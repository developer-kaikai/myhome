package com.myhome.table.core.infrastructure;

import com.myhome.table.common.exception.ApiException;
import com.myhome.table.core.domain.MenuModels.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 菜单持久化；写事务统一先锁餐厅，再锁菜品，避免容量校验竞争。 */
@Repository
public class MenuRepository {
  private final JdbcTemplate jdbc;

  public MenuRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  public record DishRow(
      long id,
      long restaurantId,
      long categoryId,
      String name,
      String introduction,
      String recipe,
      boolean onShelf,
      long version) {}

  private static final String DISH_COLUMNS =
      "id,restaurant_id,category_id,name,introduction,recipe,is_on_shelf,version";

  public List<RestaurantView> restaurants() {
    return jdbc.query(
        "SELECT id,name,version FROM restaurant WHERE status='ACTIVE' ORDER BY id",
        (r, n) -> new RestaurantView(r.getLong(1), r.getString(2), r.getLong(3)));
  }

  public RestaurantView restaurant(long id, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT id,name,version FROM restaurant WHERE id=? AND status='ACTIVE'"
                + (lock ? " FOR UPDATE" : ""),
            (r, n) -> new RestaurantView(r.getLong(1), r.getString(2), r.getLong(3)),
            id);
    return first(rows);
  }

  public List<Category> categories(long restaurant) {
    return jdbc.query(
        "SELECT id,restaurant_id,category_type,name,version FROM menu_category WHERE restaurant_id=? AND deleted_at IS NULL ORDER BY CASE WHEN category_type='SEASONAL' THEN 0 ELSE 1 END,id",
        (r, n) ->
            new Category(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getLong(5)),
        restaurant);
  }

  public Category category(long id) {
    return first(
        jdbc.query(
            "SELECT id,restaurant_id,category_type,name,version FROM menu_category WHERE id=? AND deleted_at IS NULL",
            (r, n) ->
                new Category(
                    r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getLong(5)),
            id));
  }

  private DishRow row(ResultSet r, int index) throws SQLException {
    return new DishRow(
        r.getLong(1),
        r.getLong(2),
        r.getLong(3),
        r.getString(4),
        r.getString(5),
        r.getString(6),
        r.getBoolean(7),
        r.getLong(8));
  }

  public List<DishRow> dishes(long restaurant) {
    return jdbc.query(
        "SELECT "
            + DISH_COLUMNS
            + " FROM dish WHERE restaurant_id=? AND deleted_at IS NULL ORDER BY id",
        this::row,
        restaurant);
  }

  public DishRow dish(long id, boolean lock) {
    return first(
        jdbc.query(
            "SELECT "
                + DISH_COLUMNS
                + " FROM dish WHERE id=? AND deleted_at IS NULL"
                + (lock ? " FOR UPDATE" : ""),
            this::row,
            id));
  }

  public List<Integer> months(long dish) {
    return jdbc.queryForList(
        "SELECT supply_month FROM dish_supply_month WHERE dish_id=? ORDER BY supply_month",
        Integer.class,
        dish);
  }

  public List<Dimension> dimensions(long dish) {
    return jdbc.query(
        "SELECT id,name FROM dish_spec_dimension WHERE dish_id=? AND deleted_at IS NULL ORDER BY sort_order,id",
        (r, n) -> new Dimension(r.getLong(1), r.getString(2), options(r.getLong(1))),
        dish);
  }

  public List<DishView> views(long restaurant) {
    var scores = scores(restaurant);
    var months = new HashMap<Long, List<Integer>>();
    jdbc.query(
        "SELECT m.dish_id,m.supply_month FROM dish_supply_month m JOIN dish d ON d.id=m.dish_id WHERE d.restaurant_id=? AND d.deleted_at IS NULL ORDER BY m.supply_month",
        (org.springframework.jdbc.core.RowCallbackHandler)
            r -> months.computeIfAbsent(r.getLong(1), k -> new ArrayList<>()).add(r.getInt(2)),
        restaurant);
    var options = new HashMap<Long, List<Option>>();
    jdbc.query(
        "SELECT o.dimension_id,o.id,o.name,o.is_default FROM dish_spec_option o JOIN dish_spec_dimension s ON s.id=o.dimension_id JOIN dish d ON d.id=s.dish_id WHERE d.restaurant_id=? AND d.deleted_at IS NULL AND s.deleted_at IS NULL AND o.deleted_at IS NULL ORDER BY o.sort_order,o.id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            r ->
                options
                    .computeIfAbsent(r.getLong(1), k -> new ArrayList<>())
                    .add(new Option(r.getLong(2), r.getString(3), r.getBoolean(4))),
        restaurant);
    var dimensions = new HashMap<Long, List<Dimension>>();
    jdbc.query(
        "SELECT s.dish_id,s.id,s.name FROM dish_spec_dimension s JOIN dish d ON d.id=s.dish_id WHERE d.restaurant_id=? AND d.deleted_at IS NULL AND s.deleted_at IS NULL ORDER BY s.sort_order,s.id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            r ->
                dimensions
                    .computeIfAbsent(r.getLong(1), k -> new ArrayList<>())
                    .add(
                        new Dimension(
                            r.getLong(2),
                            r.getString(3),
                            options.getOrDefault(r.getLong(2), List.of()))),
        restaurant);
    return dishes(restaurant).stream()
        .map(
            d ->
                new DishView(
                    d.id(),
                    d.categoryId(),
                    d.name(),
                    d.introduction(),
                    d.onShelf(),
                    d.version(),
                    months.getOrDefault(d.id(), List.of()),
                    dimensions.getOrDefault(d.id(), List.of()),
                    scores.getOrDefault(d.id(), new Score(null, 0)).average(),
                    scores.getOrDefault(d.id(), new Score(null, 0)).count(),
                    false))
        .toList();
  }

  public record Score(Double average, long count) {}

  public Map<Long, Score> scores(long restaurant) {
    var result = new HashMap<Long, Score>();
    jdbc.query(
        "SELECT r.dish_id,AVG(r.rating),COUNT(*) FROM dish_review r JOIN meal_order o ON o.id=r.meal_order_id JOIN dish d ON d.id=r.dish_id WHERE d.restaurant_id=? AND o.status='COMPLETED' GROUP BY r.dish_id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            r -> result.put(r.getLong(1), new Score(r.getDouble(2), r.getLong(3))),
        restaurant);
    return result;
  }

  public Set<Long> hot(long restaurant, java.time.LocalDate date, java.time.LocalDate today) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT a.dish_id FROM meal_actual_item a JOIN meal_order o ON o.id=a.meal_order_id JOIN dish d ON d.id=a.dish_id JOIN menu_category c ON c.id=d.category_id WHERE o.status='COMPLETED' AND o.meal_date<=? AND o.restaurant_id=? AND d.deleted_at IS NULL AND d.is_on_shelf=1 AND c.deleted_at IS NULL AND (c.category_type='NORMAL' OR EXISTS(SELECT 1 FROM dish_supply_month m WHERE m.dish_id=d.id AND m.supply_month=?)) GROUP BY a.dish_id ORDER BY COUNT(DISTINCT o.id) DESC,MAX(o.meal_date) DESC,a.dish_id LIMIT 5",
            Long.class,
            today,
            restaurant,
            date.getMonthValue()));
  }

  private List<Option> options(long dimension) {
    return jdbc.query(
        "SELECT id,name,is_default FROM dish_spec_option WHERE dimension_id=? AND deleted_at IS NULL ORDER BY sort_order,id",
        (r, n) -> new Option(r.getLong(1), r.getString(2), r.getBoolean(3)),
        dimension);
  }

  public long insert(String sql, Object... args) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
          return statement;
        },
        key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }

  private static <T> T first(List<T> list) {
    if (list.isEmpty()) throw new ApiException(404, "NOT_FOUND", "内容不存在或已删除");
    return list.get(0);
  }
}
