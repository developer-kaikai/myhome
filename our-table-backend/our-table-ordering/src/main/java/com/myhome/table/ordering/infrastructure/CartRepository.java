package com.myhome.table.ordering.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.util.BusinessTime;
import com.myhome.table.ordering.domain.CartModels.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** ordering 只写餐单与公共审计表；菜单表仅做受控只读查询。 */
@Repository
public class CartRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public CartRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  public record OrderRow(
      long id,
      long restaurantId,
      LocalDate date,
      BusinessTime.Meal meal,
      Instant cutoff,
      String status,
      long version) {}

  private static final String COLUMNS =
      "id,restaurant_id,meal_date,meal_period,cutoff_at,status,version";

  private OrderRow row(ResultSet r, int n) throws SQLException {
    return new OrderRow(
        r.getLong(1),
        r.getLong(2),
        r.getDate(3).toLocalDate(),
        BusinessTime.Meal.valueOf(r.getString(4)),
        r.getTimestamp(5).toInstant(),
        r.getString(6),
        r.getLong(7));
  }

  public void restaurant(long id, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT id FROM restaurant WHERE id=? AND status='ACTIVE'"
                + (lock ? " FOR UPDATE" : ""),
            Long.class,
            id);
    if (rows.isEmpty()) throw missing();
  }

  public OrderRow slot(long restaurant, LocalDate date, BusinessTime.Meal meal) {
    var rows =
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM meal_order WHERE restaurant_id=? AND meal_slot=? AND status<>'CANCELLED'",
            this::row,
            restaurant,
            slotKey(date, meal));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public OrderRow order(long id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT " + COLUMNS + " FROM meal_order WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            this::row,
            id);
    if (rows.isEmpty()) throw missing();
    return rows.get(0);
  }

  public List<Item> items(long order) {
    return items(order, "PENDING", false);
  }

  public List<Item> items(long order, boolean lock) {
    return items(order, "PENDING", lock);
  }

  public List<Item> items(long order, String stage, boolean lock) {
    return jdbc.query(
        "SELECT id,dish_id,dish_name_snapshot,image_object_key_snapshot,contributor_user_id,contributor_name_snapshot,spec_snapshot,quantity,version FROM meal_order_item WHERE meal_order_id=? AND removed_at IS NULL AND item_stage=? ORDER BY id"
            + (lock ? " FOR UPDATE" : ""),
        (r, n) ->
            new Item(
                r.getLong(1),
                r.getLong(2),
                r.getString(3),
                r.getString(4),
                r.getLong(5),
                r.getString(6),
                selections(r.getString(7)),
                r.getInt(8),
                r.getLong(9)),
        order,
        stage);
  }

  public List<com.myhome.table.ordering.domain.OrderModels.ActualItem> actualItems(long order) {
    return jdbc.query(
        "SELECT id,dish_id,dish_name_snapshot,image_object_key_snapshot,spec_snapshot,quantity,source_type FROM meal_actual_item WHERE meal_order_id=? ORDER BY id",
        (r, n) ->
            new com.myhome.table.ordering.domain.OrderModels.ActualItem(
                r.getLong(1),
                r.getLong(2),
                r.getString(3),
                r.getString(4),
                selections(r.getString(5)),
                r.getInt(6),
                r.getString(7)),
        order);
  }

  private List<Selection> selections(String value) {
    try {
      return json.readValue(value, new TypeReference<List<Selection>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored specification");
    }
  }

  public long insert(String sql, Object... args) {
    var keys = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
          return s;
        },
        keys);
    return Objects.requireNonNull(keys.getKey()).longValue();
  }

  public static long slotKey(LocalDate date, BusinessTime.Meal meal) {
    return (date.getYear() * 10000L + date.getMonthValue() * 100 + date.getDayOfMonth()) * 10
        + meal.ordinal()
        + 1;
  }

  public static ApiException missing() {
    return new ApiException(404, "NOT_FOUND", "清单或菜品不存在，请刷新");
  }
}
