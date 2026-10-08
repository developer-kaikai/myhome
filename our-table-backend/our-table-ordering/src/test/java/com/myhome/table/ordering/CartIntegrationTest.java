package com.myhome.table.ordering;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.myhome.table.common.security.SessionStore;
import com.myhome.table.common.util.Tokens;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CartIntegrationTest.TimeConfig.class)
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_INTEGRATION", matches = "true")
class CartIntegrationTest {
  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) throws Exception {
    Properties p = new Properties();
    try (var in = Files.newInputStream(Path.of(System.getenv("OUR_TABLE_TEST_CONFIG")))) {
      p.load(in);
    }
    if (!p.getProperty("spring.datasource.url", "").contains(":13306/myhome_it?"))
      throw new IllegalStateException("Require isolated database");
    p.forEach((k, v) -> registry.add(k.toString(), () -> v.toString()));
    registry.add("our-table.ordering.reminders-enabled", () -> false);
  }

  static class TestClock extends Clock {
    volatile Instant now;

    void at(String local) {
      now = LocalDateTime.parse(local).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    public Instant instant() {
      return now;
    }
  }

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper json;
  @Autowired TransactionTemplate tx;
  @Autowired TestClock clock;
  final List<String> keys = new ArrayList<>();
  final List<Long> users = new ArrayList<>();
  long a, b, chefId, friendId, dish, spec, plain, foreignDish, seasonal;
  long mild, hot;
  String chef, friend, visitor;
  String date = "2026-09-30";

  @BeforeEach
  void setup() {
    clock.at("2026-09-30T16:00:00");
    a =
        jdbc.queryForObject(
            "SELECT id FROM restaurant WHERE restaurant_code='RESTAURANT_A'", Long.class);
    b =
        jdbc.queryForObject(
            "SELECT id FROM restaurant WHERE restaurant_code='RESTAURANT_B'", Long.class);
    chefId = user("主厨");
    friendId = user("朋友👨‍👩‍👧‍👦".repeat(4));
    chef = session(chefId);
    friend = session(friendId);
    visitor = session(user("访客"));
    jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE id=?", chefId, a);
    jdbc.update(
        "INSERT INTO daily_access_secret(id,secret_hash,secret_ciphertext,secret_nonce,encryption_key_version) VALUES(1,'test',?,?,'test')",
        new byte[16],
        new byte[12]);
    jdbc.update(
        "INSERT INTO daily_access_grant(user_id,secret_version,grant_generation,issued_at,expires_at) VALUES(?,1,1,?,?)",
        friendId,
        java.sql.Timestamp.from(clock.instant()),
        java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(2))));
    long cat = insert("INSERT INTO menu_category(restaurant_id,name) VALUES(?,'家常')", a);
    dish =
        insert(
            "INSERT INTO dish(restaurant_id,category_id,name,recipe) VALUES(?,?,'牛腩','保密做法')",
            a,
            cat);
    plain = insert("INSERT INTO dish(restaurant_id,category_id,name) VALUES(?,?,'青菜')", a, cat);
    long foreignCat =
        jdbc.queryForObject(
            "SELECT id FROM menu_category WHERE restaurant_id=? AND category_type='SEASONAL'",
            Long.class,
            b);
    foreignDish =
        insert(
            "INSERT INTO dish(restaurant_id,category_id,name) VALUES(?,?,'其他餐厅菜')", b, foreignCat);
    long seasonalCat =
        jdbc.queryForObject(
            "SELECT id FROM menu_category WHERE restaurant_id=? AND category_type='SEASONAL'",
            Long.class,
            a);
    seasonal =
        insert(
            "INSERT INTO dish(restaurant_id,category_id,name) VALUES(?,?,'九月限定')", a, seasonalCat);
    jdbc.update("INSERT INTO dish_supply_month(dish_id,supply_month) VALUES(?,9)", seasonal);
    spec = insert("INSERT INTO dish_spec_dimension(dish_id,name) VALUES(?,'辣度')", dish);
    mild =
        insert("INSERT INTO dish_spec_option(dimension_id,name,is_default) VALUES(?,'微辣',1)", spec);
    hot = insert("INSERT INTO dish_spec_option(dimension_id,name) VALUES(?,'重辣')", spec);
  }

  long insert(String sql, Object... args) {
    return tx.execute(
        status -> {
          jdbc.update(sql, args);
          return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        });
  }

  long user(String name) {
    long id =
        insert(
            "INSERT INTO app_user(openid,nickname) VALUES(?,?)",
            "cart_it_" + UUID.randomUUID(),
            name);
    users.add(id);
    return id;
  }

  String session(long id) {
    String token = Tokens.random(), k = SessionStore.key(token);
    redis.opsForValue().set(k, Long.toString(id), Duration.ofMinutes(15));
    keys.add(k);
    return token;
  }

  @AfterEach
  void cleanup() {
    redis.delete(keys);
    tx.executeWithoutResult(
        status -> {
          jdbc.update("DELETE FROM notification_outbox");
          jdbc.update("DELETE FROM review_access_grant");
          jdbc.update("DELETE FROM dish_review");
          jdbc.update("DELETE FROM review_share");
          jdbc.update("DELETE FROM meal_order");
          jdbc.update(
              "DELETE o FROM dish_spec_option o JOIN dish_spec_dimension s ON s.id=o.dimension_id JOIN dish d ON d.id=s.dish_id WHERE d.restaurant_id IN (?,?)",
              a,
              b);
          jdbc.update(
              "DELETE s FROM dish_spec_dimension s JOIN dish d ON d.id=s.dish_id WHERE d.restaurant_id IN (?,?)",
              a,
              b);
          jdbc.update(
              "DELETE FROM dish_supply_month WHERE dish_id IN (SELECT id FROM dish WHERE restaurant_id IN (?,?))",
              a,
              b);
          jdbc.update("DELETE FROM dish WHERE restaurant_id IN (?,?)", a, b);
          jdbc.update(
              "DELETE FROM menu_category WHERE restaurant_id IN (?,?) AND category_type='NORMAL'",
              a,
              b);
          jdbc.update("UPDATE restaurant SET chef_user_id=NULL WHERE id IN (?,?)", a, b);
          jdbc.update("DELETE FROM daily_access_secret WHERE id=1");
          for (long u : users) {
            jdbc.update("DELETE FROM api_idempotency_record WHERE user_id=?", u);
            jdbc.update("DELETE FROM daily_access_grant WHERE user_id=?", u);
            jdbc.update("DELETE FROM app_user WHERE id=?", u);
          }
        });
  }

  ResultActions send(MockHttpServletRequestBuilder req, String token, Object body)
      throws Exception {
    if (token != null) req.header("Authorization", "Bearer " + token);
    if (body != null) req.contentType("application/json").content(json.writeValueAsString(body));
    return mvc.perform(req);
  }

  JsonNode data(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
  }

  String slot(long restaurant, String d, String meal) {
    return "/api/v1/meal-slots/" + restaurant + "/" + d + "/" + meal;
  }

  long draft(long restaurant, String d, String meal) throws Exception {
    return data(send(put(slot(restaurant, d, meal) + "/draft"), chef, null)
            .andExpect(status().isOk()))
        .get("orderId")
        .asLong();
  }

  long draft() throws Exception {
    return draft(a, date, "DINNER");
  }

  Map<String, Object> addition(long d, long... options) {
    return Map.of(
        "dishId", d, "dishVersion", 0, "optionIds", Arrays.stream(options).boxed().toList());
  }

  ResultActions add(long order, String token, Map<String, Object> form, String k) throws Exception {
    return send(
        post("/api/v1/meal-orders/" + order + "/items").header("Idempotency-Key", k), token, form);
  }

  JsonNode cart() throws Exception {
    return data(send(get(slot(a, date, "DINNER")), friend, null).andExpect(status().isOk()));
  }

  String unique() {
    return UUID.randomUUID().toString();
  }

  void delta(long order, long item, String token, int n, String k) throws Exception {
    send(
            post("/api/v1/meal-orders/" + order + "/items/" + item + "/quantity")
                .header("Idempotency-Key", k),
            token,
            Map.of("delta", n))
        .andExpect(status().isOk());
  }

  void concurrently(Callable<Void> first, Callable<Void> second) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var latch = new CountDownLatch(1);
    try {
      var x =
          pool.submit(
              () -> {
                latch.await();
                return first.call();
              });
      var y =
          pool.submit(
              () -> {
                latch.await();
                return second.call();
              });
      latch.countDown();
      x.get(10, TimeUnit.SECONDS);
      y.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void readDoesNotCreateAndIdentityIsRequired() throws Exception {
    send(get(slot(a, date, "DINNER")), null, null).andExpect(status().isUnauthorized());
    send(get(slot(a, date, "DINNER")), visitor, null).andExpect(status().isForbidden());
    assertThat(cart().get("orderId").isNull()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_order", Long.class)).isZero();
  }

  @Test
  void concurrentCreationAndSeparateSlots() throws Exception {
    concurrently(
        () -> {
          draft();
          return null;
        },
        () -> {
          draft();
          return null;
        });
    long id = draft();
    assertThat(draft()).isEqualTo(id);
    assertThat(draft(b, date, "DINNER")).isNotEqualTo(id);
    assertThat(draft(a, date, "SUPPER")).isNotEqualTo(id);
    assertThat(draft(a, "2026-10-01", "DINNER")).isNotEqualTo(id);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_order", Long.class)).isEqualTo(4);
  }

  @Test
  void mergingCountsAndIdempotentRetryKeepContributors() throws Exception {
    long id = draft();
    String k = unique();
    add(id, chef, addition(dish, mild), k).andExpect(status().isOk());
    add(id, chef, addition(dish, mild), k).andExpect(status().isOk());
    add(id, chef, addition(dish, mild), unique()).andExpect(status().isOk());
    add(id, friend, addition(dish, mild), unique()).andExpect(status().isOk());
    add(id, friend, addition(dish, hot), unique()).andExpect(status().isOk());
    var c = cart();
    assertThat(c.get("items").size()).isEqualTo(3);
    assertThat(c.get("dishCount").asInt()).isEqualTo(2);
    assertThat(c.get("quantity").asInt()).isEqualTo(4);
    assertThat(c.get("contributorCount").asInt()).isEqualTo(2);
    assertThat(c.toString()).doesNotContain("recipe", "保密做法", "openid");
    add(id, chef, addition(plain), k).andExpect(status().isConflict());
  }

  @Test
  void concurrentDeltasAndRepeatedRemovalNeverGoNegative() throws Exception {
    long id = draft();
    add(id, chef, addition(plain), unique()).andExpect(status().isOk());
    long item = cart().get("items").get(0).get("id").asLong();
    concurrently(
        () -> {
          delta(id, item, chef, 1, unique());
          return null;
        },
        () -> {
          delta(id, item, friend, 1, unique());
          return null;
        });
    assertThat(cart().get("quantity").asInt()).isEqualTo(3);
    delta(id, item, friend, -1, unique());
    delta(id, item, friend, -1, unique());
    concurrently(
        () -> {
          delta(id, item, chef, -1, unique());
          return null;
        },
        () -> {
          delta(id, item, friend, -1, unique());
          return null;
        });
    assertThat(cart().get("items").size()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT quantity FROM meal_order_item WHERE id=?", Integer.class, item))
        .isEqualTo(1);
  }

  @Test
  void crossUserSpecEditsPreserveOriginalAndConflictRequiresReview() throws Exception {
    long id = draft();
    add(id, chef, addition(dish, mild), unique()).andExpect(status().isOk());
    add(id, chef, addition(dish, hot), unique()).andExpect(status().isOk());
    var c = cart();
    var item = c.get("items").get(0);
    var form =
        Map.of(
            "version",
            c.get("version").asLong(),
            "itemVersion",
            item.get("version").asLong(),
            "quantity",
            1,
            "optionIds",
            List.of(hot));
    send(put("/api/v1/meal-orders/" + id + "/items/" + item.get("id").asLong()), friend, form)
        .andExpect(status().isOk());
    send(put("/api/v1/meal-orders/" + id + "/items/" + item.get("id").asLong()), chef, form)
        .andExpect(status().isConflict());
    c = cart();
    assertThat(c.get("items").size()).isEqualTo(1);
    assertThat(c.get("quantity").asInt()).isEqualTo(2);
    assertThat(c.get("items").get(0).get("contributorId").asLong()).isEqualTo(chefId);
  }

  @Test
  void unavailableDishesSpecificationsAndDateMonthAreChecked() throws Exception {
    long id = draft();
    add(id, chef, addition(foreignDish), unique()).andExpect(status().isConflict());
    add(id, chef, addition(dish), unique()).andExpect(status().isConflict());
    add(id, chef, addition(dish, mild, hot), unique()).andExpect(status().isConflict());
    jdbc.update("UPDATE dish_spec_option SET deleted_at=UTC_TIMESTAMP(3) WHERE id=?", hot);
    add(id, chef, addition(dish, hot), unique()).andExpect(status().isConflict());
    add(id, chef, addition(seasonal), unique()).andExpect(status().isOk());
    long tomorrow = draft(a, "2026-10-01", "DINNER");
    add(tomorrow, chef, addition(seasonal), unique()).andExpect(status().isConflict());
    jdbc.update("UPDATE dish SET is_on_shelf=0 WHERE id=?", plain);
    add(id, chef, addition(plain), unique()).andExpect(status().isConflict());
    jdbc.update("UPDATE dish SET version=version+1 WHERE id=?", dish);
    add(id, chef, addition(dish, mild), unique()).andExpect(status().isConflict());
  }

  @Test
  void clearIsVersionedAndOnlyRemovesPendingRows() throws Exception {
    long id = draft();
    add(id, chef, addition(plain), unique()).andExpect(status().isOk());
    var c = cart();
    send(post("/api/v1/meal-orders/" + id + "/pending/clear"), friend, Map.of("version", 0))
        .andExpect(status().isConflict());
    send(
            post("/api/v1/meal-orders/" + id + "/pending/clear"),
            friend,
            Map.of("version", c.get("version").asLong()))
        .andExpect(status().isOk());
    assertThat(cart().get("quantity").asInt()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT removed_by_user_id FROM meal_order_item WHERE meal_order_id=?",
                Long.class,
                id))
        .isEqualTo(friendId);
    jdbc.update("UPDATE meal_order SET status='COMPLETED' WHERE id=?", id);
    send(post("/api/v1/meal-orders/" + id + "/pending/clear"), friend, Map.of("version", 2))
        .andExpect(status().isConflict());
  }

  @Test
  void midnightAndAllCutoffsAreServerControlled() throws Exception {
    clock.at("2026-10-01T00:30:00");
    var o = data(send(get("/api/v1/meal-slots"), chef, null).andExpect(status().isOk()));
    assertThat(o.get("defaultSlot").get("date").asText()).isEqualTo(date);
    assertThat(o.get("defaultSlot").get("meal").asText()).isEqualTo("SUPPER");
    draft(a, date, "SUPPER");
    clock.at("2026-10-01T02:00:00");
    send(put(slot(a, date, "SUPPER") + "/draft"), chef, null).andExpect(status().isConflict());
    for (var pair :
        Map.of("BREAKFAST", "11:00:00", "LUNCH", "15:00:00", "DINNER", "21:00:00").entrySet()) {
      clock.at("2026-10-01T" + pair.getValue());
      send(put(slot(a, "2026-10-01", pair.getKey()) + "/draft"), chef, null)
          .andExpect(status().isConflict());
    }
    clock.at("2026-09-30T16:00:00");
    send(put(slot(a, "2026-10-02", "DINNER") + "/draft"), chef, null)
        .andExpect(status().isConflict());
    long id = draft();
    clock.at("2026-09-30T21:00:00");
    add(id, chef, addition(plain), unique()).andExpect(status().isConflict());
  }

  @Test
  void terminalSlotsCannotReviveAndExpiredAccessCannotWrite() throws Exception {
    long id = draft();
    jdbc.update("UPDATE meal_order SET status='COMPLETED' WHERE id=?", id);
    send(put(slot(a, date, "DINNER") + "/draft"), chef, null).andExpect(status().isConflict());
    add(id, chef, addition(plain), unique()).andExpect(status().isConflict());
    jdbc.update("UPDATE meal_order SET status='CANCELLED' WHERE id=?", id);
    send(put(slot(a, date, "DINNER") + "/draft"), chef, null).andExpect(status().isConflict());
    long another = draft(a, date, "SUPPER");
    jdbc.update(
        "UPDATE daily_access_grant SET issued_at=DATE_SUB(?, INTERVAL 1 DAY),expires_at=? WHERE user_id=?",
        java.sql.Timestamp.from(clock.instant().minusSeconds(5)),
        java.sql.Timestamp.from(clock.instant().minusSeconds(5)),
        friendId);
    add(another, friend, addition(plain), unique()).andExpect(status().isForbidden());
  }

  @Test
  void rowAndQuantityLimitsRollBackWithoutPartialChanges() throws Exception {
    long id = draft();
    add(id, chef, addition(plain), unique()).andExpect(status().isOk());
    long item = cart().get("items").get(0).get("id").asLong();
    jdbc.update("UPDATE meal_order_item SET quantity=99 WHERE id=?", item);
    send(
            post("/api/v1/meal-orders/" + id + "/items/" + item + "/quantity")
                .header("Idempotency-Key", unique()),
            friend,
            Map.of("delta", 1))
        .andExpect(status().isBadRequest());
    assertThat(cart().get("quantity").asInt()).isEqualTo(99);
    for (int n = 0; n < 99; n++) {
      long u = user("容量" + n);
      jdbc.update(
          "INSERT INTO meal_order_item(meal_order_id,dish_id,contributor_user_id,contributor_name_snapshot,dish_name_snapshot,spec_key,spec_snapshot,quantity) VALUES(?,?,?,'容量','青菜',?,'[]',1)",
          id,
          plain,
          u,
          Tokens.sha256("[]"));
    }
    add(id, friend, addition(dish, mild), unique()).andExpect(status().isBadRequest());
    assertThat(cart().get("items").size()).isEqualTo(100);
  }

  String url(long id) {
    return "/api/v1/meal-orders/" + id;
  }

  JsonNode detail(long id, String token) throws Exception {
    return data(send(get(url(id)), token, null).andExpect(status().isOk()));
  }

  Map<String, Object> submitForm(long id, String token) throws Exception {
    var p =
        data(send(get(url(id) + "/submission-preview"), token, null).andExpect(status().isOk()));
    return Map.of(
        "version",
        p.get("order").get("version").asLong(),
        "dinerCount",
        2,
        "remark",
        "少辣👨‍👩‍👧‍👦",
        "menuFingerprint",
        p.get("menuFingerprint").asText());
  }

  ResultActions submit(long id, String token, Object f, String k) throws Exception {
    return send(post(url(id) + "/submit").header("Idempotency-Key", k), token, f);
  }

  long submitted() throws Exception {
    long id = draft();
    add(id, friend, addition(dish, mild), unique()).andExpect(status().isOk());
    submit(id, friend, submitForm(id, friend), unique()).andExpect(status().isOk());
    return id;
  }

  @Test
  void submissionKeepsSnapshotsParticipantsAndExactlyOneEvent() throws Exception {
    long id = draft();
    add(id, friend, addition(dish, mild), unique()).andExpect(status().isOk());
    var form = submitForm(id, friend);
    String k = unique();
    submit(id, friend, form, k).andExpect(status().isOk());
    submit(id, friend, form, k).andExpect(status().isOk());
    var d = detail(id, friend);
    assertThat(d.get("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(d.get("pendingItems").size()).isZero();
    assertThat(d.get("items").size()).isEqualTo(1);
    assertThat(d.get("initiatorId").asLong()).isEqualTo(friendId);
    assertThat(d.toString()).doesNotContain("recipe", "保密做法", "openid");
    assertThat(d.get("notificationState").asText()).isEqualTo("NOT_REQUESTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE business_id=?", Long.class, id))
        .isEqualTo(1);
    jdbc.update("UPDATE dish SET recipe='新的做法',name='新菜名' WHERE id=?", dish);
    assertThat(detail(id, friend).get("items").get(0).get("dishName").asText()).isEqualTo("牛腩");
    var recipe =
        data(
            send(
                    get(
                        "/api/v1/chef/meal-orders/"
                            + id
                            + "/items/"
                            + d.get("items").get(0).get("id").asLong()
                            + "/recipe"),
                    chef,
                    null)
                .andExpect(status().isOk()));
    assertThat(recipe.get("recipe").asText()).isEqualTo("保密做法");
    send(
            get(
                "/api/v1/chef/meal-orders/"
                    + id
                    + "/items/"
                    + d.get("items").get(0).get("id").asLong()
                    + "/recipe"),
            friend,
            null)
        .andExpect(status().isForbidden());
  }

  @Test
  void simultaneousDifferentSubmitKeysNeverDoubleMerge() throws Exception {
    long id = draft();
    add(id, chef, addition(plain), unique()).andExpect(status().isOk());
    add(id, friend, addition(plain), unique()).andExpect(status().isOk());
    var f = submitForm(id, chef);
    var codes = Collections.synchronizedList(new ArrayList<Integer>());
    concurrently(
        () -> {
          codes.add(submit(id, chef, f, unique()).andReturn().getResponse().getStatus());
          return null;
        },
        () -> {
          codes.add(submit(id, friend, f, unique()).andReturn().getResponse().getStatus());
          return null;
        });
    assertThat(codes).containsExactlyInAnyOrder(200, 409);
    assertThat(detail(id, chef).get("items").size()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Long.class))
        .isEqualTo(1);
  }

  @Test
  void submitConflictsAndMenuInvalidationKeepSelections() throws Exception {
    long id = draft();
    add(id, chef, addition(dish, mild), unique()).andExpect(status().isOk());
    var f = submitForm(id, chef);
    add(id, friend, addition(plain), unique()).andExpect(status().isOk());
    submit(id, chef, f, unique()).andExpect(status().isConflict());
    f = submitForm(id, chef);
    jdbc.update("UPDATE dish SET version=version+1 WHERE id=?", dish);
    submit(id, chef, f, unique()).andExpect(status().isConflict());
    jdbc.update("UPDATE dish SET is_on_shelf=0 WHERE id=?", dish);
    var p = data(send(get(url(id) + "/submission-preview"), chef, null).andExpect(status().isOk()));
    assertThat(p.get("invalidItems").size()).isEqualTo(1);
    assertThat(p.get("canSubmit").asBoolean()).isFalse();
    submit(id, chef, submitForm(id, chef), unique()).andExpect(status().isConflict());
    assertThat(cart().get("items").size()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Long.class))
        .isZero();
  }

  @Test
  void emptyUnrelatedAndInvalidMetadataCannotSubmit() throws Exception {
    long id = draft();
    submit(id, chef, submitForm(id, chef), unique()).andExpect(status().isConflict());
    add(id, chef, addition(plain), unique()).andExpect(status().isOk());
    submit(id, friend, submitForm(id, friend), unique()).andExpect(status().isForbidden());
    var f = new HashMap<>(submitForm(id, chef));
    f.put("dinerCount", 21);
    submit(id, chef, f, unique()).andExpect(status().isBadRequest());
    f.put("dinerCount", 2);
    f.put("remark", "字".repeat(101));
    submit(id, chef, f, unique()).andExpect(status().isBadRequest());
  }

  @Test
  void additionsStayPendingUntilMergeAndClearKeepsFormalOrder() throws Exception {
    long id = submitted();
    add(id, friend, addition(dish, mild), unique()).andExpect(status().isOk());
    var d = detail(id, friend);
    assertThat(d.get("items").get(0).get("quantity").asInt()).isEqualTo(1);
    assertThat(d.get("pendingItems").size()).isEqualTo(1);
    var f = submitForm(id, friend);
    String k = unique();
    submit(id, friend, f, k).andExpect(status().isOk());
    submit(id, friend, f, k).andExpect(status().isOk());
    d = detail(id, friend);
    assertThat(d.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    assertThat(d.get("pendingItems").size()).isZero();
    add(id, chef, addition(plain), unique()).andExpect(status().isOk());
    d = detail(id, chef);
    send(post(url(id) + "/pending/clear"), chef, Map.of("version", d.get("version").asLong()))
        .andExpect(status().isOk());
    assertThat(detail(id, chef).get("items").size()).isEqualTo(1);
  }

  @Test
  void formalEditsRetainOriginalAndLastRemovalRequiresExplicitCancellation() throws Exception {
    long id = submitted();
    var d = detail(id, chef);
    var i = d.get("items").get(0);
    long item = i.get("id").asLong();
    send(
            put(url(id) + "/items/" + item),
            chef,
            Map.of(
                "version",
                d.get("version").asLong(),
                "itemVersion",
                i.get("version").asLong(),
                "quantity",
                3,
                "optionIds",
                List.of(hot)))
        .andExpect(status().isOk());
    d = detail(id, friend);
    assertThat(d.get("items").get(0).get("contributorId").asLong()).isEqualTo(friendId);
    send(delete(url(id) + "/items/" + item), chef, Map.of("version", d.get("version").asLong()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CANCEL_REQUIRED"));
    assertThat(detail(id, chef).get("items").size()).isEqualTo(1);
  }

  @Test
  void metadataAndCancellationRespectIndependentPermissions() throws Exception {
    long id = submitted();
    long stranger = user("协作者");
    String token = session(stranger);
    jdbc.update(
        "INSERT INTO daily_access_grant(user_id,secret_version,grant_generation,issued_at,expires_at) VALUES(?,1,1,?,?)",
        stranger,
        java.sql.Timestamp.from(clock.instant()),
        java.sql.Timestamp.from(clock.instant().plusSeconds(600)));
    var d = detail(id, token);
    assertThat(d.get("canMeta").asBoolean()).isFalse();
    send(
            put(url(id) + "/meta"),
            token,
            Map.of("version", d.get("version").asLong(), "dinerCount", 4, "remark", "备注"))
        .andExpect(status().isForbidden());
    send(
            post(url(id) + "/cancel").header("Idempotency-Key", unique()),
            token,
            Map.of("version", d.get("version").asLong()))
        .andExpect(status().isForbidden());
    send(
            put(url(id) + "/meta"),
            chef,
            Map.of("version", d.get("version").asLong(), "dinerCount", 4, "remark", "备注"))
        .andExpect(status().isOk());
    send(
            put(url(id) + "/meta"),
            friend,
            Map.of("version", d.get("version").asLong(), "dinerCount", 3, "remark", "冲突"))
        .andExpect(status().isConflict());
  }

  @Test
  void cancellationStopsOldEventsAndExplicitReopenCreatesEmptyNewDraft() throws Exception {
    long id = submitted();
    add(id, chef, addition(plain), unique()).andExpect(status().isOk());
    var f = submitForm(id, chef);
    submit(id, chef, f, unique()).andExpect(status().isOk());
    var d = detail(id, chef);
    String k = unique();
    var cancel = Map.of("version", d.get("version").asLong(), "reason", "这餐不做");
    send(post(url(id) + "/cancel").header("Idempotency-Key", k), chef, cancel)
        .andExpect(status().isOk());
    send(post(url(id) + "/cancel").header("Idempotency-Key", k), chef, cancel)
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type<>'ORDER_CANCELLED' AND status='PENDING'",
                Long.class))
        .isZero();
    send(put(slot(a, date, "DINNER") + "/draft"), chef, null).andExpect(status().isConflict());
    d = detail(id, chef);
    var reopen = Map.of("version", d.get("version").asLong());
    String rk = unique();
    long next =
        data(send(post(url(id) + "/reopen").header("Idempotency-Key", rk), chef, reopen)
                .andExpect(status().isOk()))
            .get("orderId")
            .asLong();
    assertThat(
            data(send(post(url(id) + "/reopen").header("Idempotency-Key", rk), chef, reopen)
                    .andExpect(status().isOk()))
                .get("orderId")
                .asLong())
        .isEqualTo(next);
    assertThat(next).isNotEqualTo(id);
    assertThat(cart().get("items").size()).isZero();
    add(id, chef, addition(plain), unique()).andExpect(status().isConflict());
  }

  @Test
  void historyIsFilteredAndCutoffClosesOrdinaryWrites() throws Exception {
    long id = submitted();
    var f = submitForm(id, friend);
    clock.at("2026-09-30T21:00:00");
    submit(id, friend, f, unique()).andExpect(status().isConflict());
    add(id, chef, addition(plain), unique()).andExpect(status().isConflict());
    var d = detail(id, friend);
    assertThat(d.get("confirmationDue").asBoolean()).isTrue();
    assertThat(d.get("canModify").asBoolean()).isFalse();
    // Another chef has daily privileges, but no participation in this historical/closed order.
    long other = user("另一主厨");
    String token = session(other);
    jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE id=?", other, b);
    send(get(url(id)), token, null).andExpect(status().isForbidden());
    var list =
        data(
            send(get("/api/v1/meal-orders?state=IN_PROGRESS"), token, null)
                .andExpect(status().isOk()));
    assertThat(list.get("orders").size()).isZero();
    jdbc.update("UPDATE meal_order SET status='CANCELLED' WHERE id=?", id);
    list =
        data(
            send(get("/api/v1/meal-orders?state=HISTORY"), friend, null)
                .andExpect(status().isOk()));
    assertThat(list.get("orders").size()).isEqualTo(1);
  }

  @Test
  void mergeOverflowRollsBackOrderParticipantsAndEvents() throws Exception {
    long id = submitted();
    jdbc.update(
        "UPDATE meal_order_item SET quantity=99 WHERE meal_order_id=? AND item_stage='SUBMITTED'",
        id);
    add(id, friend, addition(dish, mild), unique()).andExpect(status().isOk());
    var before = detail(id, chef);
    submit(id, friend, submitForm(id, friend), unique()).andExpect(status().isBadRequest());
    var after = detail(id, chef);
    assertThat(after.get("version")).isEqualTo(before.get("version"));
    assertThat(after.get("pendingItems").size()).isEqualTo(1);
    assertThat(after.get("items").get(0).get("quantity").asInt()).isEqualTo(99);
  }

  @Test
  void dueReminderIsOnceAndNeverAutoCompletes() throws Exception {
    long id = submitted();
    clock.at("2026-09-30T21:00:00");
    var job =
        new com.myhome.table.ordering.application.OrderReminderJob(
            new com.myhome.table.ordering.infrastructure.CartRepository(jdbc, json),
            new com.myhome.table.ordering.application.OrderEffects(
                new com.myhome.table.ordering.infrastructure.CartRepository(jdbc, json), clock),
            clock,
            tx);
    job.scan();
    job.scan();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type='MEAL_CONFIRM_DUE'",
                Long.class))
        .isEqualTo(1);
    assertThat(detail(id, chef).get("status").asText()).isEqualTo("IN_PROGRESS");
    jdbc.update("UPDATE meal_order SET status='CANCELLED',reminder_created_at=NULL WHERE id=?", id);
    job.scan();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type='MEAL_CONFIRM_DUE'",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void changesMergeFromFirstEventWithoutExtendingWindow() throws Exception {
    long id = submitted();
    long item = detail(id, chef).get("items").get(0).get("id").asLong();
    delta(id, item, chef, 1, unique());
    var available =
        jdbc.queryForObject(
            "SELECT available_at FROM notification_outbox WHERE event_type='ORDER_CHANGED'",
            java.sql.Timestamp.class);
    clock.at("2026-09-30T16:00:20");
    delta(id, item, friend, 1, unique());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type='ORDER_CHANGED'",
                Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT available_at FROM notification_outbox WHERE event_type='ORDER_CHANGED'",
                java.sql.Timestamp.class))
        .isEqualTo(available);
    clock.at("2026-09-30T16:00:31");
    delta(id, item, friend, 1, unique());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type='ORDER_CHANGED'",
                Long.class))
        .isEqualTo(2);
  }

  @Test
  void collaboratorWhoOnlySubmitsOthersAdditionDoesNotBecomeOriginalOrderer() throws Exception {
    long id = submitted();
    var i = detail(id, chef).get("items").get(0);
    delta(id, i.get("id").asLong(), chef, 1, unique());
    add(id, friend, addition(plain), unique()).andExpect(status().isOk());
    submit(id, chef, submitForm(id, chef), unique()).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT is_orderer FROM meal_order_participant WHERE meal_order_id=? AND user_id=?",
                Integer.class,
                id,
                chefId))
        .isZero();
  }

  @Test
  void unboundRestaurantCannotSilentlySubmitWithoutChefRecipient() throws Exception {
    jdbc.update("INSERT INTO dish_supply_month(dish_id,supply_month) VALUES(?,9)", foreignDish);
    long id = draft(b, date, "DINNER");
    add(id, chef, addition(foreignDish), unique()).andExpect(status().isOk());
    submit(id, chef, submitForm(id, chef), unique())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESTAURANT_NOT_READY"));
    assertThat(detail(id, chef).get("status").asText()).isEqualTo("DRAFT");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Long.class))
        .isZero();
  }

  @Test
  void chefWorkbenchListsOwnFormalOrdersAndRejectsGuestEntry() throws Exception {
    long id = submitted();
    long otherChef = user("她的主厨");
    String token = session(otherChef);
    jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE id=?", otherChef, b);
    var list = data(send(get("/api/v1/chef/meal-orders"), chef, null).andExpect(status().isOk()));
    assertThat(list.get("orders").size()).isEqualTo(1);
    assertThat(list.get("orders").get(0).get("id").asLong()).isEqualTo(id);
    assertThat(
            data(send(get("/api/v1/chef/meal-orders"), token, null).andExpect(status().isOk()))
                .get("orders")
                .size())
        .isZero();
    send(get("/api/v1/chef/meal-orders"), friend, null).andExpect(status().isForbidden());
  }

  String confirmationUrl(long id) {
    return "/api/v1/chef/meal-orders/" + id;
  }

  Object originalActual(long id, int quantity) throws Exception {
    return Map.of(
        "sourceOrderItemId",
        detail(id, chef).get("items").get(0).get("id").asLong(),
        "quantity",
        quantity);
  }

  Object completionForm(long id, List<?> items) throws Exception {
    return Map.of("version", detail(id, chef).get("version").asLong(), "items", items);
  }

  ResultActions complete(long id, String token, Object form, String k) throws Exception {
    return send(post(confirmationUrl(id) + "/complete").header("Idempotency-Key", k), token, form);
  }

  @Test
  void actualCompletionKeepsOrderersPendingAndDeduplicatesStatisticsFacts() throws Exception {
    long id = submitted();
    add(id, chef, addition(plain), unique()).andExpect(status().isOk());
    var f =
        completionForm(
            id,
            List.of(
                Map.of("dishId", plain, "dishVersion", 0, "optionIds", List.of(), "quantity", 3)));
    String k = unique();
    complete(id, chef, f, k).andExpect(status().isOk());
    complete(id, chef, f, k).andExpect(status().isOk());
    var d = detail(id, friend);
    assertThat(d.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(d.get("actualItems").size()).isEqualTo(1);
    assertThat(d.get("actualItems").get(0).get("sourceType").asText()).isEqualTo("CONFIRM_ADDED");
    assertThat(d.get("actualItems").get(0).get("quantity").asInt()).isEqualTo(3);
    assertThat(d.get("items").get(0).get("dishName").asText()).isEqualTo("牛腩");
    assertThat(d.get("pendingItems").size()).isEqualTo(1);
    assertThat(d.get("reviewDeadline").asText())
        .isEqualTo(clock.instant().plus(Duration.ofDays(7)).toString());
    assertThat(d.get("canConfirm").asBoolean()).isFalse();
    assertThat(
            jdbc.queryForList(
                "SELECT recipient_user_id FROM notification_outbox WHERE business_id=? AND event_type='REVIEW_INVITED'",
                Long.class,
                id))
        .containsExactly(friendId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE business_id=? AND event_type='ORDER_SUBMITTED' AND status='CANCELLED'",
                Long.class,
                id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT o.id) FROM meal_order o JOIN meal_actual_item a ON a.meal_order_id=o.id WHERE o.status='COMPLETED'",
                Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT meal_order_id) FROM meal_actual_item WHERE dish_id=?",
                Long.class,
                plain))
        .isEqualTo(1);
    complete(id, chef, f, unique()).andExpect(status().isConflict());
    send(
            post(url(id) + "/cancel").header("Idempotency-Key", unique()),
            friend,
            Map.of("version", d.get("version").asLong(), "reason", "再取消"))
        .andExpect(status().isConflict());
  }

  @Test
  void lateCompletionRetainsDeletedSnapshotAndAllowsOffShelfOutsideSeason() throws Exception {
    long id = submitted();
    var source = originalActual(id, 2);
    jdbc.update("UPDATE dish SET deleted_at=NOW(3),is_on_shelf=0 WHERE id=?", dish);
    jdbc.update("UPDATE dish SET is_on_shelf=0 WHERE id=?", seasonal);
    clock.at("2026-10-01T16:00:00");
    var p =
        data(
            send(get(confirmationUrl(id) + "/confirmation-preview"), chef, null)
                .andExpect(status().isOk()));
    assertThat(p.toString()).doesNotContain("保密做法", "recipe", "openid");
    assertThat(p.get("candidates").findValuesAsText("name")).contains("九月限定").doesNotContain("牛腩");
    var f =
        completionForm(
            id,
            List.of(
                source,
                Map.of(
                    "dishId", seasonal, "dishVersion", 0, "optionIds", List.of(), "quantity", 1)));
    complete(id, chef, f, unique()).andExpect(status().isOk());
    assertThat(detail(id, chef).get("actualItems").size()).isEqualTo(2);
    assertThat(detail(id, chef).get("actualItems").get(0).get("dishName").asText()).isEqualTo("牛腩");
  }

  @Test
  void confirmationOnlyOwnChefAndDateArrived() throws Exception {
    long id = submitted();
    var f = completionForm(id, List.of(originalActual(id, 1)));
    complete(id, friend, f, unique()).andExpect(status().isForbidden());
    send(get(confirmationUrl(id) + "/confirmation-preview"), friend, null)
        .andExpect(status().isForbidden());
    long other = user("其他主厨");
    String token = session(other);
    jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE id=?", other, b);
    complete(id, token, f, unique()).andExpect(status().isForbidden());
    long tomorrow = draft(a, "2026-10-01", "DINNER");
    add(tomorrow, chef, addition(plain), unique()).andExpect(status().isOk());
    submit(tomorrow, chef, submitForm(tomorrow, chef), unique()).andExpect(status().isOk());
    complete(
            tomorrow,
            chef,
            completionForm(tomorrow, List.of(originalActual(tomorrow, 1))),
            unique())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MEAL_NOT_STARTED"));
    send(
            post(confirmationUrl(tomorrow) + "/not-cooked").header("Idempotency-Key", unique()),
            chef,
            Map.of("version", detail(tomorrow, chef).get("version").asLong()))
        .andExpect(status().isConflict());
  }

  @Test
  void invalidSupplementOrSnapshotRollsBackWholeCompletion() throws Exception {
    long id = submitted();
    var original = originalActual(id, 1);
    for (Object invalid :
        List.of(
            Map.of("dishId", foreignDish, "dishVersion", 0, "optionIds", List.of(), "quantity", 1),
            Map.of("dishId", dish, "dishVersion", 0, "optionIds", List.of(), "quantity", 1),
            Map.of("sourceOrderItemId", 99999999, "quantity", 1))) {
      complete(id, chef, completionForm(id, List.of(original, invalid)), unique())
          .andExpect(status().isConflict());
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM meal_actual_item WHERE meal_order_id=?", Long.class, id))
          .isZero();
      assertThat(detail(id, chef).get("status").asText()).isEqualTo("IN_PROGRESS");
    }
    var old = completionForm(id, List.of(original));
    long item = detail(id, chef).get("items").get(0).get("id").asLong();
    delta(id, item, friend, 1, unique());
    complete(id, chef, old, unique())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  void actualQuantitiesMergePerDishSpecAndRejectEmptyOverflow() throws Exception {
    long id = submitted();
    complete(id, chef, completionForm(id, List.of()), unique()).andExpect(status().isBadRequest());
    complete(
            id,
            chef,
            completionForm(id, List.of(originalActual(id, 99), originalActual(id, 1))),
            unique())
        .andExpect(status().isBadRequest());
    var f =
        completionForm(
            id,
            List.of(
                originalActual(id, 2),
                originalActual(id, 3),
                Map.of(
                    "dishId", dish, "dishVersion", 0, "optionIds", List.of(hot), "quantity", 1)));
    complete(id, chef, f, unique()).andExpect(status().isOk());
    var actual = detail(id, chef).get("actualItems");
    assertThat(actual.size()).isEqualTo(2);
    assertThat(actual.get(0).get("quantity").asInt()).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT meal_order_id) FROM meal_actual_item WHERE dish_id=?",
                Long.class,
                dish))
        .isEqualTo(1);
  }

  @Test
  void notCookedAfterCutoffCancelsWithoutActualOrReview() throws Exception {
    long id = submitted();
    clock.at("2026-10-01T16:00:00");
    var f = Map.of("version", detail(id, chef).get("version").asLong(), "reason", "这顿没做");
    String k = unique();
    send(post(confirmationUrl(id) + "/not-cooked").header("Idempotency-Key", k), friend, f)
        .andExpect(status().isForbidden());
    send(post(confirmationUrl(id) + "/not-cooked").header("Idempotency-Key", k), chef, f)
        .andExpect(status().isOk());
    send(post(confirmationUrl(id) + "/not-cooked").header("Idempotency-Key", k), chef, f)
        .andExpect(status().isOk());
    assertThat(detail(id, chef).get("status").asText()).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_actual_item", Long.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type='REVIEW_INVITED'",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT recipient_user_id FROM notification_outbox WHERE event_type='ORDER_CANCELLED'",
                Long.class))
        .containsExactly(friendId);
  }

  @Test
  void concurrentCompleteAndCancelOnlyOneTerminalTransition() throws Exception {
    long id = submitted();
    var f = completionForm(id, List.of(originalActual(id, 1)));
    long v = detail(id, chef).get("version").asLong();
    List<Integer> codes = Collections.synchronizedList(new ArrayList<>());
    concurrently(
        () -> {
          codes.add(complete(id, chef, f, unique()).andReturn().getResponse().getStatus());
          return null;
        },
        () -> {
          codes.add(
              send(
                      post(url(id) + "/cancel").header("Idempotency-Key", unique()),
                      friend,
                      Map.of("version", v, "reason", "不做了"))
                  .andReturn()
                  .getResponse()
                  .getStatus());
          return null;
        });
    assertThat(codes).containsExactlyInAnyOrder(200, 409);
    String state = detail(id, chef).get("status").asText();
    assertThat(state).isIn("COMPLETED", "CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM meal_actual_item WHERE meal_order_id=?", Long.class, id))
        .isEqualTo("COMPLETED".equals(state) ? 1 : 0);
  }

  @Test
  void concurrentDifferentCompletionKeysCannotDoubleCount() throws Exception {
    long id = submitted();
    var f = completionForm(id, List.of(originalActual(id, 1)));
    List<Integer> codes = Collections.synchronizedList(new ArrayList<>());
    concurrently(
        () -> {
          codes.add(complete(id, chef, f, unique()).andReturn().getResponse().getStatus());
          return null;
        },
        () -> {
          codes.add(complete(id, chef, f, unique()).andReturn().getResponse().getStatus());
          return null;
        });
    assertThat(codes).containsExactlyInAnyOrder(200, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM meal_actual_item WHERE meal_order_id=?", Long.class, id))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE business_id=? AND event_type='REVIEW_INVITED'",
                Long.class,
                id))
        .isEqualTo(1);
  }

  long reviewOrder() throws Exception {
    long id = submitted();
    complete(
            id,
            chef,
            completionForm(
                id,
                List.of(
                    originalActual(id, 2),
                    Map.of(
                        "dishId", dish, "dishVersion", 0, "optionIds", List.of(hot), "quantity", 1),
                    Map.of(
                        "dishId", plain, "dishVersion", 0, "optionIds", List.of(), "quantity", 1))),
            unique())
        .andExpect(status().isOk());
    return id;
  }

  JsonNode reviewSheet(long id, String token) throws Exception {
    return data(send(get(url(id) + "/review-sheet"), token, null).andExpect(status().isOk()));
  }

  String share(long id, String token) throws Exception {
    return data(send(post(url(id) + "/review-shares"), token, null).andExpect(status().isOk()))
        .get("token")
        .asText();
  }

  ResultActions reviewWrite(long id, String token, Object form, String key) throws Exception {
    return send(post(url(id) + "/reviews").header("Idempotency-Key", key), token, form);
  }

  Map<String, Object> rating(long d, int n) {
    return Map.of("dishId", d, "rating", n, "comment", "好吃");
  }

  String invitationUrl(String token) {
    return "/api/v1/review-invitations/" + token;
  }

  @Test
  void reviewSheetGroupsSpecificationsAndShowsLiveAverage() throws Exception {
    long id = reviewOrder();
    var sheet = reviewSheet(id, friend);
    assertThat(sheet.get("foods").size()).isEqualTo(2);
    assertThat(sheet.get("foods").get(0).get("quantity").asInt()).isEqualTo(3);
    assertThat(sheet.get("foods").get(0).get("mine").isNull()).isTrue();
    String k = unique();
    var form = rating(dish, 4);
    String beforeGeneration = redis.opsForValue().get("core:home:generation");
    var review = data(reviewWrite(id, friend, form, k).andExpect(status().isOk()));
    String afterGeneration = redis.opsForValue().get("core:home:generation");
    assertThat(afterGeneration).isNotEqualTo(beforeGeneration);
    reviewWrite(id, friend, form, k).andExpect(status().isOk());
    assertThat(redis.opsForValue().get("core:home:generation")).isEqualTo(afterGeneration);
    reviewWrite(id, friend, form, unique())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVIEW_EXISTS"));
    assertThat(redis.opsForValue().get("core:home:generation")).isEqualTo(afterGeneration);
    assertThat(review.get("reviewerName").asText()).isEqualTo("朋友👨‍👩‍👧‍👦".repeat(4));
    reviewWrite(id, chef, rating(dish, 2), unique()).andExpect(status().isOk());
    var food = reviewSheet(id, friend).get("foods").get(0);
    assertThat(food.get("average").asDouble()).isEqualTo(3);
    assertThat(food.get("reviewCount").asInt()).isEqualTo(2);
    assertThat(reviewSheet(id, friend).get("reviewedCount").asInt()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dish_review", Long.class)).isEqualTo(2);
  }

  @Test
  void shareVisitorGetsRestrictedScopeWithoutDailyOrParticipantPromotion() throws Exception {
    long id = reviewOrder();
    String token = share(id, friend);
    var sheet = data(send(get(invitationUrl(token)), visitor, null).andExpect(status().isOk()));
    assertThat(sheet.toString())
        .doesNotContain(
            "initiator",
            "participants",
            "contributor",
            "remark",
            "recipe",
            "openid",
            "cancelReason");
    send(get(url(id)), visitor, null).andExpect(status().isForbidden());
    send(get("/api/v1/meal-slots"), visitor, null).andExpect(status().isForbidden());
    String k = unique();
    var form = rating(plain, 5);
    send(post(invitationUrl(token) + "/reviews").header("Idempotency-Key", k), visitor, form)
        .andExpect(status().isOk());
    send(post(invitationUrl(token) + "/reviews").header("Idempotency-Key", k), visitor, form)
        .andExpect(status().isOk());
    reviewWrite(id, visitor, form, unique()).andExpect(status().isConflict());
    assertThat(reviewSheet(id, visitor).get("reviewedCount").asInt()).isEqualTo(1);
    long guest = jdbc.queryForObject("SELECT id FROM app_user WHERE nickname='访客'", Long.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM meal_order_participant WHERE meal_order_id=? AND user_id=?",
                Long.class,
                id,
                guest))
        .isZero();
    String forwarded = share(id, visitor);
    assertThat(forwarded).hasSize(43).isNotEqualTo(token);
    assertThat(
            jdbc.queryForObject(
                "SELECT token_hash FROM review_share WHERE token_hash=?",
                String.class,
                Tokens.sha256(token)))
        .isNotEqualTo(token);
    assertThat(
            jdbc.queryForList(
                    "SELECT response_json FROM api_idempotency_record WHERE user_id=?",
                    String.class,
                    friendId)
                .toString())
        .doesNotContain(token);
  }

  @Test
  void expiredShareKeepsAcquiredScopeReadOnlyButRejectsNewPeople() throws Exception {
    long id = reviewOrder();
    String token = share(id, chef);
    send(get(invitationUrl(token)), visitor, null).andExpect(status().isOk());
    clock.at("2026-10-07T16:00:00");
    var read = data(send(get(invitationUrl(token)), visitor, null).andExpect(status().isOk()));
    assertThat(read.get("canSubmit").asBoolean()).isFalse();
    send(
            post(invitationUrl(token) + "/reviews").header("Idempotency-Key", unique()),
            visitor,
            rating(plain, 3))
        .andExpect(status().isGone());
    String newcomer = session(user("新朋友"));
    send(get(invitationUrl(token)), newcomer, null).andExpect(status().isGone());
    send(post(url(id) + "/review-shares"), visitor, null).andExpect(status().isGone());
  }

  @Test
  void malformedDisabledAndAnonymousSharesNeverExposeFoods() throws Exception {
    long id = reviewOrder();
    String token = share(id, chef);
    send(get(invitationUrl(token)), null, null).andExpect(status().isUnauthorized());
    send(get(invitationUrl("wrong")), visitor, null).andExpect(status().isNotFound());
    jdbc.update(
        "UPDATE review_share SET status='DISABLED' WHERE token_hash=?", Tokens.sha256(token));
    send(get(invitationUrl(token)), visitor, null).andExpect(status().isNotFound());
    send(
            post(invitationUrl(token) + "/reviews").header("Idempotency-Key", unique()),
            visitor,
            rating(plain, 4))
        .andExpect(status().isNotFound());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dish_review", Long.class)).isZero();
  }

  @Test
  void reviewRequiresActualDishAndIntegerRatingWithUnicodeStorage() throws Exception {
    long id = reviewOrder();
    for (Object invalid :
        List.of(
            rating(foreignDish, 4),
            rating(dish, 0),
            rating(dish, 6),
            Map.of("dishId", dish, "rating", 2.5),
            Map.of("dishId", dish, "rating", 4, "comment", "字".repeat(201))))
      reviewWrite(id, friend, invalid, unique()).andExpect(status().isBadRequest());
    String text = "👨‍👩‍👧‍👦".repeat(200);
    reviewWrite(id, friend, Map.of("dishId", dish, "rating", 3, "comment", text), unique())
        .andExpect(status().isOk());
    assertThat(reviewSheet(id, friend).get("foods").get(0).get("mine").get("comment").asText())
        .isEqualTo(text);
  }

  @Test
  void onlyCompletedOrdersAndOriginalInvitationsAreEligible() throws Exception {
    long id = submitted();
    send(get(url(id) + "/review-sheet"), friend, null).andExpect(status().isNotFound());
    reviewWrite(id, friend, rating(dish, 4), unique()).andExpect(status().isNotFound());
    complete(id, chef, completionForm(id, List.of(originalActual(id, 1))), unique())
        .andExpect(status().isOk());
    jdbc.update("DELETE FROM daily_access_grant WHERE user_id=?", friendId);
    assertThat(reviewSheet(id, friend).get("canSubmit").asBoolean()).isTrue();
    var invitations =
        data(send(get("/api/v1/my/review-invitations"), friend, null).andExpect(status().isOk()));
    assertThat(invitations.get("invitations").size()).isEqualTo(1);
    assertThat(
            data(send(get("/api/v1/my/review-invitations"), visitor, null)
                    .andExpect(status().isOk()))
                .get("invitations")
                .size())
        .isZero();
    send(get(url(id)), friend, null).andExpect(status().isForbidden());
    assertThat(
            jdbc.queryForObject(
                "SELECT page_path FROM notification_outbox WHERE business_id=? AND event_type='REVIEW_INVITED'",
                String.class,
                id))
        .isEqualTo("subpackages/reviews/sheet?id=" + id);
  }

  @Test
  void modificationIsOwnerOnlyOnceAndPreservesFirstIdentityAndCount() throws Exception {
    long id = reviewOrder();
    var first = data(reviewWrite(id, friend, rating(dish, 4), unique()).andExpect(status().isOk()));
    long rid = first.get("id").asLong();
    jdbc.update("UPDATE app_user SET nickname='改名后' WHERE id=?", friendId);
    var f = Map.of("version", 0, "rating", 2, "comment", "改为两星");
    String k = unique();
    send(put("/api/v1/reviews/" + rid).header("Idempotency-Key", unique()), chef, f)
        .andExpect(status().isForbidden());
    send(put("/api/v1/reviews/" + rid).header("Idempotency-Key", k), friend, f)
        .andExpect(status().isOk());
    send(put("/api/v1/reviews/" + rid).header("Idempotency-Key", k), friend, f)
        .andExpect(status().isOk());
    send(
            put("/api/v1/reviews/" + rid).header("Idempotency-Key", unique()),
            friend,
            Map.of("version", 1, "rating", 3))
        .andExpect(status().isConflict());
    var food = reviewSheet(id, friend).get("foods").get(0);
    assertThat(food.get("average").asDouble()).isEqualTo(2);
    assertThat(food.get("reviewCount").asInt()).isEqualTo(1);
    assertThat(food.get("mine").get("reviewerName").asText()).isEqualTo("朋友👨‍👩‍👧‍👦".repeat(4));
    assertThat(food.get("mine").get("firstSubmittedAt")).isEqualTo(first.get("firstSubmittedAt"));
  }

  @Test
  void twentyFourHourAndSevenDayBoundariesUseServerClock() throws Exception {
    long id = reviewOrder();
    long rid =
        data(reviewWrite(id, friend, rating(dish, 5), unique()).andExpect(status().isOk()))
            .get("id")
            .asLong();
    clock.at("2026-10-01T16:00:00");
    send(
            put("/api/v1/reviews/" + rid).header("Idempotency-Key", unique()),
            friend,
            Map.of("version", 0, "rating", 4))
        .andExpect(status().isConflict());
    clock.at("2026-10-07T15:59:59.999");
    long late =
        data(reviewWrite(id, friend, rating(plain, 4), unique()).andExpect(status().isOk()))
            .get("id")
            .asLong();
    clock.at("2026-10-07T16:00:00");
    send(
            put("/api/v1/reviews/" + late).header("Idempotency-Key", unique()),
            friend,
            Map.of("version", 0, "rating", 3))
        .andExpect(status().isGone());
    assertThat(reviewSheet(id, friend).get("canSubmit").asBoolean()).isFalse();
  }

  @Test
  void concurrentFirstReviewsAcrossEntrypointsCreateExactlyOneRecord() throws Exception {
    long id = reviewOrder();
    String token = share(id, chef);
    var form = rating(dish, 4);
    List<Integer> codes = Collections.synchronizedList(new ArrayList<>());
    concurrently(
        () -> {
          codes.add(reviewWrite(id, friend, form, unique()).andReturn().getResponse().getStatus());
          return null;
        },
        () -> {
          codes.add(
              send(
                      post(invitationUrl(token) + "/reviews").header("Idempotency-Key", unique()),
                      friend,
                      form)
                  .andReturn()
                  .getResponse()
                  .getStatus());
          return null;
        });
    assertThat(codes).containsExactlyInAnyOrder(200, 409);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dish_review", Long.class)).isEqualTo(1);
  }

  @Test
  void concurrentModificationsConsumeOnlyOneEdit() throws Exception {
    long id = reviewOrder();
    long rid =
        data(reviewWrite(id, friend, rating(dish, 5), unique()).andExpect(status().isOk()))
            .get("id")
            .asLong();
    List<Integer> codes = Collections.synchronizedList(new ArrayList<>());
    concurrently(
        () -> {
          codes.add(
              send(
                      put("/api/v1/reviews/" + rid).header("Idempotency-Key", unique()),
                      friend,
                      Map.of("version", 0, "rating", 3))
                  .andReturn()
                  .getResponse()
                  .getStatus());
          return null;
        },
        () -> {
          codes.add(
              send(
                      put("/api/v1/reviews/" + rid).header("Idempotency-Key", unique()),
                      friend,
                      Map.of("version", 0, "rating", 4))
                  .andReturn()
                  .getResponse()
                  .getStatus());
          return null;
        });
    assertThat(codes).containsExactlyInAnyOrder(200, 409);
    assertThat(
            jdbc.queryForObject("SELECT modify_count FROM dish_review WHERE id=?", Long.class, rid))
        .isEqualTo(1);
  }

  @Test
  void ownHistoryAndDishReviewsExposeSnapshotButRespectAccess() throws Exception {
    long id = reviewOrder();
    reviewWrite(id, friend, rating(dish, 4), unique()).andExpect(status().isOk());
    var mine = data(send(get("/api/v1/my/reviews"), friend, null).andExpect(status().isOk()));
    assertThat(mine.get("reviews").get(0).get("dishName").asText()).isEqualTo("牛腩");
    assertThat(
            data(send(get("/api/v1/my/reviews"), chef, null).andExpect(status().isOk()))
                .get("reviews")
                .size())
        .isZero();
    var publicReviews =
        data(
            send(get("/api/v1/dishes/" + dish + "/reviews"), chef, null)
                .andExpect(status().isOk()));
    assertThat(publicReviews.get("reviews").size()).isEqualTo(1);
    assertThat(publicReviews.get("average").asDouble()).isEqualTo(4);
    assertThat(publicReviews.get("reviewCount").asLong()).isEqualTo(1);
    assertThat(publicReviews.get("reviews").get(0).get("canModify").asBoolean()).isFalse();
    assertThat(publicReviews.toString()).doesNotContain("openid", "recipe", "remark");
    send(get("/api/v1/dishes/" + dish + "/reviews"), visitor, null)
        .andExpect(status().isForbidden());
    send(get("/api/v1/my/reviews?page=0"), friend, null).andExpect(status().isBadRequest());
  }
}
