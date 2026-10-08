package com.myhome.table.ordering.application;

import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.HomeStatisticsCache;
import com.myhome.table.common.persistence.IdempotencyService;
import com.myhome.table.common.security.UserContext;
import com.myhome.table.common.util.BusinessTime;
import com.myhome.table.common.util.TextRules;
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
public class MealConfirmationService {
  private final CartRepository repo;
  private final CartService cart;
  private final OrderService orders;
  private final OrderEffects effects;
  private final IdempotencyService idem;
  private final Clock clock;
  private final HomeStatisticsCache homeCache;

  public MealConfirmationService(
      CartRepository repo,
      CartService cart,
      OrderService orders,
      OrderEffects effects,
      IdempotencyService idem,
      Clock clock,
      HomeStatisticsCache homeCache) {
    this.repo = repo;
    this.cart = cart;
    this.orders = orders;
    this.effects = effects;
    this.idem = idem;
    this.clock = clock;
    this.homeCache = homeCache;
  }

  // 与普通修改采用一致的餐厅→餐单锁顺序，但不限制截止时间，支持晚些时候补确认。
  private OrderRow writable(UserContext user, long id) {
    var initial = repo.order(id, false);
    user.requireDaily(clock.instant());
    user.requireChef(initial.restaurantId());
    repo.restaurant(initial.restaurantId(), true);
    var o = repo.order(id, true);
    if (!"IN_PROGRESS".equals(o.status()))
      throw new ApiException(409, "MEAL_ENDED", "餐单已结束，请查看最新状态");
    if (o.date().isAfter(LocalDate.now(clock.withZone(BusinessTime.ZONE))))
      throw new ApiException(409, "MEAL_NOT_STARTED", "用餐日期尚未到，不能提前确认");
    return o;
  }

  @Transactional
  public Confirmation preview(UserContext user, long id) {
    var o = writable(user, id);
    var candidates =
        repo.jdbc()
            .query(
                "SELECT d.id,d.name,d.version FROM dish d JOIN menu_category c ON c.id=d.category_id WHERE d.restaurant_id=? AND d.deleted_at IS NULL AND c.deleted_at IS NULL ORDER BY c.sort_order,d.id FOR SHARE",
                (r, n) ->
                    new Candidate(
                        r.getLong(1), r.getString(2), r.getLong(3), dimensions(r.getLong(1))),
                o.restaurantId());
    return new Confirmation(orders.project(user, o, true), candidates);
  }

  private List<Dimension> dimensions(long dish) {
    return repo.jdbc()
        .query(
            "SELECT id,name FROM dish_spec_dimension WHERE dish_id=? AND deleted_at IS NULL ORDER BY sort_order,id FOR SHARE",
            (r, n) ->
                new Dimension(
                    r.getLong(1),
                    r.getString(2),
                    repo.jdbc()
                        .query(
                            "SELECT id,name,is_default FROM dish_spec_option WHERE dimension_id=? AND deleted_at IS NULL ORDER BY sort_order,id FOR SHARE",
                            (v, k) -> new Option(v.getLong(1), v.getString(2), v.getBoolean(3)),
                            r.getLong(1))),
            dish);
  }

  private record Actual(
      long dish,
      String name,
      String image,
      List<Selection> selections,
      int quantity,
      Long source) {}

  public Mutation complete(UserContext user, long id, Complete form, String key) {
    user.requireDaily(clock.instant());
    if (form == null || form.items() == null || form.items().isEmpty() || form.items().size() > 100)
      throw ApiException.invalid("请保留1—100行实际菜品；没做饭请选择“这顿没做”");
    return idem.execute(
        user.userId(),
        "ordering/complete/" + id,
        key,
        cart.encode(form),
        Mutation.class,
        () -> {
          var o = writable(user, id);
          CartService.version(o.version(), form.version());
          var sources = repo.items(id, "SUBMITTED", true);
          var actual = new LinkedHashMap<String, Actual>();
          for (var input : form.items()) {
            if (input == null
                || input.quantity() == null
                || input.quantity() < 1
                || input.quantity() > 99) throw ApiException.invalid("每道实际菜品为1—99份");
            Actual value;
            if (input.sourceOrderItemId() != null) {
              var source =
                  sources.stream()
                      .filter(i -> i.id() == input.sourceOrderItemId())
                      .findFirst()
                      .orElseThrow(() -> new ApiException(409, "SOURCE_CHANGED", "原点菜已变化，请重新核对"));
              // 原菜保留提交快照，不能通过请求参数伪造名称、餐厅或失效规格。
              value =
                  new Actual(
                      source.dishId(),
                      source.dishName(),
                      source.imageObjectKey(),
                      source.selections(),
                      input.quantity(),
                      source.id());
            } else {
              if (input.dishId() == null || input.dishVersion() == null)
                throw ApiException.invalid("请从本店菜单选择补做的菜");
              var selected =
                  cart.menu(o, input.dishId(), input.dishVersion(), input.optionIds(), true);
              value =
                  new Actual(
                      input.dishId(),
                      selected.name(),
                      selected.image(),
                      selected.selections(),
                      input.quantity(),
                      null);
            }
            String group = value.dish() + ":" + cart.digest(value.selections());
            var previous = actual.get(group);
            if (previous != null) {
              int total = previous.quantity() + value.quantity();
              if (total > 99) throw ApiException.invalid("同菜同规格合计不能超过99份，请调整实际份数");
              value =
                  new Actual(
                      previous.dish(),
                      previous.name(),
                      previous.image(),
                      previous.selections(),
                      total,
                      previous.source() != null ? previous.source() : value.source());
            }
            actual.put(group, value);
          }
          for (var value : actual.values())
            repo.jdbc()
                .update(
                    "INSERT INTO meal_actual_item(meal_order_id,dish_id,source_type,source_order_item_id,confirmed_by_user_id,dish_name_snapshot,image_object_key_snapshot,spec_key,spec_snapshot,quantity) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id,
                    value.dish(),
                    value.source() == null ? "CONFIRM_ADDED" : "ORDERED",
                    value.source(),
                    user.userId(),
                    value.name(),
                    value.image(),
                    cart.digest(value.selections()),
                    cart.encode(value.selections()),
                    value.quantity());
          repo.jdbc()
              .update(
                  "UPDATE meal_order SET status='COMPLETED',completed_at=?,confirmed_by_user_id=?,version=version+1 WHERE id=?",
                  Timestamp.from(clock.instant()),
                  user.userId(),
                  id);
          effects.cancelOld(id);
          for (long recipient :
              repo.jdbc()
                  .queryForList(
                      "SELECT user_id FROM meal_order_participant WHERE meal_order_id=? AND is_orderer=1",
                      Long.class,
                      id)) {
            repo.jdbc()
                .update(
                    "INSERT INTO review_access_grant(meal_order_id,user_id) VALUES(?,?) ON DUPLICATE KEY UPDATE user_id=user_id",
                    id,
                    recipient);
            effects.event(
                id,
                recipient,
                "REVIEW_INVITED",
                "order:" + id + ":review:" + recipient,
                null,
                clock.instant());
          }
          audit(user, id, "MEAL_COMPLETE");
          homeCache.invalidateAfterCommit();
          return new Mutation(id, o.version() + 1);
        });
  }

  public Mutation notCooked(UserContext user, long id, Cancel form, String key) {
    user.requireDaily(clock.instant());
    if (form == null) throw ApiException.invalid("请确认这顿没做");
    String reason =
        form.reason() == null || form.reason().isBlank()
            ? "这顿没做"
            : TextRules.required(form.reason(), 100, 2048, true);
    return idem.execute(
        user.userId(),
        "ordering/not-cooked/" + id,
        key,
        cart.encode(form),
        Mutation.class,
        () -> {
          var o = writable(user, id);
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
          audit(user, id, "MEAL_NOT_COOKED");
          return new Mutation(id, o.version() + 1);
        });
  }

  private void audit(UserContext user, long id, String operation) {
    repo.jdbc()
        .update(
            "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id,request_id,summary_json) VALUES('MEAL_ORDER',?,?,?,?,JSON_OBJECT('actualCount',(SELECT COUNT(*) FROM meal_actual_item WHERE meal_order_id=?)))",
            id,
            operation,
            user.userId(),
            MDC.get("requestId"),
            id);
  }
}
