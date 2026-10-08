package com.myhome.table.ordering.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.IdempotencyService;
import com.myhome.table.common.security.UserContext;
import com.myhome.table.common.util.*;
import com.myhome.table.ordering.domain.CartModels.*;
import com.myhome.table.ordering.infrastructure.CartRepository;
import com.myhome.table.ordering.infrastructure.CartRepository.OrderRow;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {
  private final CartRepository repo;
  private final IdempotencyService idem;
  private final ObjectMapper json;
  private final Clock clock;
  private final OrderEffects effects;

  public CartService(
      CartRepository repo,
      IdempotencyService idem,
      ObjectMapper json,
      Clock clock,
      OrderEffects effects) {
    this.repo = repo;
    this.idem = idem;
    this.json = json;
    this.clock = clock;
    this.effects = effects;
  }

  @Transactional(readOnly = true)
  public Cart read(UserContext user, long restaurant, LocalDate date, BusinessTime.Meal meal) {
    user.requireDaily(clock.instant());
    BusinessTime.requireOpen(date, meal, clock.instant());
    repo.restaurant(restaurant, false);
    var order = repo.slot(restaurant, date, meal);
    // 正式单的权限、阶段投影在下单阶段实现，不能当作空清单覆盖。
    if (order != null) editable(order);
    var items = order == null ? List.<Item>of() : repo.items(order.id());
    int dishes =
        (int)
            items.stream()
                .map(
                    i ->
                        i.dishId()
                            + ":"
                            + encode(i.selections().stream().map(Selection::optionId).toList()))
                .distinct()
                .count();
    return new Cart(
        order == null ? null : order.id(),
        restaurant,
        date,
        meal,
        BusinessTime.cutoff(date, meal),
        clock.instant(),
        order == null ? "DRAFT" : order.status(),
        order == null ? 0 : order.version(),
        items,
        order == null ? List.of() : repo.items(order.id(), "SUBMITTED", false),
        dishes,
        items.stream().mapToInt(Item::quantity).sum(),
        (int) items.stream().map(Item::contributorId).distinct().count());
  }

  @Transactional
  public Mutation ensure(
      UserContext user, long restaurant, LocalDate date, BusinessTime.Meal meal) {
    repo.restaurant(restaurant, true);
    user.requireDaily(clock.instant());
    BusinessTime.requireOpen(date, meal, clock.instant());
    var existing = repo.slot(restaurant, date, meal);
    if (existing != null) {
      editable(existing);
      return new Mutation(existing.id(), existing.version());
    }
    if (repo.jdbc()
            .queryForObject(
                "SELECT COUNT(*) FROM meal_order WHERE restaurant_id=? AND meal_slot=? AND status='CANCELLED'",
                Long.class,
                restaurant,
                CartRepository.slotKey(date, meal))
        > 0) throw new ApiException(409, "REOPEN_REQUIRED", "该餐次已取消，请从餐单页明确重新点餐");
    long id =
        repo.insert(
            "INSERT INTO meal_order(restaurant_id,meal_date,meal_period,meal_slot,cutoff_at) VALUES(?,?,?,?,?)",
            restaurant,
            date,
            meal.name(),
            CartRepository.slotKey(date, meal),
            Timestamp.from(BusinessTime.cutoff(date, meal)));
    audit(user, id, "DRAFT_CREATE", Map.of());
    return new Mutation(id, 0);
  }

  public Mutation add(UserContext user, long orderId, Add form, String key) {
    if (form == null || form.dishId() == null || form.dishVersion() == null)
      throw ApiException.invalid("请选择菜品");
    user.requireDaily(clock.instant());
    return idem.execute(
        user.userId(),
        "ordering/cart/add/" + orderId,
        key,
        encode(form),
        Mutation.class,
        () -> {
          var order = writable(user, orderId);
          var selection = menu(order, form.dishId(), form.dishVersion(), form.optionIds());
          String digest = digest(selection.selections());
          var existing =
              repo.items(orderId, true).stream()
                  .filter(
                      i ->
                          i.dishId() == form.dishId()
                              && i.contributorId() == user.userId()
                              && digest(i.selections()).equals(digest))
                  .findFirst();
          if (existing.isPresent()) {
            var item = existing.get();
            quantity(item.quantity() + 1);
            repo.jdbc()
                .update(
                    "UPDATE meal_order_item SET quantity=quantity+1,dish_name_snapshot=?,spec_snapshot=?,version=version+1 WHERE id=?",
                    selection.name(),
                    encode(selection.selections()),
                    item.id());
          } else {
            if (repo.items(orderId, true).size() >= 100) throw ApiException.invalid("每张清单最多100行");
            String nickname =
                repo.jdbc()
                    .queryForObject(
                        "SELECT nickname FROM app_user WHERE id=?", String.class, user.userId());
            repo.insert(
                "INSERT INTO meal_order_item(meal_order_id,dish_id,contributor_user_id,contributor_name_snapshot,dish_name_snapshot,image_object_key_snapshot,spec_key,spec_snapshot,quantity) VALUES(?,?,?,?,?,?,?,?,1)",
                orderId,
                form.dishId(),
                user.userId(),
                nickname,
                selection.name(),
                selection.image(),
                digest,
                encode(selection.selections()));
          }
          return changed(user, order, "ITEM_ADD", Map.of("dishId", form.dishId()));
        });
  }

  public Mutation delta(UserContext user, long orderId, long itemId, Delta form, String key) {
    if (form == null || form.delta() == null || Math.abs(form.delta()) != 1)
      throw ApiException.invalid("每次增减一份");
    user.requireDaily(clock.instant());
    return idem.execute(
        user.userId(),
        "ordering/cart/delta/" + orderId + "/" + itemId,
        key,
        encode(form),
        Mutation.class,
        () -> {
          var order = writable(user, orderId);
          var item = allItems(orderId).stream().filter(i -> i.id() == itemId).findFirst();
          // 同时减到零的请求不复活行，也不保存负数。
          if (item.isEmpty() && form.delta() == -1)
            return new Mutation(order.id(), order.version());
          if (item.isEmpty()) throw CartRepository.missing();
          var i = item.get();
          if (form.delta() > 0)
            menu(
                order, i.dishId(), null, i.selections().stream().map(Selection::optionId).toList());
          int count = i.quantity() + form.delta();
          if (count == 0) {
            lastRemoval(user, order, i.id());
            remove(user, i.id());
          } else {
            quantity(count);
            repo.jdbc()
                .update(
                    "UPDATE meal_order_item SET quantity=quantity+?,version=version+1 WHERE id=?",
                    form.delta(),
                    i.id());
          }
          return changed(
              user,
              order,
              "ITEM_DELTA",
              Map.of("itemId", itemId, "delta", form.delta(), "contributorId", i.contributorId()));
        });
  }

  @Transactional
  public Mutation edit(UserContext user, long orderId, long itemId, Edit form) {
    if (form == null || form.quantity() == null) throw ApiException.invalid("请填写份数");
    quantity(form.quantity());
    var order = writable(user, orderId);
    version(order.version(), form.version());
    var item =
        allItems(orderId).stream()
            .filter(i -> i.id() == itemId)
            .findFirst()
            .orElseThrow(CartRepository::missing);
    version(item.version(), form.itemVersion());
    var selection = menu(order, item.dishId(), null, form.optionIds());
    String digest = digest(selection.selections());
    var target =
        repo.items(orderId, stage(itemId), true).stream()
            .filter(
                i ->
                    i.id() != itemId
                        && i.dishId() == item.dishId()
                        && i.contributorId() == item.contributorId()
                        && digest(i.selections()).equals(digest))
            .findFirst();
    if (target.isPresent()) {
      var other = target.get();
      quantity(other.quantity() + form.quantity());
      remove(user, itemId);
      repo.jdbc()
          .update(
              "UPDATE meal_order_item SET quantity=quantity+?,version=version+1 WHERE id=?",
              form.quantity(),
              other.id());
    } else
      repo.jdbc()
          .update(
              "UPDATE meal_order_item SET spec_key=?,spec_snapshot=?,dish_name_snapshot=?,quantity=?,version=version+1 WHERE id=?",
              digest,
              encode(selection.selections()),
              selection.name(),
              form.quantity(),
              itemId);
    return changed(
        user, order, "ITEM_EDIT", Map.of("itemId", itemId, "contributorId", item.contributorId()));
  }

  @Transactional
  public Mutation delete(UserContext user, long orderId, long itemId, Long expected) {
    var order = writable(user, orderId);
    version(order.version(), expected);
    var item =
        allItems(orderId).stream()
            .filter(i -> i.id() == itemId)
            .findFirst()
            .orElseThrow(CartRepository::missing);
    lastRemoval(user, order, item.id());
    remove(user, item.id());
    return changed(
        user,
        order,
        "ITEM_REMOVE",
        Map.of("itemId", itemId, "contributorId", item.contributorId()));
  }

  @Transactional
  public Mutation clear(UserContext user, long orderId, Long expected) {
    var order = writable(user, orderId);
    version(order.version(), expected);
    repo.jdbc()
        .update(
            "UPDATE meal_order_item SET removed_at=?,removed_by_user_id=?,version=version+1 WHERE meal_order_id=? AND removed_at IS NULL AND item_stage='PENDING'",
            Timestamp.from(clock.instant()),
            user.userId(),
            orderId);
    return changed(user, order, "PENDING_CLEAR", Map.of());
  }

  private List<Item> allItems(long id) {
    var items = new ArrayList<>(repo.items(id, true));
    items.addAll(repo.items(id, "SUBMITTED", true));
    return items;
  }

  private String stage(long item) {
    return repo.jdbc()
        .queryForObject("SELECT item_stage FROM meal_order_item WHERE id=?", String.class, item);
  }

  private void lastRemoval(UserContext user, OrderRow order, long item) {
    if (!"SUBMITTED".equals(stage(item)) || repo.items(order.id(), "SUBMITTED", true).size() > 1)
      return;
    var initiator =
        repo.jdbc()
            .queryForObject(
                "SELECT initiator_user_id FROM meal_order WHERE id=?", Long.class, order.id());
    if (!Objects.equals(user.chefRestaurantId(), order.restaurantId())
        && !Objects.equals(initiator, user.userId()))
      throw new ApiException(403, "CANCEL_ONLY", "最后一道菜只能由本店主厨或发起人确认取消");
    throw new ApiException(409, "CANCEL_REQUIRED", "移除最后一道菜需明确取消整张餐单");
  }

  OrderRow writable(UserContext user, long id) {
    var initial = repo.order(id, false);
    // 与 core 菜单写入采用同一餐厅行锁顺序，保证选菜复核和菜单变更不穿插。
    repo.restaurant(initial.restaurantId(), true);
    var order = repo.order(id, true);
    user.requireDaily(clock.instant());
    BusinessTime.requireOpen(order.date(), order.meal(), clock.instant());
    editable(order);
    return order;
  }

  private static void editable(OrderRow order) {
    if (!Set.of("DRAFT", "IN_PROGRESS").contains(order.status()))
      throw new ApiException(409, "CART_NOT_DRAFT", "该餐次已结束，请前往餐单查看");
  }

  record MenuSelection(String name, String image, List<Selection> selections) {}

  MenuSelection menu(OrderRow order, long dish, Long expected, List<Long> optionIds) {
    return menu(order, dish, expected, optionIds, false);
  }

  MenuSelection menu(
      OrderRow order, long dish, Long expected, List<Long> optionIds, boolean actual) {
    if (optionIds == null
        || optionIds.size() > 3
        || optionIds.stream().anyMatch(Objects::isNull)
        || new HashSet<>(optionIds).size() != optionIds.size())
      throw ApiException.invalid("每个规格请选择一项");
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT d.name,m.object_key,d.version,c.category_type FROM dish d JOIN menu_category c ON c.id=d.category_id LEFT JOIN media_asset m ON m.id=d.image_media_id WHERE d.id=? AND d.restaurant_id=? AND d.deleted_at IS NULL AND c.deleted_at IS NULL"
                    + (actual ? "" : " AND d.is_on_shelf=1")
                    + " FOR SHARE",
                dish,
                order.restaurantId());
    if (rows.isEmpty()) throw new ApiException(409, "MENU_CHANGED", "菜品已下架或删除，请重新核对菜单");
    var d = rows.get(0);
    if (expected != null && ((Number) d.get("version")).longValue() != expected)
      throw new ApiException(409, "MENU_CHANGED", "菜品已更新，请重新核对菜单");
    if (!actual
        && "SEASONAL".equals(d.get("category_type"))
        && repo.jdbc()
            .queryForList(
                "SELECT supply_month FROM dish_supply_month WHERE dish_id=? AND supply_month=? FOR SHARE",
                Integer.class,
                dish,
                order.date().getMonthValue())
            .isEmpty()) throw new ApiException(409, "MENU_CHANGED", "该用餐月份不供应这道菜");
    var dimensions =
        repo.jdbc()
            .queryForList(
                "SELECT id FROM dish_spec_dimension WHERE dish_id=? AND deleted_at IS NULL ORDER BY id FOR SHARE",
                Long.class,
                dish);
    if (dimensions.size() != optionIds.size())
      throw new ApiException(409, "MENU_CHANGED", "菜品规格已更新，请重新选择");
    var selections = new ArrayList<Selection>();
    for (long dimension : dimensions) {
      var options =
          repo.jdbc()
              .query(
                  "SELECT s.id,s.name,o.id,o.name FROM dish_spec_dimension s JOIN dish_spec_option o ON o.dimension_id=s.id WHERE s.id=? AND o.deleted_at IS NULL FOR SHARE",
                  (r, n) ->
                      new Selection(r.getLong(1), r.getString(2), r.getLong(3), r.getString(4)),
                  dimension);
      var selected = options.stream().filter(o -> optionIds.contains(o.optionId())).toList();
      if (selected.size() != 1) throw new ApiException(409, "MENU_CHANGED", "规格选项已失效，请重新选择");
      selections.add(selected.get(0));
    }
    return new MenuSelection(
        d.get("name").toString(), (String) d.get("object_key"), List.copyOf(selections));
  }

  String digest(List<Selection> values) {
    return Tokens.sha256(
        encode(values.stream().map(s -> List.of(s.dimensionId(), s.optionId())).toList()));
  }

  private void remove(UserContext user, long item) {
    repo.jdbc()
        .update(
            "UPDATE meal_order_item SET removed_at=?,removed_by_user_id=?,version=version+1 WHERE id=?",
            Timestamp.from(clock.instant()),
            user.userId(),
            item);
  }

  private Mutation changed(
      UserContext user, OrderRow order, String operation, Map<String, Object> summary) {
    repo.jdbc().update("UPDATE meal_order SET version=version+1 WHERE id=?", order.id());
    audit(user, order.id(), operation, summary);
    if ("IN_PROGRESS".equals(order.status())
        && !"CANCELLED".equals(repo.order(order.id(), true).status())) {
      effects.participant(order.id(), user.userId(), false, false, true, false);
      if (summary.containsKey("itemId")
          && "SUBMITTED".equals(stage(((Number) summary.get("itemId")).longValue())))
        effects.changed(order.id(), "ORDER_CHANGED");
    }
    return new Mutation(order.id(), order.version() + 1);
  }

  private void audit(UserContext user, long id, String operation, Map<String, Object> summary) {
    repo.jdbc()
        .update(
            "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id,request_id,summary_json) VALUES('MEAL_ORDER',?,?,?,?,?)",
            id,
            operation,
            user.userId(),
            MDC.get("requestId"),
            encode(summary));
  }

  private static void quantity(int n) {
    if (n < 1 || n > 99) throw ApiException.invalid("每行份数为1—99");
  }

  static void version(long actual, Long expected) {
    if (expected == null || expected < 0) throw ApiException.invalid("请提供版本号");
    if (actual != expected) throw ApiException.conflict();
  }

  String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw ApiException.invalid("请求格式错误");
    }
  }
}
