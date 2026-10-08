package com.myhome.table.ordering.application;

import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.HomeStatisticsCache;
import com.myhome.table.common.persistence.IdempotencyService;
import com.myhome.table.common.security.UserContext;
import com.myhome.table.common.util.*;
import com.myhome.table.ordering.domain.ReviewModels.*;
import com.myhome.table.ordering.infrastructure.CartRepository;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {
  private final CartRepository repo;
  private final CartService cart;
  private final IdempotencyService idem;
  private final Clock clock;
  private final HomeStatisticsCache homeCache;

  public ReviewService(
      CartRepository repo,
      CartService cart,
      IdempotencyService idem,
      Clock clock,
      HomeStatisticsCache homeCache) {
    this.repo = repo;
    this.cart = cart;
    this.idem = idem;
    this.clock = clock;
    this.homeCache = homeCache;
  }

  private record Scope(
      long id,
      long restaurant,
      String name,
      LocalDate date,
      BusinessTime.Meal meal,
      Instant deadline) {}

  private Scope completed(long id, boolean lock) {
    var rows =
        repo.jdbc()
            .query(
                "SELECT id,restaurant_id,restaurant_name_snapshot,meal_date,meal_period,completed_at FROM meal_order WHERE id=? AND status='COMPLETED'"
                    + (lock ? " FOR UPDATE" : ""),
                (r, n) ->
                    new Scope(
                        r.getLong(1),
                        r.getLong(2),
                        r.getString(3),
                        r.getDate(4).toLocalDate(),
                        BusinessTime.Meal.valueOf(r.getString(5)),
                        r.getTimestamp(6).toInstant().plus(Duration.ofDays(7))),
                id);
    if (rows.isEmpty()) throw new ApiException(404, "REVIEW_UNAVAILABLE", "餐单尚未完成或已取消，不能评价");
    return rows.get(0);
  }

  private boolean grant(long order, long user) {
    return repo.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM review_access_grant WHERE meal_order_id=? AND user_id=?",
                Long.class,
                order,
                user)
        > 0;
  }

  private void remember(long order, long user, Long share) {
    repo.jdbc()
        .update(
            "INSERT INTO review_access_grant(meal_order_id,user_id,source_share_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE user_id=user_id",
            order,
            user,
            share);
  }

  private Scope access(UserContext user, long id, String token, boolean lock) {
    Long source = null;
    if (token != null) {
      if (!token.matches("[A-Za-z0-9_-]{43}"))
        throw new ApiException(404, "INVALID_INVITATION", "评价邀请无效");
      var shares =
          repo.jdbc()
              .queryForList(
                  "SELECT id,meal_order_id,status,expires_at FROM review_share WHERE token_hash=?"
                      + (lock ? " FOR SHARE" : ""),
                  Tokens.sha256(token));
      if (shares.isEmpty() || !"ACTIVE".equals(shares.get(0).get("status")))
        throw new ApiException(404, "INVALID_INVITATION", "评价邀请无效或已停用");
      var share = shares.get(0);
      id = ((Number) share.get("meal_order_id")).longValue();
      source = ((Number) share.get("id")).longValue();
      if (!clock.instant().isBefore(instant(share.get("expires_at"))) && !grant(id, user.userId()))
        throw new ApiException(410, "INVITATION_EXPIRED", "邀请已过期，不能取得新的评价资格");
    }
    var scope = completed(id, lock);
    boolean qualified = grant(id, user.userId());
    if (token == null && !qualified) {
      boolean participant =
          repo.jdbc()
                  .queryForObject(
                      "SELECT COUNT(*) FROM meal_order_participant WHERE meal_order_id=? AND user_id=?",
                      Long.class,
                      id,
                      user.userId())
              > 0;
      if (!user.dailyAllowed(clock.instant())
          || !(Objects.equals(user.chefRestaurantId(), scope.restaurant()) || participant))
        throw new ApiException(403, "REVIEW_FORBIDDEN", "请通过该餐单的有效评价邀请进入");
    }
    if (!qualified && clock.instant().isBefore(scope.deadline()))
      remember(id, user.userId(), source);
    return scope;
  }

  private Instant instant(Object value) {
    return value instanceof LocalDateTime d
        ? d.toInstant(ZoneOffset.UTC)
        : ((Timestamp) value).toInstant();
  }

  private void open(Scope s) {
    if (!clock.instant().isBefore(s.deadline()))
      throw new ApiException(410, "REVIEW_EXPIRED", "7天评价期限已结束，已发表内容可只读查看");
  }

  @Transactional
  public Sheet sheet(UserContext user, long id, String token) {
    return project(user, access(user, id, token, false));
  }

  private Sheet project(UserContext u, Scope s) {
    var actual = repo.actualItems(s.id());
    var groups =
        new LinkedHashMap<Long, List<com.myhome.table.ordering.domain.OrderModels.ActualItem>>();
    for (var item : actual) groups.computeIfAbsent(item.dishId(), k -> new ArrayList<>()).add(item);
    var foods = new ArrayList<Food>();
    int reviewed = 0;
    for (var entry : groups.entrySet()) {
      var items = entry.getValue();
      var first = items.get(0);
      var stats =
          repo.jdbc()
              .queryForMap(
                  "SELECT AVG(r.rating) AS average,COUNT(*) AS count FROM dish_review r JOIN meal_order o ON o.id=r.meal_order_id WHERE r.dish_id=? AND o.status='COMPLETED'",
                  first.dishId());
      var mine =
          repo.jdbc()
              .query(
                  "SELECT * FROM dish_review WHERE meal_order_id=? AND dish_id=? AND reviewer_user_id=?",
                  (r, n) -> review(r, s.deadline()),
                  s.id(),
                  first.dishId(),
                  u.userId());
      if (!mine.isEmpty()) reviewed++;
      foods.add(
          new Food(
              first.dishId(),
              first.dishName(),
              first.imageObjectKey(),
              items.stream().mapToInt(i -> i.quantity()).sum(),
              items.stream()
                  .map(
                      i ->
                          i.selections().stream()
                              .map(v -> v.dimensionName() + "：" + v.optionName())
                              .reduce((a, b) -> a + " · " + b)
                              .orElse("无规格"))
                  .distinct()
                  .toList(),
              stats.get("average") == null ? null : ((Number) stats.get("average")).doubleValue(),
              ((Number) stats.get("count")).longValue(),
              mine.isEmpty() ? null : mine.get(0)));
    }
    return new Sheet(
        s.id(),
        s.name(),
        s.date(),
        s.meal(),
        clock.instant(),
        s.deadline(),
        clock.instant().isBefore(s.deadline()),
        reviewed,
        foods);
  }

  private Review review(ResultSet r, Instant deadline) throws SQLException {
    var first = r.getTimestamp("first_submitted_at").toInstant();
    var modified = r.getTimestamp("modified_at");
    int count = r.getInt("modify_count");
    return new Review(
        r.getLong("id"),
        r.getLong("meal_order_id"),
        r.getLong("dish_id"),
        repo.jdbc()
            .queryForObject(
                "SELECT MIN(dish_name_snapshot) FROM meal_actual_item WHERE meal_order_id=? AND dish_id=?",
                String.class,
                r.getLong("meal_order_id"),
                r.getLong("dish_id")),
        r.getLong("reviewer_user_id"),
        r.getString("reviewer_name_snapshot"),
        r.getString("reviewer_avatar_snapshot"),
        r.getInt("rating"),
        r.getString("comment_text"),
        count,
        r.getLong("version"),
        first,
        modified == null ? null : modified.toInstant(),
        count == 0
            && clock.instant().isBefore(first.plus(Duration.ofHours(24)))
            && clock.instant().isBefore(deadline));
  }

  private String comment(Integer rating, String value) {
    if (rating == null || rating < 1 || rating > 5) throw ApiException.invalid("请选择1—5整星评分");
    return value == null || value.isBlank() ? null : TextRules.required(value, 200, 4096, true);
  }

  public Review write(UserContext u, long order, String token, Write f, String key) {
    if (f == null || f.dishId() == null) throw ApiException.invalid("请选择实际做过的菜");
    String text = comment(f.rating(), f.comment());
    // 幂等记录不保存分享明文，普通/分享入口共用记录和唯一键。
    return idem.execute(
        u.userId(),
        "ordering/review/" + order + "/" + (token == null ? "normal" : Tokens.sha256(token)),
        key,
        cart.encode(f),
        Review.class,
        () -> {
          var s = access(u, order, token, true);
          open(s);
          if (repo.jdbc()
                  .queryForObject(
                      "SELECT COUNT(*) FROM meal_actual_item WHERE meal_order_id=? AND dish_id=?",
                      Long.class,
                      s.id(),
                      f.dishId())
              == 0) throw ApiException.invalid("只能评价实际做过的菜");
          if (repo.jdbc()
                  .queryForObject(
                      "SELECT COUNT(*) FROM dish_review WHERE meal_order_id=? AND dish_id=? AND reviewer_user_id=?",
                      Long.class,
                      s.id(),
                      f.dishId(),
                      u.userId())
              > 0) throw new ApiException(409, "REVIEW_EXISTS", "这道菜已评价，请使用本人评价修改入口");
          long id =
              repo.insert(
                  "INSERT INTO dish_review(meal_order_id,dish_id,reviewer_user_id,reviewer_name_snapshot,reviewer_avatar_snapshot,rating,comment_text,first_submitted_at) SELECT ?,?,id,nickname,avatar_url,?,?,? FROM app_user WHERE id=?",
                  s.id(),
                  f.dishId(),
                  f.rating(),
                  text,
                  Timestamp.from(clock.instant()),
                  u.userId());
          audit(u, id, "REVIEW_CREATE");
          homeCache.invalidateAfterCommit();
          return repo.jdbc()
              .queryForObject(
                  "SELECT * FROM dish_review WHERE id=?", (r, n) -> review(r, s.deadline()), id);
        });
  }

  public Review modify(UserContext u, long id, Modify f, String key) {
    if (f == null) throw ApiException.invalid("请填写评价");
    String text = comment(f.rating(), f.comment());
    return idem.execute(
        u.userId(),
        "ordering/review-modify/" + id,
        key,
        cart.encode(f),
        Review.class,
        () -> {
          var rows =
              repo.jdbc()
                  .queryForList(
                      "SELECT meal_order_id,reviewer_user_id FROM dish_review WHERE id=?", id);
          if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "评价不存在");
          if (((Number) rows.get(0).get("reviewer_user_id")).longValue() != u.userId())
            throw new ApiException(403, "REVIEW_OWNER_ONLY", "只能修改本人评价");
          var s = completed(((Number) rows.get(0).get("meal_order_id")).longValue(), true);
          open(s);
          var current =
              repo.jdbc()
                  .queryForObject(
                      "SELECT * FROM dish_review WHERE id=? FOR UPDATE",
                      (r, n) -> review(r, s.deadline()),
                      id);
          CartService.version(current.version(), f.version());
          if (!current.canModify())
            throw new ApiException(409, "REVIEW_LOCKED", "评价已修改一次或超过24小时，不能再次修改");
          repo.jdbc()
              .update(
                  "UPDATE dish_review SET rating=?,comment_text=?,modify_count=1,modified_at=?,version=version+1 WHERE id=?",
                  f.rating(),
                  text,
                  Timestamp.from(clock.instant()),
                  id);
          audit(u, id, "REVIEW_MODIFY");
          homeCache.invalidateAfterCommit();
          return repo.jdbc()
              .queryForObject(
                  "SELECT * FROM dish_review WHERE id=?", (r, n) -> review(r, s.deadline()), id);
        });
  }

  @Transactional
  public Share share(UserContext u, long id) {
    var s = access(u, id, null, true);
    open(s);
    String token = Tokens.random();
    repo.jdbc()
        .update(
            "INSERT INTO review_share(meal_order_id,token_hash,created_by_user_id,expires_at) VALUES(?,?,?,?)",
            id,
            Tokens.sha256(token),
            u.userId(),
            Timestamp.from(s.deadline()));
    return new Share(token, s.deadline());
  }

  private void page(int page) {
    if (page < 1 || page > 10000) throw ApiException.invalid("分页无效");
  }

  @Transactional(readOnly = true)
  public Reviews mine(UserContext u, int page) {
    page(page);
    var rows =
        repo.jdbc()
            .query(
                "SELECT r.*,o.completed_at FROM dish_review r JOIN meal_order o ON o.id=r.meal_order_id WHERE r.reviewer_user_id=? ORDER BY r.first_submitted_at DESC,r.id DESC LIMIT 21 OFFSET ?",
                (r, n) ->
                    review(r, r.getTimestamp("completed_at").toInstant().plus(Duration.ofDays(7))),
                u.userId(),
                (page - 1) * 20);
    return new Reviews(rows.stream().limit(20).toList(), page, rows.size() > 20);
  }

  @Transactional(readOnly = true)
  public Invitations invitations(UserContext u, int page) {
    page(page);
    var rows =
        repo.jdbc()
            .query(
                "SELECT o.id,o.restaurant_name_snapshot,o.meal_date,o.meal_period,o.completed_at,(SELECT COUNT(DISTINCT a.dish_id) FROM meal_actual_item a WHERE a.meal_order_id=o.id) AS total,(SELECT COUNT(*) FROM dish_review r WHERE r.meal_order_id=o.id AND r.reviewer_user_id=?) AS reviewed FROM meal_order o JOIN meal_order_participant p ON p.meal_order_id=o.id WHERE p.user_id=? AND p.is_orderer=1 AND o.status='COMPLETED' ORDER BY o.completed_at DESC,o.id DESC LIMIT 21 OFFSET ?",
                (r, n) ->
                    new Invitation(
                        r.getLong(1),
                        r.getString(2),
                        r.getDate(3).toLocalDate(),
                        BusinessTime.Meal.valueOf(r.getString(4)),
                        r.getTimestamp(5).toInstant().plus(Duration.ofDays(7)),
                        r.getInt(6),
                        r.getInt(7)),
                u.userId(),
                u.userId(),
                (page - 1) * 20);
    return new Invitations(rows.stream().limit(20).toList(), page, rows.size() > 20);
  }

  @Transactional(readOnly = true)
  public DishReviews dish(UserContext u, long dish, int page) {
    u.requireDaily(clock.instant());
    page(page);
    if (repo.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM dish WHERE id=? AND deleted_at IS NULL", Long.class, dish)
        == 0) throw new ApiException(404, "NOT_FOUND", "菜品不存在");
    var rows =
        repo.jdbc()
            .query(
                "SELECT r.*,o.completed_at FROM dish_review r JOIN meal_order o ON o.id=r.meal_order_id WHERE r.dish_id=? AND o.status='COMPLETED' ORDER BY r.first_submitted_at DESC,r.id DESC LIMIT 21 OFFSET ?",
                (r, n) -> {
                  var v =
                      review(
                          r, r.getTimestamp("completed_at").toInstant().plus(Duration.ofDays(7)));
                  return new Review(
                      v.id(),
                      v.orderId(),
                      v.dishId(),
                      v.dishName(),
                      v.reviewerId(),
                      v.reviewerName(),
                      v.reviewerAvatar(),
                      v.rating(),
                      v.comment(),
                      v.modifyCount(),
                      v.version(),
                      v.firstSubmittedAt(),
                      v.modifiedAt(),
                      v.reviewerId() == u.userId() && v.canModify());
                },
                dish,
                (page - 1) * 20);
    var stats =
        repo.jdbc()
            .queryForMap(
                "SELECT AVG(r.rating) AS average,COUNT(*) AS count FROM dish_review r JOIN meal_order o ON o.id=r.meal_order_id WHERE r.dish_id=? AND o.status='COMPLETED'",
                dish);
    return new DishReviews(
        rows.stream().limit(20).toList(),
        page,
        rows.size() > 20,
        stats.get("average") == null ? null : ((Number) stats.get("average")).doubleValue(),
        ((Number) stats.get("count")).longValue());
  }

  private void audit(UserContext u, long id, String operation) {
    repo.jdbc()
        .update(
            "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id,request_id,summary_json) VALUES('DISH_REVIEW',?,?,?,?,JSON_OBJECT('version',(SELECT version FROM dish_review WHERE id=?)))",
            id,
            operation,
            u.userId(),
            MDC.get("requestId"),
            id);
  }
}
