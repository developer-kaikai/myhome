package com.myhome.table.core;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.myhome.table.common.security.SessionStore;
import com.myhome.table.common.util.Tokens;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_INTEGRATION", matches = "true")
class MenuIntegrationTest {
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
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper json;
  @Autowired TransactionTemplate tx;
  private final List<String> keys = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private long a, b, seasonal, category;
  private String chef, other, guest, visitor;

  @BeforeEach
  void prepare() throws Exception {
    a =
        jdbc.queryForObject(
            "SELECT id FROM restaurant WHERE restaurant_code='RESTAURANT_A'", Long.class);
    b =
        jdbc.queryForObject(
            "SELECT id FROM restaurant WHERE restaurant_code='RESTAURANT_B'", Long.class);
    chef = session(user(a, true));
    other = session(user(b, true));
    send(put("/api/v1/chef/access/passcode"), chef, Map.of("passcode", "Menu123", "version", 0))
        .andExpect(status().isOk());
    guest = session(user(0, true));
    visitor = session(user(0, false));
    seasonal =
        jdbc.queryForObject(
            "SELECT id FROM menu_category WHERE restaurant_id=? AND category_type='SEASONAL'",
            Long.class,
            a);
    jdbc.update("INSERT INTO menu_category(restaurant_id,name) VALUES(?,'家常')", a);
    category =
        jdbc.queryForObject(
            "SELECT id FROM menu_category WHERE restaurant_id=? AND name='家常'", Long.class, a);
  }

  private long user(long restaurant, boolean grant) {
    String openid = "menu_it_" + UUID.randomUUID();
    jdbc.update("INSERT INTO app_user(openid) VALUES(?)", openid);
    long id = jdbc.queryForObject("SELECT id FROM app_user WHERE openid=?", Long.class, openid);
    users.add(id);
    if (restaurant > 0)
      jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE id=?", id, restaurant);
    else if (grant)
      jdbc.update(
          "INSERT INTO daily_access_grant(user_id,secret_version,grant_generation,issued_at,expires_at) SELECT ?,secret_version,grant_generation,UTC_TIMESTAMP(3),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY) FROM daily_access_secret WHERE id=1",
          id);
    return id;
  }

  private String session(long id) {
    String token = Tokens.random(), key = SessionStore.key(token);
    redis.opsForValue().set(key, Long.toString(id), Duration.ofMinutes(5));
    keys.add(key);
    return token;
  }

  @AfterEach
  void cleanup() {
    redis.delete(keys);
    tx.executeWithoutResult(
        status -> {
          for (long restaurant : List.of(a, b)) {
            jdbc.update(
                "DELETE o FROM dish_spec_option o JOIN dish_spec_dimension s ON s.id=o.dimension_id JOIN dish d ON d.id=s.dish_id WHERE d.restaurant_id=?",
                restaurant);
            jdbc.update(
                "DELETE s FROM dish_spec_dimension s JOIN dish d ON d.id=s.dish_id WHERE d.restaurant_id=?",
                restaurant);
            jdbc.update(
                "DELETE m FROM dish_supply_month m JOIN dish d ON d.id=m.dish_id WHERE d.restaurant_id=?",
                restaurant);
            jdbc.update("DELETE FROM dish WHERE restaurant_id=?", restaurant);
            jdbc.update(
                "DELETE FROM menu_category WHERE restaurant_id=? AND category_type='NORMAL'",
                restaurant);
            jdbc.update("UPDATE restaurant SET chef_user_id=NULL WHERE id=?", restaurant);
          }
          jdbc.update("DELETE FROM daily_access_secret WHERE id=1");
          for (long user : users) {
            jdbc.update("DELETE FROM api_idempotency_record WHERE user_id=?", user);
            jdbc.update("DELETE FROM daily_access_grant WHERE user_id=?", user);
            jdbc.update("DELETE FROM app_user WHERE id=?", user);
          }
        });
  }

  private ResultActions send(MockHttpServletRequestBuilder request, String token, Object body)
      throws Exception {
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null)
      request.contentType("application/json").content(json.writeValueAsString(body));
    return mvc.perform(request);
  }

  private JsonNode data(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
  }

  private Map<String, Object> form(String name, long category) {
    var f = new HashMap<String, Object>();
    f.put("name", name);
    f.put("categoryId", category);
    f.put("recipe", "主厨秘密\n焖十分钟");
    f.put("introduction", "家的味道");
    f.put("onShelf", true);
    f.put("supplyMonths", List.of());
    f.put("dimensions", List.of());
    return f;
  }

  private long create(Map<String, Object> f) throws Exception {
    return data(send(
                post("/api/v1/chef/restaurants/" + a + "/dishes")
                    .header("Idempotency-Key", UUID.randomUUID().toString()),
                chef,
                f)
            .andExpect(status().isOk()))
        .get("id")
        .asLong();
  }

  private List<Map<String, Object>> specs() {
    return List.of(
        Map.of(
            "name",
            "辣度",
            "options",
            List.of(
                Map.of("name", "微辣", "isDefault", true),
                Map.of("name", "不辣", "isDefault", false))));
  }

  @Test
  void dailyAccessAndChefOwnershipProtectEveryRoute() throws Exception {
    send(get("/api/v1/restaurants"), null, null).andExpect(status().isUnauthorized());
    send(get("/api/v1/restaurants"), visitor, null).andExpect(status().isForbidden());
    send(get("/api/v1/restaurants"), guest, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2));
    send(get("/api/v1/chef/restaurants/" + a + "/menu"), other, null)
        .andExpect(status().isForbidden());
    send(put("/api/v1/chef/restaurants/" + a), other, Map.of("name", "偷改", "version", 0))
        .andExpect(status().isForbidden());
    long id = create(form("番茄炒蛋", category));
    send(put("/api/v1/chef/dishes/" + id), other, form("覆盖", category))
        .andExpect(status().isForbidden());
    send(delete("/api/v1/chef/dishes/" + id), guest, Map.of("version", 0))
        .andExpect(status().isForbidden());
    send(get("/api/v1/chef/dishes/" + id + "/recipe"), other, null)
        .andExpect(status().isForbidden());
    send(get("/api/v1/chef/dishes/" + id), guest, null).andExpect(status().isForbidden());
  }

  @Test
  void ownerCanRenameAndMoveDishBeforeDeletingCategory() throws Exception {
    send(put("/api/v1/chef/restaurants/" + a), chef, Map.of("name", "我们的厨房", "version", 0))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(1));
    send(put("/api/v1/chef/restaurants/" + a), chef, Map.of("name", "旧覆盖", "version", 0))
        .andExpect(status().isConflict());
    send(put("/api/v1/chef/categories/" + category), chef, Map.of("name", "拿手菜", "version", 0))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(1));
    send(put("/api/v1/chef/categories/" + category), chef, Map.of("name", "旧覆盖", "version", 0))
        .andExpect(status().isConflict());
    long target =
        data(send(
                    post("/api/v1/chef/restaurants/" + a + "/categories")
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                    chef,
                    Map.of("name", "汤羹"))
                .andExpect(status().isOk()))
            .get("id")
            .asLong();
    var f = form("番茄汤", category);
    long id = create(f);
    f.put("version", 0);
    f.put("categoryId", target);
    send(put("/api/v1/chef/dishes/" + id), chef, f).andExpect(status().isOk());
    send(delete("/api/v1/chef/categories/" + category), chef, Map.of("version", 1))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT category_id FROM dish WHERE id=?", Long.class, id))
        .isEqualTo(target);
    jdbc.update("UPDATE restaurant SET name='我的厨房',version=0 WHERE id=?", a);
  }

  @Test
  void customerProjectionNeverContainsRecipeAndSeasonalUsesSelectedDate() throws Exception {
    long regular = create(form("米饭", category));
    var f = form("桂花藕", seasonal);
    f.put("supplyMonths", List.of(10));
    long seasonalDish = create(f);
    var october =
        data(
            send(get("/api/v1/restaurants/" + a + "/menu?date=2026-10-01"), guest, null)
                .andExpect(status().isOk()));
    assertThat(october.get("dishes").size()).isEqualTo(2);
    assertThat(october.toString()).doesNotContain("recipe", "主厨秘密");
    var november =
        data(
            send(get("/api/v1/restaurants/" + a + "/menu?date=2026-11-01"), guest, null)
                .andExpect(status().isOk()));
    assertThat(november.get("dishes").size()).isEqualTo(1);
    assertThat(november.get("categories").size()).isEqualTo(1);
    send(
            get("/api/v1/restaurants/" + a + "/dishes/" + seasonalDish + "?date=2026-11-01"),
            guest,
            null)
        .andExpect(status().isNotFound());
    send(get("/api/v1/restaurants/" + b + "/dishes/" + regular), guest, null)
        .andExpect(status().isNotFound());
    send(get("/api/v1/chef/dishes/" + regular), chef, null)
        .andExpect(jsonPath("$.data.recipe").value("主厨秘密\n焖十分钟"));
  }

  @Test
  void duplicateCreationReplaysAndChangedPayloadConflicts() throws Exception {
    String route = "/api/v1/chef/restaurants/" + a + "/dishes", key = UUID.randomUUID().toString();
    var f = form("红烧肉", category);
    long first =
        data(send(post(route).header("Idempotency-Key", key), chef, f).andExpect(status().isOk()))
            .get("id")
            .asLong();
    assertThat(
            data(send(post(route).header("Idempotency-Key", key), chef, f)
                    .andExpect(status().isOk()))
                .get("id")
                .asLong())
        .isEqualTo(first);
    f.put("name", "清蒸鱼");
    send(post(route).header("Idempotency-Key", key), chef, f).andExpect(status().isConflict());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM dish WHERE restaurant_id=?", Integer.class, a))
        .isEqualTo(1);
  }

  @Test
  void duplicateNamesAndCrossStoreCategoryAreRejected() throws Exception {
    create(form("米饭", category));
    send(
            post("/api/v1/chef/restaurants/" + a + "/dishes")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            chef,
            form("米饭", category))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NAME_DUPLICATE"));
    long foreign =
        jdbc.queryForObject("SELECT id FROM menu_category WHERE restaurant_id=?", Long.class, b);
    send(
            post("/api/v1/chef/restaurants/" + a + "/dishes")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            chef,
            form("鱼", foreign))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shelfAndVersionRulesPreserveHistoryAndSoftDeleteAllowsFreshId() throws Exception {
    var f = form("米饭", category);
    long id = create(f);
    f.put("version", 0);
    f.put("onShelf", false);
    send(put("/api/v1/chef/dishes/" + id), chef, f).andExpect(status().isOk());
    send(put("/api/v1/chef/dishes/" + id), chef, f).andExpect(status().isConflict());
    send(get("/api/v1/restaurants/" + a + "/dishes/" + id), guest, null)
        .andExpect(status().isNotFound());
    send(delete("/api/v1/chef/categories/" + category), chef, Map.of("version", 0))
        .andExpect(status().isBadRequest());
    send(delete("/api/v1/chef/dishes/" + id), chef, Map.of("version", 0))
        .andExpect(status().isConflict());
    send(delete("/api/v1/chef/dishes/" + id), chef, Map.of("version", 1))
        .andExpect(status().isOk());
    assertThat(create(form("米饭", category))).isNotEqualTo(id);
    assertThat(
            jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM dish WHERE id=?", Boolean.class, id))
        .isTrue();
    send(get("/api/v1/chef/dishes/" + id), chef, null).andExpect(status().isNotFound());
  }

  @Test
  void builtinCategoryCannotChangeAndActiveCategoryNamesAreUnique() throws Exception {
    send(put("/api/v1/chef/categories/" + seasonal), chef, Map.of("name", "季节", "version", 0))
        .andExpect(status().isBadRequest());
    send(delete("/api/v1/chef/categories/" + seasonal), chef, Map.of("version", 0))
        .andExpect(status().isBadRequest());
    send(
            post("/api/v1/chef/restaurants/" + a + "/categories")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            chef,
            Map.of("name", "家常"))
        .andExpect(status().isConflict());
    send(delete("/api/v1/chef/categories/" + category), chef, Map.of("version", 0))
        .andExpect(status().isOk());
    send(
            post("/api/v1/chef/restaurants/" + a + "/categories")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            chef,
            Map.of("name", "家常"))
        .andExpect(status().isOk());
  }

  @Test
  void supplyValidationIsAtomicAndMovingToRegularClearsMonths() throws Exception {
    String route = "/api/v1/chef/restaurants/" + a + "/dishes";
    var f = form("莲藕", seasonal);
    send(post(route).header("Idempotency-Key", UUID.randomUUID().toString()), chef, f)
        .andExpect(status().isBadRequest());
    f.put("supplyMonths", List.of(13));
    send(post(route).header("Idempotency-Key", UUID.randomUUID().toString()), chef, f)
        .andExpect(status().isBadRequest());
    f.put("supplyMonths", List.of(1, 1));
    send(post(route).header("Idempotency-Key", UUID.randomUUID().toString()), chef, f)
        .andExpect(status().isBadRequest());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM dish WHERE restaurant_id=?", Integer.class, a))
        .isZero();
    f.put("supplyMonths", List.of(10));
    long id = create(f);
    f.put("version", 0);
    f.put("categoryId", category);
    f.put("supplyMonths", List.of());
    send(put("/api/v1/chef/dishes/" + id), chef, f)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.dish.supplyMonths.length()").value(0));
  }

  @Test
  void defaultsSwapWithoutUniqueCollisionAndExistingIdsStayStable() throws Exception {
    var f = form("面条", category);
    f.put("dimensions", specs());
    long id = create(f);
    var dimensions =
        data(send(get("/api/v1/chef/dishes/" + id), chef, null)).get("dish").get("dimensions");
    long dimension = dimensions.get(0).get("id").asLong(),
        one = dimensions.get(0).get("options").get(0).get("id").asLong(),
        two = dimensions.get(0).get("options").get(1).get("id").asLong();
    f.put("version", 0);
    f.put(
        "dimensions",
        List.of(
            Map.of(
                "id",
                dimension,
                "name",
                "辣度",
                "options",
                List.of(
                    Map.of("id", one, "name", "不辣", "isDefault", false),
                    Map.of("id", two, "name", "微辣", "isDefault", true)))));
    var result = data(send(put("/api/v1/chef/dishes/" + id), chef, f).andExpect(status().isOk()));
    assertThat(result.get("dish").get("dimensions").get(0).get("id").asLong()).isEqualTo(dimension);
    assertThat(
            jdbc.queryForObject(
                "SELECT id FROM dish_spec_option WHERE dimension_id=? AND deleted_at IS NULL AND is_default=1",
                Long.class,
                dimension))
        .isEqualTo(two);
  }

  @Test
  void badSpecsRollbackDishAndForeignSpecIdsCannotBeReused() throws Exception {
    var f = form("面条", category);
    f.put(
        "dimensions",
        List.of(
            Map.of(
                "name",
                "辣度",
                "options",
                List.of(
                    Map.of("name", "a", "isDefault", true),
                    Map.of("name", "b", "isDefault", true)))));
    send(
            post("/api/v1/chef/restaurants/" + a + "/dishes")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            chef,
            f)
        .andExpect(status().isBadRequest());
    f.put(
        "dimensions",
        List.of(
            Map.of(
                "name",
                "辣度",
                "options",
                List.of(
                    Map.of("name", "a", "isDefault", true),
                    Map.of("name", "A", "isDefault", false)))));
    send(
            post("/api/v1/chef/restaurants/" + a + "/dishes")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            chef,
            f)
        .andExpect(status().isConflict());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM dish WHERE restaurant_id=?", Integer.class, a))
        .isZero();
    f.put("dimensions", specs());
    long first = create(f);
    var foreign =
        data(send(get("/api/v1/chef/dishes/" + first), chef, null)).get("dish").get("dimensions");
    var second = form("饺子", category);
    long secondId = create(second);
    second.put("version", 0);
    second.put("dimensions", foreign);
    send(put("/api/v1/chef/dishes/" + secondId), chef, second).andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT version FROM dish WHERE id=?", Long.class, secondId))
        .isZero();
  }

  @Test
  void limitsAndTextStorageAreValidatedBeforeWriting() throws Exception {
    for (int i = 0; i < 28; i++)
      jdbc.update("INSERT INTO menu_category(restaurant_id,name) VALUES(?,?)", a, "分类" + i);
    send(
            post("/api/v1/chef/restaurants/" + a + "/categories")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            chef,
            Map.of("name", "超额"))
        .andExpect(status().isBadRequest());
    var f = form("超过二十字".repeat(5), category);
    send(
            post("/api/v1/chef/restaurants/" + a + "/dishes")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            chef,
            f)
        .andExpect(status().isBadRequest());
    f = form("👩‍🍳".repeat(20), category);
    f.put("recipe", "做".repeat(500));
    long id = create(f);
    assertThat(
            jdbc.queryForObject(
                "SELECT CHAR_LENGTH(recipe) FROM dish WHERE id=?", Integer.class, id))
        .isEqualTo(500);
    for (int i = 0; i < 499; i++)
      jdbc.update(
          "INSERT INTO dish(restaurant_id,category_id,name) VALUES(?,?,?)", a, category, "菜" + i);
    send(
            post("/api/v1/chef/restaurants/" + a + "/dishes")
                .header("Idempotency-Key", UUID.randomUUID().toString()),
            chef,
            form("第501道", category))
        .andExpect(status().isBadRequest());
  }
}
