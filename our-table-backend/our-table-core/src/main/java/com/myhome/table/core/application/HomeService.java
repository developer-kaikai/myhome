package com.myhome.table.core.application;

import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.HomeStatisticsCache;
import com.myhome.table.common.security.UserContext;
import com.myhome.table.common.util.BusinessTime;
import com.myhome.table.core.domain.HomeModels.*;
import com.myhome.table.core.infrastructure.MenuRepository;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HomeService {
  private final MenuRepository menu;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final HomeStatisticsCache cache;

  public HomeService(
      MenuRepository menu, JdbcTemplate jdbc, Clock clock, HomeStatisticsCache cache) {
    this.menu = menu;
    this.jdbc = jdbc;
    this.clock = clock;
    this.cache = cache;
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Summary summary(UserContext user, String period, String scope) {
    user.requireDaily(clock.instant());
    var restaurants = menu.restaurants();
    Long restaurant = null;
    if (!"family".equals(scope)) {
      if (!scope.matches("restaurant:[1-9][0-9]{0,17}")) throw ApiException.invalid("统计范围无效");
      restaurant = Long.parseLong(scope.substring(11));
      long id = restaurant;
      if (restaurants.stream().noneMatch(r -> r.id() == id)) throw ApiException.invalid("餐厅不存在");
    }
    LocalDate today = LocalDate.now(clock.withZone(BusinessTime.ZONE));
    LocalDate start =
        switch (period) {
          case "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          case "month" -> today.withDayOfMonth(1);
          case "year" -> today.withDayOfYear(1);
          default -> throw ApiException.invalid("请选择周、月或年");
        };
    LocalDate end =
        switch (period) {
          case "week" -> start.plusDays(6);
          case "month" -> start.with(TemporalAdjusters.lastDayOfMonth());
          default -> start.with(TemporalAdjusters.lastDayOfYear());
        };
    // 与菜单维护保持同一餐厅锁顺序，候选校验和推荐写入原子完成；不锁餐单。
    for (var r : restaurants)
      if (restaurant == null || restaurant == r.id()) menu.restaurant(r.id(), true);
    Long selected = restaurant;
    Statistics stats =
        cache.read(
            scope + ":" + period + ":" + today,
            Statistics.class,
            () -> statistics(selected, period, start, end, today));
    int hour = clock.instant().atZone(BusinessTime.ZONE).getHour();
    String greeting =
        hour >= 5 && hour < 11
            ? "早上好"
            : hour >= 11 && hour < 14
                ? "中午好"
                : hour >= 14 && hour < 18 ? "下午好" : hour >= 18 && hour < 23 ? "晚上好" : "夜深了";
    return new Summary(
        today,
        greeting,
        period,
        scope,
        start,
        end,
        restaurants,
        stats.cookCount(),
        stats.trend(),
        stats.popular(),
        recommend(scope, selected, today));
  }

  private Statistics statistics(
      Long restaurant, String period, LocalDate start, LocalDate end, LocalDate today) {
    String where =
        "o.status='COMPLETED' AND o.meal_date BETWEEN ? AND ? AND EXISTS(SELECT 1 FROM meal_actual_item ai WHERE ai.meal_order_id=o.id)";
    var args = new ArrayList<Object>(List.of(start, today.isBefore(end) ? today : end));
    if (restaurant != null) {
      where += " AND o.restaurant_id=?";
      args.add(restaurant);
    }
    var daily = new HashMap<LocalDate, Long>();
    jdbc.query(
        "SELECT o.meal_date,COUNT(*) n FROM meal_order o WHERE " + where + " GROUP BY o.meal_date",
        (org.springframework.jdbc.core.RowCallbackHandler)
            r -> daily.put(r.getDate(1).toLocalDate(), r.getLong(2)),
        args.toArray());
    var points = new ArrayList<Point>();
    if ("year".equals(period)) {
      for (int month = 1; month <= 12; month++) {
        LocalDate date = start.withMonth(month);
        long count =
            daily.entrySet().stream()
                .filter(e -> e.getKey().getMonthValue() == date.getMonthValue())
                .mapToLong(Map.Entry::getValue)
                .sum();
        points.add(
            new Point(month + "月", date, date.isAfter(today) ? null : count, date.isAfter(today)));
      }
    } else
      for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
        String label =
            "week".equals(period)
                ? List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                    .get(date.getDayOfWeek().getValue() - 1)
                : Integer.toString(date.getDayOfMonth());
        points.add(
            new Point(
                label,
                date,
                date.isAfter(today) ? null : daily.getOrDefault(date, 0L),
                date.isAfter(today)));
      }
    var popular =
        jdbc.query(
            "SELECT ai.dish_id,o.restaurant_id,COUNT(DISTINCT o.id) n,MAX(o.meal_date) last_date "
                + "FROM meal_order o JOIN meal_actual_item ai ON ai.meal_order_id=o.id WHERE "
                + where
                + " GROUP BY ai.dish_id,o.restaurant_id ORDER BY n DESC,last_date DESC,ai.dish_id ASC LIMIT 5",
            (r, n) -> {
              long dish = r.getLong(1), owner = r.getLong(2);
              String name =
                  jdbc.queryForObject(
                      "SELECT dish_name_snapshot FROM meal_actual_item a JOIN meal_order m ON m.id=a.meal_order_id WHERE a.dish_id=? AND m.status='COMPLETED' AND m.meal_date<=? ORDER BY m.meal_date DESC,m.id DESC,a.id LIMIT 1",
                      String.class,
                      dish,
                      today);
              String restaurantName =
                  jdbc.queryForObject(
                      "SELECT name FROM restaurant WHERE id=?", String.class, owner);
              var ratings = ratings(dish);
              return new Popular(
                  dish,
                  owner,
                  name,
                  restaurantName,
                  r.getLong(3),
                  r.getDate(4).toLocalDate(),
                  average(ratings),
                  ((Number) ratings.get("n")).longValue());
            },
            args.toArray());
    return new Statistics(
        daily.values().stream().mapToLong(Long::longValue).sum(), points, popular);
  }

  private Map<String, Object> ratings(long dish) {
    return jdbc.queryForMap(
        "SELECT AVG(r.rating) average,COUNT(*) n FROM dish_review r JOIN meal_order o ON o.id=r.meal_order_id WHERE r.dish_id=? AND o.status='COMPLETED'",
        dish);
  }

  private Double average(Map<String, Object> row) {
    return row.get("average") == null ? null : ((Number) row.get("average")).doubleValue();
  }

  private record Candidate(
      long id, long restaurant, String name, String restaurantName, String introduction) {}

  private Recommendation recommend(String scope, Long restaurant, LocalDate today) {
    String filter = restaurant == null ? "" : " AND d.restaurant_id=?";
    var args = new ArrayList<Object>(List.of(today.getMonthValue()));
    if (restaurant != null) args.add(restaurant);
    var candidates =
        jdbc.query(
            "SELECT d.id,d.restaurant_id,d.name,r.name,d.introduction FROM dish d JOIN restaurant r ON r.id=d.restaurant_id JOIN menu_category c ON c.id=d.category_id JOIN dish_supply_month m ON m.dish_id=d.id WHERE d.deleted_at IS NULL AND d.is_on_shelf=1 AND c.deleted_at IS NULL AND c.category_type='SEASONAL' AND m.supply_month=?"
                + filter
                + " ORDER BY d.id",
            (r, n) ->
                new Candidate(
                    r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5)),
            args.toArray());
    var current =
        jdbc.queryForList(
            "SELECT dish_id,invalidated_at FROM daily_recommendation WHERE recommendation_date=? AND scope_key=? FOR UPDATE",
            today,
            scope);
    Candidate selected = null;
    if (!current.isEmpty() && current.get(0).get("invalidated_at") == null) {
      long id = ((Number) current.get(0).get("dish_id")).longValue();
      selected = candidates.stream().filter(c -> c.id() == id).findFirst().orElse(null);
    }
    if (selected == null) {
      if (candidates.isEmpty()) {
        jdbc.update(
            "UPDATE daily_recommendation SET invalidated_at=UTC_TIMESTAMP(3) WHERE recommendation_date=? AND scope_key=? AND invalidated_at IS NULL",
            today,
            scope);
        return null;
      }
      var yesterday =
          jdbc.queryForList(
              "SELECT dish_id FROM daily_recommendation WHERE recommendation_date=? AND scope_key=?",
              Long.class,
              today.minusDays(1),
              scope);
      var pool =
          candidates.size() > 1 && !yesterday.isEmpty()
              ? candidates.stream().filter(c -> c.id() != yesterday.get(0)).toList()
              : candidates;
      selected = pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
      jdbc.update(
          "INSERT INTO daily_recommendation(recommendation_date,scope_key,restaurant_id,dish_id) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE restaurant_id=VALUES(restaurant_id),dish_id=VALUES(dish_id),invalidated_at=NULL",
          today,
          scope,
          selected.restaurant(),
          selected.id());
    }
    var score = ratings(selected.id());
    return new Recommendation(
        selected.id(),
        selected.restaurant(),
        selected.name(),
        selected.restaurantName(),
        selected.introduction(),
        average(score),
        ((Number) score.get("n")).longValue());
  }
}
