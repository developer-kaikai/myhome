package com.myhome.table.core;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.myhome.table.common.persistence.HomeStatisticsCache;
import com.myhome.table.common.security.*;
import com.myhome.table.common.util.Tokens;
import com.myhome.table.core.application.HomeService;
import com.myhome.table.core.infrastructure.MenuRepository;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_INTEGRATION", matches = "true")
class HomeIntegrationTest {
  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) throws Exception {
    Properties p = new Properties();
    try (var in = Files.newInputStream(Path.of(System.getenv("OUR_TABLE_TEST_CONFIG")))) {
      p.load(in);
    }
    if (!p.getProperty("spring.datasource.url", "").contains(":13306/myhome_it?"))
      throw new IllegalStateException("Require isolated database");
    p.forEach((k, v) -> registry.add(k.toString(), () -> v.toString()));
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired MenuRepository repo;
  @Autowired ObjectMapper json;
  @Autowired StringRedisTemplate redis;
  @Autowired HomeService home;
  @Autowired HomeStatisticsCache cache;
  @MockitoBean Clock clock;
  private final List<Long> users = new ArrayList<>(),
      dishes = new ArrayList<>(),
      orders = new ArrayList<>();
  private final List<String> sessionKeys = new ArrayList<>();
  private final Map<String, Integer> mealCounters = new HashMap<>();
  private long chef, a, b, normalA, normalB, seasonalA, seasonalB;
  private String token, visitor, guest;
  private Instant now;

  @BeforeEach
  void prepare() {
    now = Instant.parse("2026-10-04T10:00:00Z");
    when(clock.instant()).thenAnswer(i -> now);
    when(clock.withZone(any())).thenAnswer(i -> Clock.fixed(now, i.getArgument(0)));
    a =
        jdbc.queryForObject(
            "SELECT id FROM restaurant WHERE restaurant_code='RESTAURANT_A'", Long.class);
    b =
        jdbc.queryForObject(
            "SELECT id FROM restaurant WHERE restaurant_code='RESTAURANT_B'", Long.class);
    chef = user();
    jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE id=?", chef, a);
    token = session(chef);
    visitor = session(user());
    jdbc.update(
        "INSERT INTO daily_access_secret(id,secret_hash,secret_ciphertext,secret_nonce,encryption_key_version) VALUES(1,'unused-home-fixture','fixture','000000000000','v1')");
    long visitorWithGrant = user();
    guest = session(visitorWithGrant);
    jdbc.update(
        "INSERT INTO daily_access_grant(user_id,secret_version,grant_generation,issued_at,expires_at) VALUES(?,1,1,?,?)",
        visitorWithGrant,
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(86400)));
    normalA = repo.insert("INSERT INTO menu_category(restaurant_id,name) VALUES(?,'统计测试')", a);
    normalB = repo.insert("INSERT INTO menu_category(restaurant_id,name) VALUES(?,'统计测试')", b);
    seasonalA =
        jdbc.queryForObject(
            "SELECT id FROM menu_category WHERE restaurant_id=? AND category_type='SEASONAL'",
            Long.class,
            a);
    seasonalB =
        jdbc.queryForObject(
            "SELECT id FROM menu_category WHERE restaurant_id=? AND category_type='SEASONAL'",
            Long.class,
            b);
    clearCache();
  }

  private void clearCache() {
    var keys = redis.keys("core:home:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  private long user() {
    long id = repo.insert("INSERT INTO app_user(openid) VALUES(?)", "home_it_" + UUID.randomUUID());
    users.add(id);
    return id;
  }

  private String session(long user) {
    String token = Tokens.random(), key = SessionStore.key(token);
    redis.opsForValue().set(key, Long.toString(user), Duration.ofMinutes(5));
    sessionKeys.add(key);
    return token;
  }

  private long dish(long restaurant, String name, boolean seasonal, int... months) {
    long id =
        repo.insert(
            "INSERT INTO dish(restaurant_id,category_id,name,introduction,recipe) VALUES(?,?,?,'家的味道','不可公开的做法')",
            restaurant,
            seasonal
                ? (restaurant == a ? seasonalA : seasonalB)
                : (restaurant == a ? normalA : normalB),
            name);
    dishes.add(id);
    for (int month : months)
      jdbc.update("INSERT INTO dish_supply_month(dish_id,supply_month) VALUES(?,?)", id, month);
    return id;
  }

  private long order(long restaurant, String date, String status) {
    LocalDate day = LocalDate.parse(date);
    int slot = mealCounters.merge(restaurant + date, 1, Integer::sum);
    long id =
        repo.insert(
            "INSERT INTO meal_order(restaurant_id,meal_date,meal_period,meal_slot,status,cutoff_at,completed_at) VALUES(?,?,'DINNER',?,?,?,?)",
            restaurant,
            day,
            day.toEpochDay() * 10 + slot,
            status,
            Timestamp.from(now),
            status.equals("COMPLETED") ? Timestamp.from(now) : null);
    orders.add(id);
    return id;
  }

  private void actual(long order, long dish, int quantity, String spec) {
    jdbc.update(
        "INSERT INTO meal_actual_item(meal_order_id,dish_id,source_type,confirmed_by_user_id,dish_name_snapshot,spec_key,spec_snapshot,quantity) SELECT ?,id,'CONFIRM_ADDED',?,name,?,'[]',? FROM dish WHERE id=?",
        order,
        chef,
        Tokens.sha256(spec),
        quantity,
        dish);
  }

  private void rating(long order, long dish, int stars) {
    jdbc.update(
        "INSERT INTO dish_review(meal_order_id,dish_id,reviewer_user_id,reviewer_name_snapshot,rating,first_submitted_at) VALUES(?,?,?,'本人',?,?)",
        order,
        dish,
        chef,
        stars,
        Timestamp.from(now));
  }

  private JsonNode summary(String period, String scope, String session) throws Exception {
    var result =
        mvc.perform(
                get("/api/v1/home/summary")
                    .param("period", period)
                    .param("scope", scope)
                    .header("Authorization", "Bearer " + session))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("data");
  }

  private JsonNode summary() throws Exception {
    return summary("week", "family", token);
  }

  @AfterEach
  void cleanup() {
    redis.delete(sessionKeys);
    clearCache();
    for (long order : orders) {
      jdbc.update("DELETE FROM dish_review WHERE meal_order_id=?", order);
      jdbc.update("DELETE FROM meal_actual_item WHERE meal_order_id=?", order);
      jdbc.update("DELETE FROM meal_order WHERE id=?", order);
    }
    for (long dish : dishes) {
      jdbc.update("DELETE FROM daily_recommendation WHERE dish_id=?", dish);
      jdbc.update("DELETE FROM dish_supply_month WHERE dish_id=?", dish);
      jdbc.update("DELETE FROM dish WHERE id=?", dish);
    }
    jdbc.update("DELETE FROM menu_category WHERE id IN (?,?)", normalA, normalB);
    jdbc.update("UPDATE restaurant SET chef_user_id=NULL WHERE id=?", a);
    for (long user : users) {
      jdbc.update("DELETE FROM daily_access_grant WHERE user_id=?", user);
      jdbc.update("DELETE FROM app_user WHERE id=?", user);
    }
    jdbc.update("DELETE FROM daily_access_secret WHERE id=1");
  }

  @Test
  void permissionsAndStrictParameters() throws Exception {
    mvc.perform(get("/api/v1/home/summary")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/home/summary").header("Authorization", "Bearer " + visitor))
        .andExpect(status().isForbidden());
    assertThat(summary("week", "family", guest).get("cookCount").asInt()).isZero();
    for (String scope : List.of("restaurant:999999", "restaurant:0", "1", "restaurant:1 OR 1=1"))
      mvc.perform(
              get("/api/v1/home/summary")
                  .param("scope", scope)
                  .header("Authorization", "Bearer " + token))
          .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/home/summary")
                .param("period", "all")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_recommendation", Long.class))
        .isZero();
  }

  @Test
  void emptyTrendIncludesLeapDayAndFutureWithoutFakeNumbers() throws Exception {
    now = Instant.parse("2028-02-10T18:30:00Z");
    var s = summary("month", "family", token);
    assertThat(s.get("today").asText()).isEqualTo("2028-02-11");
    assertThat(s.get("greeting").asText()).isEqualTo("夜深了");
    assertThat(s.get("cookCount").asInt()).isZero();
    assertThat(s.get("popular").isEmpty()).isTrue();
    assertThat(s.get("recommendation").isNull()).isTrue();
    assertThat(s.get("trend").size()).isEqualTo(29);
    assertThat(s.get("trend").get(10).get("count").asInt()).isZero();
    assertThat(s.get("trend").get(11).get("count").isNull()).isTrue();
  }

  @Test
  void actualFactsCountEachRestaurantAndDishOnce() throws Exception {
    long x = dish(a, "牛腩", false),
        y = dish(b, "牛腩", false),
        added = dish(a, "补做菜", false),
        removed = dish(a, "没做的菜", false);
    long one = order(a, "2026-10-02", "COMPLETED"),
        two = order(b, "2026-10-02", "COMPLETED"),
        three = order(a, "2026-10-03", "COMPLETED");
    actual(one, x, 3, "微辣");
    actual(one, x, 2, "重辣");
    actual(one, added, 1, "无规格");
    actual(two, y, 1, "无规格");
    actual(three, x, 1, "无规格");
    actual(order(a, "2026-10-01", "CANCELLED"), removed, 1, "无规格");
    order(b, "2026-09-30", "IN_PROGRESS");
    order(a, "2026-09-29", "COMPLETED");
    actual(order(b, "2026-10-05", "COMPLETED"), y, 1, "无规格");
    var s = summary();
    assertThat(s.get("cookCount").asInt()).isEqualTo(3);
    assertThat(s.get("rangeStart").asText()).isEqualTo("2026-09-28");
    assertThat(s.get("popular").size()).isEqualTo(3);
    assertThat(s.get("popular").get(0).get("dishId").asLong()).isEqualTo(x);
    assertThat(s.get("popular").get(0).get("orderCount").asInt()).isEqualTo(2);
    assertThat(summary("week", "restaurant:" + a, token).get("cookCount").asInt()).isEqualTo(2);
    assertThat(s.toString()).doesNotContain("不可公开的做法", "没做的菜", "reviewer", "remark", "openid");
  }

  @Test
  void lateConfirmationUsesMealDateAndYearBuckets() throws Exception {
    long x = dish(a, "晚确认", false);
    actual(order(a, "2026-09-27", "COMPLETED"), x, 1, "无规格");
    actual(order(a, "2026-01-01", "COMPLETED"), x, 1, "无规格");
    assertThat(summary().get("cookCount").asInt()).isZero();
    assertThat(summary("month", "family", token).get("cookCount").asInt()).isZero();
    var s = summary("year", "family", token);
    assertThat(s.get("cookCount").asInt()).isEqualTo(2);
    assertThat(s.get("trend").get(0).get("count").asInt()).isEqualTo(1);
    assertThat(s.get("trend").get(8).get("count").asInt()).isEqualTo(1);
    assertThat(s.get("trend").get(10).get("count").isNull()).isTrue();
  }

  @Test
  void popularityTieUsesLatestMealThenStableId() throws Exception {
    long x = dish(a, "早菜", false), y = dish(a, "晚菜1", false), z = dish(a, "晚菜2", false);
    actual(order(a, "2026-10-01", "COMPLETED"), x, 99, "无规格");
    long later = order(a, "2026-10-02", "COMPLETED");
    actual(later, y, 1, "无规格");
    actual(later, z, 1, "无规格");
    var p = summary().get("popular");
    assertThat(p.get(0).get("dishId").asLong()).isEqualTo(y);
    assertThat(p.get(1).get("dishId").asLong()).isEqualTo(z);
    assertThat(p.get(2).get("dishId").asLong()).isEqualTo(x);
  }

  @Test
  void averageUsesAllReviewsAndCacheGenerationChanges() throws Exception {
    long x = dish(a, "评分菜", false),
        old = order(a, "2026-09-01", "COMPLETED"),
        recent = order(a, "2026-10-01", "COMPLETED");
    actual(old, x, 1, "无规格");
    actual(recent, x, 5, "无规格");
    rating(old, x, 2);
    rating(recent, x, 5);
    assertThat(summary().get("popular").get(0).get("average").asDouble()).isEqualTo(3.5);
    jdbc.update("UPDATE dish_review SET rating=4,version=version+1 WHERE meal_order_id=?", recent);
    cache.invalidateAfterCommit();
    var p = summary().get("popular").get(0);
    assertThat(p.get("average").asDouble()).isEqualTo(3);
    assertThat(p.get("reviewCount").asInt()).isEqualTo(2);
    assertThat(p.get("orderCount").asInt()).isEqualTo(1);
    var menu =
        json.readTree(
                mvc.perform(
                        get("/api/v1/restaurants/" + a + "/menu")
                            .param("date", "2026-10-04")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("data");
    assertThat(menu.get("dishes").get(0).get("average").asDouble()).isEqualTo(3);
    assertThat(menu.get("dishes").get(0).get("hot").asBoolean()).isTrue();
  }

  @Test
  void recommendationSurvivesRedisAndInvalidatesImmediately() throws Exception {
    long x = dish(a, "当季甲", true, 10), y = dish(a, "当季乙", true, 10);
    dish(a, "非当季", true, 1);
    dish(a, "常规", false);
    jdbc.update(
        "INSERT INTO daily_recommendation(recommendation_date,scope_key,restaurant_id,dish_id) VALUES('2026-10-03','family',?,?)",
        a,
        x);
    var s = summary();
    assertThat(s.get("recommendation").get("dishId").asLong()).isEqualTo(y);
    clearCache();
    assertThat(summary().get("recommendation").get("dishId").asLong()).isEqualTo(y);
    jdbc.update("UPDATE dish SET is_on_shelf=0,version=version+1 WHERE id=?", y);
    assertThat(summary().get("recommendation").get("dishId").asLong()).isEqualTo(x);
    jdbc.update("DELETE FROM dish_supply_month WHERE dish_id=?", x);
    assertThat(summary().get("recommendation").isNull()).isTrue();
    jdbc.update("INSERT INTO dish_supply_month(dish_id,supply_month) VALUES(?,10)", x);
    assertThat(summary().get("recommendation").get("dishId").asLong()).isEqualTo(x);
    assertThat(summary("week", "restaurant:" + b, token).get("recommendation").isNull()).isTrue();
  }

  @Test
  void concurrentDailyRecommendationHasOneStableChoice() throws Exception {
    dish(a, "随机甲", true, 10);
    dish(b, "随机乙", true, 10);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(4);
    try {
      var jobs = new ArrayList<Future<Long>>();
      for (int i = 0; i < 4; i++)
        jobs.add(
            pool.submit(
                () -> {
                  start.await();
                  return home.summary(new UserContext(chef, a, null), "week", "family")
                      .recommendation()
                      .dishId();
                }));
      start.countDown();
      var choices = new HashSet<Long>();
      for (var job : jobs) choices.add(job.get(10, TimeUnit.SECONDS));
      assertThat(choices).hasSize(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM daily_recommendation WHERE recommendation_date='2026-10-04' AND scope_key='family'",
                  Long.class))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void hotSelectsOnlyCurrentAvailablePositiveTopFive() throws Exception {
    long gone = dish(a, "最热门下架", false), never = dish(a, "从没做过", false);
    jdbc.update("UPDATE dish SET is_on_shelf=0 WHERE id=?", gone);
    var ids = new ArrayList<Long>();
    for (int i = 0; i < 6; i++) ids.add(dish(a, "热门" + i, false));
    for (int i = 0; i < 6; i++) {
      long order = order(a, "2026-09-0" + (i + 1), "COMPLETED");
      actual(order, gone, 99, "无规格");
      for (int j = 0; j < 6 - i; j++) actual(order, ids.get(j), 1, "无规格");
    }
    var hot = repo.hot(a, LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-04"));
    assertThat(hot)
        .hasSize(5)
        .doesNotContain(gone, never, ids.get(5))
        .contains(ids.get(0), ids.get(4));
  }
}
