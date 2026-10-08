package com.myhome.table.core;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.myhome.table.common.security.SessionStore;
import com.myhome.table.common.util.Tokens;
import com.myhome.table.core.domain.WechatGateway;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_INTEGRATION", matches = "true")
class IdentityIntegrationTest {
  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) throws Exception {
    Properties p = new Properties();
    try (var in = Files.newInputStream(Path.of(System.getenv("OUR_TABLE_TEST_CONFIG")))) {
      p.load(in);
    }
    if (!p.getProperty("spring.datasource.url", "").contains(":13306/myhome_it?"))
      throw new IllegalStateException("Integration tests require isolated database");
    p.forEach((k, v) -> registry.add(k.toString(), () -> v.toString()));
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper json;
  @MockitoBean WechatGateway wechat;
  private final List<String> keys = new ArrayList<>();
  private long chef, guest;
  private String chefToken, guestToken;

  @BeforeEach
  void prepare() {
    String prefix = "it_" + UUID.randomUUID();
    chef = create(prefix + "c");
    guest = create(prefix + "g");
    jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE restaurant_code='RESTAURANT_A'", chef);
    chefToken = session(chef);
    guestToken = session(guest);
  }

  private long create(String openid) {
    jdbc.update("INSERT INTO app_user(openid) VALUES(?)", openid);
    return jdbc.queryForObject("SELECT id FROM app_user WHERE openid=?", Long.class, openid);
  }

  private String session(long id) {
    String token = Tokens.random();
    String key = SessionStore.key(token);
    redis.opsForValue().set(key, Long.toString(id), Duration.ofMinutes(5));
    keys.add(key);
    return token;
  }

  @AfterEach
  void cleanRedis() {
    keys.add("core:passcode:fail:" + guest);
    keys.add("core:passcode:lock:" + guest);
    redis.delete(keys);
  }

  private ResultActions send(String verb, String url, String token, String body) throws Exception {
    var request =
        switch (verb) {
          case "PUT" -> put(url);
          case "PATCH" -> put(url);
          default -> post(url);
        };
    if (token != null) request.header("Authorization", "Bearer " + token);
    return mvc.perform(request.contentType("application/json").content(body));
  }

  private void initSecret() throws Exception {
    send(
            "PUT",
            "/api/v1/chef/access/passcode",
            chefToken,
            "{\"passcode\":\"Abc123\",\"version\":0}")
        .andExpect(status().isOk());
  }

  private JsonNode data(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
  }

  @Test
  void migratedSchemaContains28TablesAndFixedRestaurants() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'",
                Integer.class))
        .isEqualTo(28);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM restaurant", Integer.class)).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM menu_category WHERE category_type='SEASONAL'", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void unauthorizedRequestsDoNotLeakData() throws Exception {
    mvc.perform(get("/api/v1/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());
    mvc.perform(
            get("/api/v1/chef/access/passcode?reveal=true")
                .header("Authorization", "Bearer " + guestToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void stableWechatIdentityRegistersOnlyOnce() throws Exception {
    String openid = "wx_" + UUID.randomUUID();
    when(wechat.exchange(anyString())).thenReturn(new WechatGateway.Identity(openid, null));
    var a =
        data(
            send(
                    "POST",
                    "/api/v1/auth/wechat/login",
                    null,
                    "{\"code\":\"one\",\"nickname\":\"小厨师\"}")
                .andExpect(status().isOk()));
    keys.add(SessionStore.key(a.get("token").asText()));
    var b =
        data(
            send(
                    "POST",
                    "/api/v1/auth/wechat/login",
                    null,
                    "{\"code\":\"two\",\"nickname\":\"其他昵称\"}")
                .andExpect(status().isOk()));
    keys.add(SessionStore.key(b.get("token").asText()));
    assertThat(a.get("user").get("id")).isEqualTo(b.get("user").get("id"));
    assertThat(b.get("user").get("nickname").asText()).isEqualTo("小厨师");
    assertThat(a.toString()).doesNotContain(openid, "session_key");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE openid=?", Integer.class, openid))
        .isEqualTo(1);
  }

  @Test
  void grantLasts168HoursAndDoesNotSlide() throws Exception {
    initSecret();
    Instant before = Instant.now();
    var a =
        data(
            send("POST", "/api/v1/access/passcode/verify", guestToken, "{\"passcode\":\"Abc123\"}")
                .andExpect(status().isOk()));
    var b =
        data(
            send("POST", "/api/v1/access/passcode/verify", guestToken, "{\"passcode\":\"Abc123\"}")
                .andExpect(status().isOk()));
    assertThat(Instant.parse(a.get("expiresAt").asText()))
        .isBetween(
            before.plus(Duration.ofHours(168)).minusMillis(1),
            Instant.now().plus(Duration.ofHours(168)));
    assertThat(Instant.parse(a.get("expiresAt").asText()))
        .isCloseTo(
            Instant.parse(b.get("expiresAt").asText()),
            within(1, java.time.temporal.ChronoUnit.MILLIS));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM daily_access_grant WHERE user_id=?", Integer.class, guest))
        .isEqualTo(1);
  }

  @Test
  void fifthFailureLocksIdentityAcrossSessions() throws Exception {
    initSecret();
    for (int i = 0; i < 4; i++)
      send("POST", "/api/v1/access/passcode/verify", guestToken, "{\"passcode\":\"Wrong1\"}")
          .andExpect(status().isForbidden());
    send("POST", "/api/v1/access/passcode/verify", guestToken, "{\"passcode\":\"Wrong1\"}")
        .andExpect(status().isLocked());
    send("POST", "/api/v1/access/passcode/verify", session(guest), "{\"passcode\":\"Abc123\"}")
        .andExpect(status().isLocked());
    assertThat(redis.getExpire("core:passcode:lock:" + guest)).isBetween(590L, 600L);
  }

  @Test
  void changePreservesGrantAndRevokeIsImmediateAndIdempotent() throws Exception {
    initSecret();
    send("POST", "/api/v1/access/passcode/verify", guestToken, "{\"passcode\":\"Abc123\"}")
        .andExpect(status().isOk());
    send(
            "PUT",
            "/api/v1/chef/access/passcode",
            chefToken,
            "{\"passcode\":\"New123\",\"version\":0}")
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/access/status").header("Authorization", "Bearer " + guestToken))
        .andExpect(jsonPath("$.data.dailyAllowed").value(true));
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/v1/chef/access/grants/revoke-all")
                  .header("Authorization", "Bearer " + chefToken)
                  .header("Idempotency-Key", "test-revoke-key-0001")
                  .contentType("application/json")
                  .content("{\"version\":1,\"confirmed\":true}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.generation").value(2));
    mvc.perform(get("/api/v1/access/status").header("Authorization", "Bearer " + guestToken))
        .andExpect(jsonPath("$.data.dailyAllowed").value(false));
    mvc.perform(get("/api/v1/access/status").header("Authorization", "Bearer " + chefToken))
        .andExpect(jsonPath("$.data.dailyAllowed").value(true));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_operation_log WHERE operation_type='REVOKE_GRANTS' AND actor_user_id=?",
                Integer.class,
                chef))
        .isEqualTo(1);
  }

  @Test
  void nicknameUpdateUsesVersionAndPrivateIdentityIsHidden() throws Exception {
    send("PATCH", "/api/v1/users/me/profile", guestToken, "{\"nickname\":\"一起吃饭\",\"version\":0}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(1));
    send("PATCH", "/api/v1/users/me/profile", guestToken, "{\"nickname\":\"覆盖\",\"version\":0}")
        .andExpect(status().isConflict());
    var r =
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + guestToken))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(r).doesNotContain("openid", "unionid");
  }

  @Test
  void uniqueIndexPreventsSameChefInTwoRestaurants() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE restaurant SET chef_user_id=? WHERE restaurant_code='RESTAURANT_B'",
                    chef))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  @Test
  void staleSecretCannotOverwriteAndViewIsAudited() throws Exception {
    initSecret();
    send(
            "PUT",
            "/api/v1/chef/access/passcode",
            chefToken,
            "{\"passcode\":\"New123\",\"version\":12}")
        .andExpect(status().isConflict());
    mvc.perform(
            get("/api/v1/chef/access/passcode?reveal=true")
                .header("Authorization", "Bearer " + chefToken))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.passcode").value("Abc123"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_operation_log WHERE operation_type='VIEW_SECRET' AND actor_user_id=?",
                Integer.class,
                chef))
        .isEqualTo(1);
  }

  @Test
  void logoutRevokesToken() throws Exception {
    send("POST", "/api/v1/auth/logout", guestToken, "{}").andExpect(status().isOk());
    mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + guestToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unicodeNicknameFitsStorageAndIdentityIsCaseSensitive() throws Exception {
    String nickname = "👩‍🍳".repeat(20);
    send(
            "PATCH",
            "/api/v1/users/me/profile",
            guestToken,
            json.writeValueAsString(Map.of("nickname", nickname, "version", 0)))
        .andExpect(status().isOk());
    String identity = "case_" + UUID.randomUUID();
    long a = create(identity + "A"), b = create(identity + "a");
    assertThat(a).isNotEqualTo(b);
  }
}
