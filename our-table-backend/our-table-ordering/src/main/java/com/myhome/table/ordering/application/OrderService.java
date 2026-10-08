package com.myhome.table.ordering.application;

import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.IdempotencyService;
import com.myhome.table.common.security.UserContext;
import com.myhome.table.common.util.*;
import com.myhome.table.ordering.domain.CartModels.*;
import com.myhome.table.ordering.domain.OrderModels.*;
import com.myhome.table.ordering.infrastructure.CartRepository;
import com.myhome.table.ordering.infrastructure.CartRepository.OrderRow;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
  private final CartRepository repo;
  private final CartService cart;
  private final OrderEffects effects;
  private final IdempotencyService idem;
  private final Clock clock;

  public OrderService(
      CartRepository repo,
      CartService cart,
      OrderEffects effects,
      IdempotencyService idem,
      Clock clock) {
    this.repo = repo;
    this.cart = cart;
    this.effects = effects;
    this.idem = idem;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Detail detail(UserContext user, long id) {
    var order = repo.order(id, false);
    access(user, order);
    return project(user, order);
  }

  private void access(UserContext user, OrderRow order) {
    user.requireDaily(clock.instant());
    if (open(order)
        || Objects.equals(user.chefRestaurantId(), order.restaurantId())
        || participant(order.id(), user.userId())) return;
    throw new ApiException(403, "ORDER_FORBIDDEN", "只能查看本人参与或本店的历史餐单");
  }

  private boolean participant(long order, long user) {
    return repo.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM meal_order_participant WHERE meal_order_id=? AND user_id=?",
                Long.class,
                order,
                user)
        > 0;
  }

  private boolean open(OrderRow o) {
    return Set.of("DRAFT", "IN_PROGRESS").contains(o.status())
        && clock.instant().isBefore(o.cutoff());
  }

  private boolean meta(UserContext u, OrderRow o, Long initiator) {
    return u.chefRestaurantId() != null || Objects.equals(initiator, u.userId());
  }

  private boolean cancel(UserContext u, OrderRow o, Long initiator) {
    return Objects.equals(u.chefRestaurantId(), o.restaurantId())
        || Objects.equals(initiator, u.userId());
  }

  private Long initiator(long id) {
    return repo.jdbc()
        .queryForObject(
            "SELECT initiator_user_id FROM meal_order WHERE id=? FOR SHARE", Long.class, id);
  }

  private Detail project(UserContext user, OrderRow order) {
    return project(user, order, false);
  }

  Detail project(UserContext user, OrderRow order, boolean lock) {
    var row =
        repo.jdbc()
            .queryForMap(
                "SELECT o.*,r.name AS current_name FROM meal_order o JOIN restaurant r ON r.id=o.restaurant_id WHERE o.id=?"
                    + (lock ? " FOR SHARE" : ""),
                order.id());
    var participants =
        repo.jdbc()
            .query(
                "SELECT user_id,display_name_snapshot,is_initiator,is_orderer,is_collaborator,is_chef FROM meal_order_participant WHERE meal_order_id=? ORDER BY id"
                    + (lock ? " FOR SHARE" : ""),
                (r, n) ->
                    new Participant(
                        r.getLong(1),
                        r.getString(2),
                        r.getBoolean(3),
                        r.getBoolean(4),
                        r.getBoolean(5),
                        r.getBoolean(6)),
                order.id());
    Long initiator =
        row.get("initiator_user_id") == null
            ? null
            : ((Number) row.get("initiator_user_id")).longValue();
    boolean own = Objects.equals(user.chefRestaurantId(), order.restaurantId());
    boolean reopen =
        "CANCELLED".equals(order.status())
            && clock.instant().isBefore(order.cutoff())
            && repo.slot(order.restaurantId(), order.date(), order.meal()) == null;
    return new Detail(
        order.id(),
        (String) row.get("order_no"),
        order.restaurantId(),
        Objects.toString(row.get("restaurant_name_snapshot"), row.get("current_name").toString()),
        order.date(),
        order.meal(),
        order.status(),
        order.version(),
        ((Number) row.get("diner_count")).intValue(),
        (String) row.get("remark"),
        (String) row.get("cancel_reason"),
        order.cutoff(),
        clock.instant(),
        instant(row.get("submitted_at")),
        initiator,
        repo.items(order.id(), "SUBMITTED", lock),
        repo.items(order.id(), lock),
        repo.actualItems(order.id()),
        instant(row.get("completed_at")),
        row.get("completed_at") == null
            ? null
            : instant(row.get("completed_at")).plus(Duration.ofDays(7)),
        own
            && "IN_PROGRESS".equals(order.status())
            && !order.date().isAfter(LocalDate.now(clock.withZone(BusinessTime.ZONE))),
        participants,
        open(order),
        open(order) && meta(user, order, initiator),
        open(order) && "IN_PROGRESS".equals(order.status()) && cancel(user, order, initiator),
        reopen,
        own,
        "IN_PROGRESS".equals(order.status()) && !clock.instant().isBefore(order.cutoff()),
        effects.notificationState(order.id(), user.userId()));
  }

  private Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
    return ((Timestamp) value).toInstant();
  }

  @Transactional(readOnly = true)
  public Page list(UserContext user, String state, int page, int size) {
    return listFiltered(user, state, page, size, null);
  }

  @Transactional(readOnly = true)
  public Page chefList(UserContext user, String state, int page, int size) {
    return listFiltered(user, state, page, size, user.requireChef());
  }

  private Page listFiltered(UserContext user, String state, int page, int size, Long restaurant) {
    user.requireDaily(clock.instant());
    if (!Set.of("IN_PROGRESS", "HISTORY", "COMPLETED", "CANCELLED").contains(state)
        || page < 1
        || size < 1
        || size > 30) throw ApiException.invalid("列表筛选或分页无效");
    String filter =
        "HISTORY".equals(state) ? "o.status IN ('COMPLETED','CANCELLED')" : "o.status=?";
    String access =
        "(o.restaurant_id=? OR EXISTS(SELECT 1 FROM meal_order_participant p WHERE p.meal_order_id=o.id AND p.user_id=?)";
    if ("IN_PROGRESS".equals(state)) access += " OR o.cutoff_at>?";
    access += ")";
    var args = new ArrayList<Object>();
    if (!"HISTORY".equals(state)) args.add(state);
    if (restaurant != null) args.add(restaurant);
    args.add(user.chefRestaurantId());
    args.add(user.userId());
    if ("IN_PROGRESS".equals(state)) args.add(Timestamp.from(clock.instant()));
    args.add(size + 1);
    args.add((page - 1) * size);
    var ids =
        repo.jdbc()
            .queryForList(
                "SELECT o.id FROM meal_order o WHERE "
                    + filter
                    + (restaurant == null ? "" : " AND o.restaurant_id=?")
                    + " AND "
                    + access
                    + " ORDER BY o.meal_date DESC,o.id DESC LIMIT ? OFFSET ?",
                Long.class,
                args.toArray());
    return new Page(
        ids.stream().limit(size).map(id -> project(user, repo.order(id, false))).toList(),
        page,
        size,
        ids.size() > size);
  }

  @Transactional
  public Preview preview(UserContext user, long id) {
    var o = cart.writable(user, id);
    var invalid = new ArrayList<InvalidItem>();
    var pending = repo.items(id, true);
    for (var item : pending) {
      try {
        cart.menu(
            o, item.dishId(), null, item.selections().stream().map(Selection::optionId).toList());
      } catch (ApiException e) {
        invalid.add(new InvalidItem(item.id(), e.getMessage()));
      }
    }
    boolean eligible = eligible(user, o, pending);
    return new Preview(
        project(user, o, true),
        invalid,
        !pending.isEmpty() && invalid.isEmpty() && eligible,
        fingerprint(pending));
  }

  private boolean eligible(UserContext user, OrderRow o, List<Item> pending) {
    return user.chefRestaurantId() != null
        || participant(o.id(), user.userId())
        || pending.stream().anyMatch(i -> i.contributorId() == user.userId());
  }

  public Mutation submit(UserContext user, long id, Submit form, String key) {
    user.requireDaily(clock.instant());
    metadata(form);
    return idem.execute(
        user.userId(),
        "ordering/submit/" + id,
        key,
        cart.encode(form),
        Mutation.class,
        () -> {
          var o = cart.writable(user, id);
          CartService.version(o.version(), form.version());
          var pending = repo.items(id, true);
          if (pending.isEmpty()) throw new ApiException(409, "EMPTY_CART", "请选择至少一道菜");
          if (!eligible(user, o, pending))
            throw new ApiException(403, "ORDER_FORBIDDEN", "请先参与这份清单");
          if ("IN_PROGRESS".equals(o.status()) && !meta(user, o, initiator(id))) {
            var row =
                repo.jdbc()
                    .queryForMap(
                        "SELECT diner_count,remark FROM meal_order WHERE id=? FOR SHARE", id);
            if (((Number) row.get("diner_count")).intValue() != form.dinerCount()
                || !Objects.equals(normalize((String) row.get("remark")), normalize(form.remark())))
              throw new ApiException(403, "META_FORBIDDEN", "仅主厨或发起人可修改人数和备注");
          }
          if (!Objects.equals(form.menuFingerprint(), fingerprint(pending)))
            throw new ApiException(409, "MENU_CHANGED", "菜单已变化，请重新核对提交确认页");
          if (effects.chef(o.restaurantId()) == null)
            throw new ApiException(409, "RESTAURANT_NOT_READY", "本餐厅尚未绑定主厨，暂不能提交餐单");
          var submitted = new ArrayList<>(repo.items(id, "SUBMITTED", true));
          for (var item : pending) {
            var selection =
                cart.menu(
                    o,
                    item.dishId(),
                    null,
                    item.selections().stream().map(Selection::optionId).toList());
            String digest = cart.digest(selection.selections());
            var target =
                submitted.stream()
                    .filter(
                        i ->
                            i.dishId() == item.dishId()
                                && i.contributorId() == item.contributorId()
                                && cart.digest(i.selections()).equals(digest))
                    .findFirst();
            if (target.isPresent()) {
              var t = target.get();
              if (t.quantity() + item.quantity() > 99) throw ApiException.invalid("合并后每行份数不能超过99");
              repo.jdbc()
                  .update(
                      "UPDATE meal_order_item SET quantity=quantity+?,version=version+1 WHERE id=?",
                      item.quantity(),
                      t.id());
              repo.jdbc()
                  .update(
                      "UPDATE meal_order_item SET removed_at=?,removed_by_user_id=?,version=version+1 WHERE id=?",
                      Timestamp.from(clock.instant()),
                      user.userId(),
                      item.id());
            } else {
              if (submitted.size() >= 100) throw ApiException.invalid("正式餐单最多100行");
              String recipe =
                  repo.jdbc()
                      .queryForObject(
                          "SELECT recipe FROM dish WHERE id=?", String.class, item.dishId());
              repo.jdbc()
                  .update(
                      "UPDATE meal_order_item SET item_stage='SUBMITTED',submitted_at=?,dish_name_snapshot=?,image_object_key_snapshot=?,spec_snapshot=?,recipe_snapshot=?,version=version+1 WHERE id=?",
                      Timestamp.from(clock.instant()),
                      selection.name(),
                      selection.image(),
                      cart.encode(selection.selections()),
                      recipe,
                      item.id());
              submitted.add(item);
            }
            effects.participant(id, item.contributorId(), false, true, false, false);
          }
          boolean first = "DRAFT".equals(o.status());
          Long chef = effects.chef(o.restaurantId());
          effects.participant(
              id,
              user.userId(),
              first,
              first || pending.stream().anyMatch(i -> i.contributorId() == user.userId()),
              true,
              false);
          if (chef != null) effects.participant(id, chef, false, false, false, true);
          repo.jdbc()
              .update(
                  "UPDATE meal_order SET status='IN_PROGRESS',order_no=COALESCE(order_no,?),initiator_user_id=COALESCE(initiator_user_id,?),restaurant_name_snapshot=COALESCE(restaurant_name_snapshot,(SELECT name FROM restaurant WHERE id=?)),submitted_at=COALESCE(submitted_at,?),diner_count=?,remark=?,version=version+1 WHERE id=?",
                  "OT" + o.date().toString().replace("-", "") + "-" + id,
                  user.userId(),
                  o.restaurantId(),
                  Timestamp.from(clock.instant()),
                  form.dinerCount(),
                  normalize(form.remark()),
                  id);
          audit(user, id, first ? "ORDER_SUBMIT" : "ORDER_APPEND");
          if (chef != null) {
            if (first)
              effects.event(
                  id,
                  chef,
                  "ORDER_SUBMITTED",
                  "order:" + id + ":submitted:" + chef,
                  null,
                  clock.instant());
            else effects.changed(id, "ORDER_APPENDED");
          }
          return new Mutation(id, o.version() + 1);
        });
  }

  @Transactional
  public Mutation meta(UserContext user, long id, Submit form) {
    metadata(form);
    var o = cart.writable(user, id);
    if (!"IN_PROGRESS".equals(o.status())) throw ApiException.invalid("请在提交确认页填写人数和备注");
    if (!meta(user, o, initiator(id)))
      throw new ApiException(403, "META_FORBIDDEN", "仅主厨或发起人可修改人数和备注");
    CartService.version(o.version(), form.version());
    repo.jdbc()
        .update(
            "UPDATE meal_order SET diner_count=?,remark=?,version=version+1 WHERE id=?",
            form.dinerCount(),
            normalize(form.remark()),
            id);
    effects.participant(id, user.userId(), false, false, true, false);
    effects.changed(id, "ORDER_CHANGED");
    audit(user, id, "ORDER_META");
    return new Mutation(id, o.version() + 1);
  }

  public Mutation cancel(UserContext user, long id, Cancel form, String key) {
    user.requireDaily(clock.instant());
    String reason = normalize(form.reason());
    return idem.execute(
        user.userId(),
        "ordering/cancel/" + id,
        key,
        cart.encode(form),
        Mutation.class,
        () -> {
          var o = cart.writable(user, id);
          if (!"IN_PROGRESS".equals(o.status()) || !cancel(user, o, initiator(id)))
            throw new ApiException(403, "CANCEL_ONLY", "仅本店主厨或发起人可取消进行中餐单");
          CartService.version(o.version(), form.version());
          repo.jdbc()
              .update(
                  "UPDATE meal_order SET status='CANCELLED',cancelled_at=?,cancelled_by_user_id=?,cancel_reason=?,version=version+1 WHERE id=?",
                  Timestamp.from(clock.instant()),
                  user.userId(),
                  reason,
                  id);
          effects.cancelOld(id);
          for (long recipient :
              repo.jdbc()
                  .queryForList(
                      "SELECT user_id FROM meal_order_participant WHERE meal_order_id=? AND user_id<>?",
                      Long.class,
                      id,
                      user.userId()))
            effects.event(
                id,
                recipient,
                "ORDER_CANCELLED",
                "order:" + id + ":cancelled:" + recipient,
                null,
                clock.instant());
          audit(user, id, "ORDER_CANCEL");
          return new Mutation(id, o.version() + 1);
        });
  }

  public Mutation reopen(UserContext user, long source, Long expected, String key) {
    user.requireDaily(clock.instant());
    return idem.execute(
        user.userId(),
        "ordering/reopen/" + source,
        key,
        cart.encode(new Version(expected)),
        Mutation.class,
        () -> {
          var initial = repo.order(source, false);
          repo.restaurant(initial.restaurantId(), true);
          var o = repo.order(source, true);
          access(user, o);
          BusinessTime.requireOpen(o.date(), o.meal(), clock.instant());
          CartService.version(o.version(), expected);
          if (!"CANCELLED".equals(o.status())
              || repo.slot(o.restaurantId(), o.date(), o.meal()) != null)
            throw new ApiException(409, "SLOT_OCCUPIED", "该餐次已有清单或餐单，请查看最新状态");
          long id =
              repo.insert(
                  "INSERT INTO meal_order(restaurant_id,meal_date,meal_period,meal_slot,cutoff_at,source_cancelled_id) VALUES(?,?,?,?,?,?)",
                  o.restaurantId(),
                  o.date(),
                  o.meal().name(),
                  CartRepository.slotKey(o.date(), o.meal()),
                  Timestamp.from(o.cutoff()),
                  source);
          audit(user, id, "ORDER_REOPEN");
          return new Mutation(id, 0);
        });
  }

  @Transactional(readOnly = true)
  public Map<String, String> recipe(UserContext user, long id, long item) {
    var o = repo.order(id, false);
    user.requireChef(o.restaurantId());
    return Map.of(
        "recipe",
        Objects.toString(
            repo.jdbc()
                .queryForObject(
                    "SELECT recipe_snapshot FROM meal_order_item WHERE id=? AND meal_order_id=? AND item_stage='SUBMITTED'",
                    String.class,
                    item,
                    id),
            ""));
  }

  private String fingerprint(List<Item> pending) {
    var values = new ArrayList<Object>();
    for (var item : pending) {
      values.add(
          List.of(
              item.id(),
              item.version(),
              repo.jdbc()
                  .queryForObject(
                      "SELECT version FROM dish WHERE id=? FOR SHARE", Long.class, item.dishId())));
    }
    return Tokens.sha256(cart.encode(values));
  }

  private void metadata(Submit f) {
    if (f == null || f.dinerCount() == null || f.dinerCount() < 1 || f.dinerCount() > 20)
      throw ApiException.invalid("用餐人数为1—20");
    normalize(f.remark());
  }

  private String normalize(String text) {
    return text == null || text.isBlank() ? null : TextRules.required(text, 100, 2048, true);
  }

  private void audit(UserContext u, long id, String operation) {
    repo.jdbc()
        .update(
            "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id,request_id,summary_json) VALUES('MEAL_ORDER',?,?,?,?,JSON_OBJECT('version',(SELECT version FROM meal_order WHERE id=?)))",
            id,
            operation,
            u.userId(),
            MDC.get("requestId"),
            id);
  }
}
