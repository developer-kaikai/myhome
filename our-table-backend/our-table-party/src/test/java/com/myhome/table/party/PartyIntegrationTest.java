package com.myhome.table.party;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.security.*;
import com.myhome.table.common.util.Tokens;
import com.myhome.table.party.application.PartyService;
import com.myhome.table.party.domain.PartyModels.*;
import java.nio.file.*;
import java.sql.*;
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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_INTEGRATION", matches = "true")
class PartyIntegrationTest {
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
  @Autowired ObjectMapper json;
  @Autowired StringRedisTemplate redis;
  @Autowired PartyService service;
  @MockitoBean Clock clock;
  private final List<Long> users = new ArrayList<>();
  private final List<String> sessions = new ArrayList<>();
  private Instant now;
  private long chef, guest, outsider, restaurant;
  private String ownerToken, guestToken, outsiderToken;

  @BeforeEach
  void setup() {
    now = Instant.parse("2026-10-05T03:00:00Z");
    when(clock.instant()).thenAnswer(i -> now);
    chef = user();
    guest = user();
    outsider = user();
    restaurant =
        jdbc.queryForObject(
            "SELECT id FROM restaurant WHERE restaurant_code='RESTAURANT_A'", Long.class);
    jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE id=?", chef, restaurant);
    ownerToken = session(chef);
    guestToken = session(guest);
    outsiderToken = session(outsider);
  }

  long insert(String sql, Object... args) {
    var h = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
          return s;
        },
        h);
    return h.getKey().longValue();
  }

  long user() {
    long id =
        insert(
            "INSERT INTO app_user(openid,nickname) VALUES(?,?)",
            "party_it_" + UUID.randomUUID(),
            "朋友👩🏽‍🍳");
    users.add(id);
    return id;
  }

  String session(long id) {
    String t = Tokens.random();
    sessions.add(SessionStore.key(t));
    redis.opsForValue().set(SessionStore.key(t), Long.toString(id), Duration.ofMinutes(10));
    return t;
  }

  @AfterEach
  void clean() {
    for (long u : users) {
      var ids = jdbc.queryForList("SELECT id FROM party WHERE creator_user_id=?", Long.class, u);
      for (long id : ids) {
        jdbc.update(
            "DELETE FROM biz_operation_log WHERE business_type='PARTY' AND business_id=?", id);
        jdbc.update("DELETE FROM party_purchase_item WHERE party_id=?", id);
        jdbc.update("DELETE FROM party WHERE id=?", id);
      }
    }
    jdbc.update("UPDATE restaurant SET chef_user_id=NULL WHERE id=?", restaurant);
    for (long u : users) {
      jdbc.update("DELETE FROM api_idempotency_record WHERE user_id=?", u);
      jdbc.update("DELETE FROM daily_access_grant WHERE user_id=?", u);
      jdbc.update("DELETE FROM app_user WHERE id=?", u);
    }
    for (String k : sessions) redis.delete(k);
  }

  JsonNode call(MockHttpServletRequestBuilder b, String t, int status) throws Exception {
    return json.readTree(
            mvc.perform(b.header("Authorization", "Bearer " + t))
                .andExpect(status().is(status))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data");
  }

  String body(Create f) throws Exception {
    return json.writeValueAsString(f);
  }

  Create form() {
    return new Create("周末👩🏽‍🍳聚会", "朋友家", now.plusSeconds(3600), now.plusSeconds(7200), false);
  }

  JsonNode create() throws Exception {
    return call(
        post("/api/v1/parties")
            .contentType("application/json")
            .content(body(form()))
            .header("Idempotency-Key", Tokens.random()),
        ownerToken,
        200);
  }

  String share(long id) throws Exception {
    return call(get("/api/v1/parties/" + id + "/share"), ownerToken, 200).path("token").asText();
  }

  JsonNode change(long id, long version, String action, String t, int expected) throws Exception {
    return call(
        put("/api/v1/parties/" + id)
            .contentType("application/json")
            .content(json.writeValueAsString(new Change(version, action)))
            .header("Idempotency-Key", Tokens.random()),
        t,
        expected);
  }

  JsonNode join(String invitation, String t, int expected) throws Exception {
    return call(
        post("/api/v1/party-invitations/" + invitation + "/join")
            .header("Idempotency-Key", Tokens.random()),
        t,
        expected);
  }

  @Test
  void authAndCreationBoundary() throws Exception {
    mvc.perform(get("/api/v1/my/parties")).andExpect(status().isUnauthorized());
    call(
        post("/api/v1/parties")
            .contentType("application/json")
            .content(body(form()))
            .header("Idempotency-Key", Tokens.random()),
        guestToken,
        403);
    var c = create();
    assertThat(c.path("memberCount").asInt()).isEqualTo(1);
    assertThat(c.path("creator").asBoolean()).isTrue();
    assertThat(c.path("theme").asText()).contains("👩🏽‍🍳");
    assertThat(c.path("members").get(0).path("name").asText()).contains("👩🏽‍🍳");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_order", Integer.class)).isZero();
    call(get("/api/v1/parties/" + c.path("id").asLong()), outsiderToken, 404);
    call(get("/api/v1/my/parties?state=ALL"), guestToken, 400);
  }

  @Test
  void datesLengthAndLongDuration() throws Exception {
    for (Create f :
        List.of(
            new Create("x", "y", now.minusSeconds(1), now.plusSeconds(1), false),
            new Create("x", "y", now, now, false),
            new Create("x".repeat(21), "y", now, now.plusSeconds(1), false))) {
      call(
          post("/api/v1/parties")
              .contentType("application/json")
              .content(body(f))
              .header("Idempotency-Key", Tokens.random()),
          ownerToken,
          400);
    }
    var f = new Create("长聚会", "这里", now.plusSeconds(1), now.plus(Duration.ofDays(8)), false);
    call(
        post("/api/v1/parties")
            .contentType("application/json")
            .content(body(f))
            .header("Idempotency-Key", Tokens.random()),
        ownerToken,
        409);
    f = new Create(f.theme(), f.location(), f.startAt(), f.plannedEndAt(), true);
    call(
        post("/api/v1/parties")
            .contentType("application/json")
            .content(body(f))
            .header("Idempotency-Key", Tokens.random()),
        ownerToken,
        200);
  }

  @Test
  void idempotentCreateAndTokenAtRest() throws Exception {
    String key = Tokens.random(), f = body(form());
    var c =
        call(
            post("/api/v1/parties")
                .contentType("application/json")
                .content(f)
                .header("Idempotency-Key", key),
            ownerToken,
            200);
    now = now.plus(Duration.ofDays(2));
    var replay =
        call(
            post("/api/v1/parties")
                .contentType("application/json")
                .content(f)
                .header("Idempotency-Key", key),
            ownerToken,
            200);
    assertThat(replay).isEqualTo(c);
    String token = share(c.path("id").asLong());
    assertThat(share(c.path("id").asLong())).isEqualTo(token);
    var db =
        jdbc.queryForMap(
            "SELECT token_hash,token_ciphertext FROM party_invitation WHERE party_id=?",
            c.path("id").asLong());
    assertThat(db.get("token_hash")).isEqualTo(Tokens.sha256(token));
    assertThat(
            new String(
                (byte[]) db.get("token_ciphertext"), java.nio.charset.StandardCharsets.UTF_8))
        .doesNotContain(token);
    assertThat(
            jdbc.queryForObject(
                "SELECT response_json FROM api_idempotency_record WHERE user_id=? AND idempotency_key=?",
                String.class,
                chef,
                key))
        .doesNotContain(token);
  }

  @Test
  void invitationSummaryAndUniqueRegistration() throws Exception {
    long id = create().path("id").asLong();
    String t = share(id);
    var preview = call(get("/api/v1/party-invitations/" + t), guestToken, 200);
    assertThat(preview.path("members").size()).isZero();
    assertThat(preview.path("memberCount").isNull()).isTrue();
    assertThat(preview.path("canJoin").asBoolean()).isTrue();
    var joined = join(t, guestToken, 200);
    assertThat(joined.path("memberCount").asInt()).isEqualTo(2);
    assertThat(joined.path("dailyAllowed").asBoolean()).isFalse();
    assertThat(join(t, guestToken, 200).path("memberCount").asInt()).isEqualTo(2);
    assertThat(call(get("/api/v1/my/parties"), guestToken, 200).path("parties").size())
        .isEqualTo(1);
    call(get("/api/v1/parties/" + id + "/share"), guestToken, 200);
    change(id, 1, "END", guestToken, 403);
  }

  @Test
  void manualEndReopenDisabledAndReplacement() throws Exception {
    long id = create().path("id").asLong();
    String t = share(id);
    var end = change(id, 0, "END", ownerToken, 200);
    assertThat(end.path("firstEndedAt").asText()).isNotBlank();
    join(t, guestToken, 409);
    change(id, 1, "DISABLE_INVITATION", ownerToken, 409);
    change(id, 1, "REGENERATE_INVITATION", ownerToken, 409);
    assertThat(
            call(get("/api/v1/party-invitations/" + t), guestToken, 200)
                .path("canJoin")
                .asBoolean())
        .isFalse();
    now = now.plusSeconds(10);
    var reopened = change(id, 1, "REOPEN", ownerToken, 200);
    assertThat(reopened.path("firstEndedAt")).isEqualTo(end.path("firstEndedAt"));
    join(t, guestToken, 200);
    change(id, 3, "DISABLE_INVITATION", ownerToken, 200);
    var oldMemberCard = call(get("/api/v1/party-invitations/" + t), guestToken, 200);
    assertThat(oldMemberCard.path("members").size()).isEqualTo(2);
    call(get("/api/v1/party-invitations/" + t), outsiderToken, 404);
    join(t, outsiderToken, 404);
    call(get("/api/v1/parties/" + id), guestToken, 200);
    change(id, 4, "REGENERATE_INVITATION", ownerToken, 200);
    String next = share(id);
    assertThat(next).isNotEqualTo(t);
    join(t, outsiderToken, 404);
    join(next, outsiderToken, 200);
    change(id, 6, "END", ownerToken, 200);
    assertThat(call(get("/api/v1/my/parties?state=ENDED"), guestToken, 200).path("parties").size())
        .isEqualTo(1);
  }

  @Test
  void expiredDailyGrantStillAllowsExistingCreator() throws Exception {
    jdbc.update("UPDATE restaurant SET chef_user_id=NULL WHERE id=?", restaurant);
    jdbc.update(
        "INSERT INTO daily_access_secret(id,secret_hash,secret_ciphertext,secret_nonce,encryption_key_version) VALUES(1,'party-fixture','test','000000000000','v1')");
    try {
      jdbc.update(
          "INSERT INTO daily_access_grant(user_id,secret_version,grant_generation,issued_at,expires_at) VALUES(?,1,1,?,?)",
          chef,
          Timestamp.from(now),
          Timestamp.from(now.plusSeconds(60)));
      long id = create().path("id").asLong();
      now = now.plusSeconds(61);
      change(id, 0, "END", ownerToken, 200);
      change(id, 1, "REOPEN", ownerToken, 200);
      call(
          post("/api/v1/parties")
              .contentType("application/json")
              .content(body(form()))
              .header("Idempotency-Key", Tokens.random()),
          ownerToken,
          403);
    } finally {
      jdbc.update("DELETE FROM daily_access_grant WHERE user_id=?", chef);
      jdbc.update("DELETE FROM daily_access_secret WHERE id=1");
    }
  }

  @Test
  void exitPreservesPurchasedPaymentAndAllowsRejoin() throws Exception {
    long id = create().path("id").asLong();
    String t = share(id);
    join(t, guestToken, 200);
    jdbc.update(
        "INSERT INTO party_purchase_item(party_id,item_name,creator_user_id,assignee_user_id) VALUES(?,'食材',?,?)",
        id,
        chef,
        guest);
    change(id, 1, "EXIT", guestToken, 409);
    jdbc.update(
        "UPDATE party_purchase_item SET purchase_status='PURCHASED',payer_user_id=?,amount=20 WHERE party_id=?",
        guest,
        id);
    var exit = change(id, 1, "EXIT", guestToken, 200);
    assertThat(exit.path("memberCount").asInt()).isEqualTo(1);
    call(get("/api/v1/parties/" + id), guestToken, 200);
    call(get("/api/v1/parties/" + id + "/share"), guestToken, 404);
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM party_purchase_item WHERE party_id=?",
                java.math.BigDecimal.class,
                id))
        .isEqualByComparingTo("20");
    assertThat(join(t, guestToken, 200).path("memberCount").asInt()).isEqualTo(2);
    change(id, 3, "EXIT", ownerToken, 409);
  }

  @Test
  void versionConflictAndNoAutomaticEnd() throws Exception {
    long id = create().path("id").asLong();
    now = now.plus(Duration.ofDays(3));
    assertThat(call(get("/api/v1/parties/" + id), ownerToken, 200).path("status").asText())
        .isEqualTo("ACTIVE");
    change(id, 100, "END", ownerToken, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_operation_log WHERE business_type='PARTY' AND business_id=? AND operation_type='END'",
                Integer.class,
                id))
        .isZero();
    change(id, 0, "END", ownerToken, 200);
  }

  @Test
  void concurrentRegistrationCannotExceedCapacity() throws Exception {
    long id = create().path("id").asLong();
    String t = share(id);
    for (int i = 0; i < 98; i++) {
      long u = user();
      jdbc.update(
          "INSERT INTO party_member(party_id,user_id,display_name_snapshot,joined_at) VALUES(?,?,?,?)",
          id,
          u,
          "成员",
          Timestamp.from(now));
    }
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var results = new ArrayList<Future<Boolean>>();
      for (long u : List.of(guest, outsider))
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    service.join(new UserContext(u, null, null), t, Tokens.random());
                    return true;
                  } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("PARTY_FULL");
                    return false;
                  }
                }));
      gate.countDown();
      int successes = 0;
      for (var f : results) if (f.get(10, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM party_member WHERE party_id=? AND member_status='JOINED'",
                  Integer.class,
                  id))
          .isEqualTo(100);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentSameUserJoinRemainsOneMember() throws Exception {
    long id = create().path("id").asLong();
    String t = share(id);
    var pool = Executors.newFixedThreadPool(3);
    try {
      var jobs = new ArrayList<Callable<Detail>>();
      for (int i = 0; i < 3; i++)
        jobs.add(() -> service.join(new UserContext(guest, null, null), t, Tokens.random()));
      for (var f : pool.invokeAll(jobs)) assertThat(f.get().memberCount()).isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM biz_operation_log WHERE business_type='PARTY' AND business_id=? AND operation_type='JOIN'",
                  Integer.class,
                  id))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void strictMutationContract() throws Exception {
    long id = create().path("id").asLong();
    for (String body :
        List.of(
            "{\"version\":0.5,\"action\":\"END\"}",
            "{\"version\":0,\"action\":\"UNKNOWN\"}",
            "{\"version\":0,\"action\":\"END\",\"creator\":true}",
            "{\"action\":\"END\"}")) {
      call(
          put("/api/v1/parties/" + id)
              .contentType("application/json")
              .content(body)
              .header("Idempotency-Key", Tokens.random()),
          ownerToken,
          400);
    }
    assertThat(call(get("/api/v1/parties/" + id), ownerToken, 200).path("status").asText())
        .isEqualTo("ACTIVE");
  }

  JsonNode add(long party, String name, Long assignee, String token, int expected)
      throws Exception {
    var body = new HashMap<String, Object>();
    body.put("name", name);
    body.put("quantity", "2斤");
    body.put("assigneeId", assignee);
    return call(
        post("/api/v1/parties/" + party + "/items")
            .contentType("application/json")
            .content(json.writeValueAsString(body))
            .header("Idempotency-Key", Tokens.random()),
        token,
        expected);
  }

  JsonNode purchaseChange(
      long party,
      JsonNode item,
      String action,
      Map<String, Object> fields,
      String token,
      int expected)
      throws Exception {
    var body = new HashMap<String, Object>(fields);
    body.put("version", item.path("version").asLong());
    body.put("action", action);
    return call(
        put("/api/v1/parties/" + party + "/items/" + item.path("id").asLong())
            .contentType("application/json")
            .content(json.writeValueAsString(body))
            .header("Idempotency-Key", Tokens.random()),
        token,
        expected);
  }

  JsonNode purchases(long party, String token) throws Exception {
    return call(get("/api/v1/parties/" + party + "/items"), token, 200);
  }

  @Test
  void purchasePrivacyAssignmentsAndEditingPermissions() throws Exception {
    long id = create().path("id").asLong();
    String invite = share(id);
    call(get("/api/v1/parties/" + id + "/items"), guestToken, 404);
    join(invite, guestToken, 200);
    join(invite, outsiderToken, 200);
    add(id, "牛肉", outsider, guestToken, 403);
    add(id, "👩🏽‍🍳".repeat(20), null, guestToken, 200);
    add(id, "名".repeat(21), null, ownerToken, 400);
    var item = add(id, "水果", guest, ownerToken, 200);
    purchaseChange(id, item, "ASSIGN", Map.of("assigneeId", outsider), guestToken, 403);
    purchaseChange(id, item, "EDIT", Map.of("name", "偷偷改", "quantity", "1"), outsiderToken, 403);
    var edited =
        purchaseChange(id, item, "EDIT", Map.of("name", "橙子", "quantity", "3斤"), guestToken, 200);
    purchaseChange(id, edited, "RELEASE", Map.of(), guestToken, 200);
    var created = add(id, "饮料", null, guestToken, 200);
    var claimed = purchaseChange(id, created, "CLAIM", Map.of(), outsiderToken, 200);
    purchaseChange(id, claimed, "REMOVE", Map.of(), guestToken, 403);
  }

  @Test
  void purchaseIdempotencyAndConcurrentClaimHaveOneWinner() throws Exception {
    long id = create().path("id").asLong();
    String invite = share(id);
    join(invite, guestToken, 200);
    join(invite, outsiderToken, 200);
    String key = Tokens.random();
    String body = "{\"name\":\"杯子\"}";
    var request =
        post("/api/v1/parties/" + id + "/items")
            .contentType("application/json")
            .content(body)
            .header("Idempotency-Key", key);
    var item = call(request, ownerToken, 200);
    assertThat(call(request, ownerToken, 200)).isEqualTo(item);
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var tasks = new ArrayList<Future<Integer>>();
      for (String token : List.of(guestToken, outsiderToken))
        tasks.add(
            pool.submit(
                () -> {
                  gate.await();
                  return mvc.perform(
                          put("/api/v1/parties/" + id + "/items/" + item.path("id").asLong())
                              .contentType("application/json")
                              .content("{\"version\":0,\"action\":\"CLAIM\"}")
                              .header("Authorization", "Bearer " + token)
                              .header("Idempotency-Key", Tokens.random()))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      gate.countDown();
      assertThat(List.of(tasks.get(0).get(), tasks.get(1).get()))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(purchases(id, ownerToken).path("summary").path("itemCount").asInt()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_operation_log WHERE business_id=? AND operation_type='PURCHASE_CLAIM'",
                Integer.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void purchaseMoneyNeverRoundsInputAndZeroRequiresExplanation() throws Exception {
    long id = create().path("id").asLong();
    var item = add(id, "食材", chef, ownerToken, 200);
    for (String invalid : List.of("-1", "NaN", "1.999", "100000", "1e2", ".5", "00.10", " 1 "))
      purchaseChange(
          id, item, "PURCHASE", Map.of("amount", invalid, "payerId", chef), ownerToken, 400);
    purchaseChange(id, item, "PURCHASE", Map.of("amount", "0", "payerId", chef), ownerToken, 400);
    var zero =
        purchaseChange(
            id,
            item,
            "PURCHASE",
            Map.of("amount", "0.00", "payerId", chef, "zeroNote", "家里已有"),
            ownerToken,
            200);
    assertThat(zero.path("amount").asText()).isEqualTo("0.00");
    var max =
        purchaseChange(
            id,
            zero,
            "PURCHASE",
            Map.of("amount", "99999.99", "payerId", chef, "reason", "金额修正"),
            ownerToken,
            200);
    assertThat(max.path("amount").asText()).isEqualTo("99999.99");
    assertThat(purchases(id, ownerToken).path("summary").path("total").asText())
        .isEqualTo("99999.99");
  }

  @Test
  void purchasesCalculateReferenceAndPreserveExitedPayers() throws Exception {
    long id = create().path("id").asLong();
    String invite = share(id);
    join(invite, guestToken, 200);
    join(invite, outsiderToken, 200);
    var item = add(id, "烧烤", guest, guestToken, 200);
    purchaseChange(
        id, item, "PURCHASE", Map.of("amount", "100", "payerId", guest), guestToken, 200);
    var summary = purchases(id, ownerToken).path("summary");
    assertThat(summary.path("total").asText()).isEqualTo("100.00");
    assertThat(summary.path("perPerson").asText()).isEqualTo("33.33");
    long v = call(get("/api/v1/parties/" + id), guestToken, 200).path("version").asLong();
    change(id, v, "EXIT", guestToken, 200);
    var after = purchases(id, guestToken);
    assertThat(after.path("canAdd").asBoolean()).isFalse();
    assertThat(after.path("summary").path("perPerson").asText()).isEqualTo("50.00");
    assertThat(after.path("summary").path("advances").findValues("amount"))
        .contains(json.readTree("\"100.00\""));
    assertThat(after.path("summary").path("advances").findValuesAsText("status"))
        .contains("EXITED");
    jdbc.update("UPDATE party_member SET member_status='EXITED' WHERE party_id=?", id);
    assertThat(purchases(id, ownerToken).path("summary").path("perPerson").isNull()).isTrue();
  }

  @Test
  void payerCorrectionAndRevertRemovalPreserveAudit() throws Exception {
    long id = create().path("id").asLong();
    join(share(id), guestToken, 200);
    var item = add(id, "牛肉", guest, ownerToken, 200);
    var bought =
        purchaseChange(
            id, item, "PURCHASE", Map.of("amount", "20.50", "payerId", guest), guestToken, 200);
    purchaseChange(
        id,
        bought,
        "PURCHASE",
        Map.of("amount", "21", "payerId", chef, "reason", "改为主厨垫付"),
        guestToken,
        403);
    purchaseChange(
        id, bought, "PURCHASE", Map.of("amount", "21", "payerId", chef), ownerToken, 400);
    var assigned =
        purchaseChange(id, bought, "ASSIGN", Map.of("assigneeId", chef), ownerToken, 200);
    assertThat(assigned.path("payerId").asLong()).isEqualTo(guest);
    var corrected =
        purchaseChange(
            id,
            assigned,
            "PURCHASE",
            Map.of("amount", "21", "payerId", chef, "reason", "实际由主厨垫付"),
            ownerToken,
            200);
    purchaseChange(id, corrected, "REMOVE", Map.of("reason", "误填"), ownerToken, 400);
    var reverted =
        purchaseChange(
            id, corrected, "REVERT", Map.of("reason", "退货", "confirmed", true), ownerToken, 200);
    assertThat(reverted.path("amount").isNull()).isTrue();
    assertThat(purchases(id, ownerToken).path("summary").path("total").asText()).isEqualTo("0.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM party_purchase_item WHERE id=?",
                String.class,
                item.path("id").asLong()))
        .isEqualTo("21.00");
    var rebought =
        purchaseChange(
            id, reverted, "PURCHASE", Map.of("amount", "3", "payerId", chef), ownerToken, 200);
    purchaseChange(
        id, rebought, "REMOVE", Map.of("reason", "重复记录", "confirmed", true), ownerToken, 200);
    assertThat(purchases(id, ownerToken).path("summary").path("itemCount").asInt()).isZero();
    call(get("/api/v1/parties/" + id + "/items/" + item.path("id").asLong()), ownerToken, 404);
    String audit =
        jdbc.queryForObject(
            "SELECT before_json FROM biz_operation_log WHERE business_id=? AND operation_type='PURCHASE_REVERT'",
            String.class,
            id);
    assertThat(json.readTree(audit).path("amount").decimalValue()).isEqualByComparingTo("21.00");
  }

  @Test
  void endedPurchaseIsReadOnlyUntilReopenedAndStaleVersionIsRejected() throws Exception {
    long id = create().path("id").asLong();
    var item = add(id, "蔬菜", chef, ownerToken, 200);
    var edited =
        purchaseChange(id, item, "EDIT", Map.of("name", "青菜", "quantity", "1斤"), ownerToken, 200);
    purchaseChange(id, item, "PURCHASE", Map.of("amount", "2", "payerId", chef), ownerToken, 409);
    var detail = call(get("/api/v1/parties/" + id), ownerToken, 200);
    var ended = change(id, detail.path("version").asLong(), "END", ownerToken, 200);
    add(id, "新物品", null, ownerToken, 409);
    purchaseChange(id, edited, "PURCHASE", Map.of("amount", "2", "payerId", chef), ownerToken, 409);
    assertThat(purchases(id, ownerToken).path("items").get(0).path("canPurchase").asBoolean())
        .isFalse();
    change(id, ended.path("version").asLong(), "REOPEN", ownerToken, 200);
    purchaseChange(id, edited, "PURCHASE", Map.of("amount", "2", "payerId", chef), ownerToken, 200);
  }

  @Test
  void purchaseCapacityPaginationAndScopeCannotBeBypassed() throws Exception {
    long id = create().path("id").asLong();
    for (int n = 0; n < 500; n++)
      jdbc.update(
          "INSERT INTO party_purchase_item(party_id,item_name,creator_user_id) VALUES(?,?,?)",
          id,
          "物品" + n,
          chef);
    add(id, "超出容量", null, ownerToken, 409);
    var listing = purchases(id, ownerToken);
    assertThat(listing.path("items").size()).isEqualTo(20);
    assertThat(listing.path("hasMore").asBoolean()).isTrue();
    assertThat(listing.path("summary").path("itemCount").asInt()).isEqualTo(500);
    var last = call(get("/api/v1/parties/" + id + "/items?page=25"), ownerToken, 200);
    assertThat(last.path("hasMore").asBoolean()).isFalse();
    call(get("/api/v1/parties/" + id + "/items?page=26"), ownerToken, 400);
    long other = create().path("id").asLong();
    call(
        get(
            "/api/v1/parties/"
                + other
                + "/items/"
                + listing.path("items").get(0).path("id").asLong()),
        ownerToken,
        404);
    purchaseChange(id, listing.path("items").get(0), "REMOVE", Map.of(), ownerToken, 200);
    add(id, "腾出空间", null, ownerToken, 200);
  }

  JsonNode editInfo(long id, Edit f, String token, int expected) throws Exception {
    return call(
        put("/api/v1/parties/" + id + "/info")
            .contentType("application/json")
            .content(json.writeValueAsString(f))
            .header("Idempotency-Key", Tokens.random()),
        token,
        expected);
  }

  JsonNode detail(long id) throws Exception {
    return call(get("/api/v1/parties/" + id), ownerToken, 200);
  }

  Edit editForm(JsonNode p, String theme) {
    return new Edit(
        p.path("version").asLong(),
        theme,
        p.path("location").asText(),
        Instant.parse(p.path("startAt").asText()),
        Instant.parse(p.path("plannedEndAt").asText()),
        false);
  }

  JsonNode removeMember(long id, long user, long version, String token, int expected)
      throws Exception {
    return call(
        put("/api/v1/parties/" + id + "/members/" + user)
            .contentType("application/json")
            .content(json.writeValueAsString(new RemoveMember(version, "误报名👩🏽‍🍳")))
            .header("Idempotency-Key", Tokens.random()),
        token,
        expected);
  }

  @Test
  void editCreatorOnlyAndVersionWithAudit() throws Exception {
    var p = create();
    long id = p.path("id").asLong();
    var f = editForm(p, "新主题👩🏽‍🍳");
    editInfo(id, f, outsiderToken, 404);
    join(share(id), guestToken, 200);
    p = detail(id);
    f = editForm(p, "新主题👩🏽‍🍳");
    editInfo(id, f, guestToken, 403);
    var updated = editInfo(id, f, ownerToken, 200);
    assertThat(updated.path("theme").asText()).isEqualTo(f.theme());
    assertThat(updated.path("version").asLong()).isEqualTo(f.version() + 1);
    editInfo(id, f, ownerToken, 409);
    String log =
        jdbc.queryForObject(
            "SELECT summary_json FROM biz_operation_log WHERE business_id=? AND operation_type='EDIT_INFO'",
            String.class,
            id);
    assertThat(json.readTree(log).path("before").path("theme").asText())
        .isEqualTo(p.path("theme").asText());
    assertThat(json.readTree(log).path("after").path("theme").asText()).isEqualTo(f.theme());
    assertThat(updated.path("userId").asLong()).isEqualTo(chef);
    assertThat(updated.path("members").get(0).path("canRemove").asBoolean()).isFalse();
    assertThat(updated.path("members").get(1).path("canRemove").asBoolean()).isTrue();
  }

  @Test
  void editDatesRespectStartedAndManualReopenedHistory() throws Exception {
    var p = create();
    long id = p.path("id").asLong();
    now = now.plus(Duration.ofDays(2));
    p = editInfo(id, editForm(p, "时间过去仍可改主题"), ownerToken, 200);
    var original = editForm(p, "保留开始");
    editInfo(
        id,
        new Edit(original.version(), "x", "y", now.plusSeconds(1), now.plusSeconds(2), false),
        ownerToken,
        409);
    editInfo(
        id,
        new Edit(original.version(), "x", "y", original.startAt(), now.minusSeconds(1), false),
        ownerToken,
        400);
    p = change(id, original.version(), "END", ownerToken, 200);
    editInfo(id, editForm(p, "结束不可改"), ownerToken, 409);
    p = change(id, p.path("version").asLong(), "REOPEN", ownerToken, 200);
    var edited = editInfo(id, editForm(p, "补充聚会"), ownerToken, 200);
    assertThat(edited.path("startAt")).isEqualTo(p.path("startAt"));
    assertThat(edited.path("plannedEndAt")).isEqualTo(p.path("plannedEndAt"));
    assertThat(edited.path("firstEndedAt")).isEqualTo(p.path("firstEndedAt"));
  }

  @Test
  void editLongDurationAndValidationDoNotMutateOnFailure() throws Exception {
    var p = create();
    long id = p.path("id").asLong();
    var f = editForm(p, "调整时间");
    var longEnd = f.startAt().plus(Duration.ofDays(8));
    editInfo(id, new Edit(f.version(), "x", "y", f.startAt(), longEnd, false), ownerToken, 409);
    editInfo(
        id,
        new Edit(f.version(), "x".repeat(21), "y", f.startAt(), f.plannedEndAt(), true),
        ownerToken,
        400);
    editInfo(
        id,
        new Edit(f.version(), "x", "y", now.minusSeconds(1), f.plannedEndAt(), true),
        ownerToken,
        400);
    assertThat(detail(id).path("version")).isEqualTo(p.path("version"));
    p =
        editInfo(
            id, new Edit(f.version(), "长聚会", "y", f.startAt(), longEnd, true), ownerToken, 200);
    editInfo(id, editForm(p, "只改文字无需再次确认"), ownerToken, 200);
  }

  @Test
  void editUnknownResultReplaysAfterTimeAndLaterChanges() throws Exception {
    var p = create();
    long id = p.path("id").asLong();
    String key = Tokens.random();
    var f = editForm(p, "同次保存");
    String body = json.writeValueAsString(f);
    var first =
        call(
            put("/api/v1/parties/" + id + "/info")
                .contentType("application/json")
                .content(body)
                .header("Idempotency-Key", key),
            ownerToken,
            200);
    change(id, first.path("version").asLong(), "END", ownerToken, 200);
    now = now.plus(Duration.ofDays(2));
    var replay =
        call(
            put("/api/v1/parties/" + id + "/info")
                .contentType("application/json")
                .content(body)
                .header("Idempotency-Key", key),
            ownerToken,
            200);
    assertThat(replay).isEqualTo(first);
    assertThat(detail(id).path("status").asText()).isEqualTo("ENDED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_operation_log WHERE business_id=? AND operation_type='EDIT_INFO'",
                Integer.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void removeMembershipPermissionsAndEndedBoundary() throws Exception {
    var p = create();
    long id = p.path("id").asLong();
    join(share(id), guestToken, 200);
    p = detail(id);
    removeMember(id, guest, p.path("version").asLong(), outsiderToken, 404);
    removeMember(id, guest, p.path("version").asLong(), guestToken, 403);
    removeMember(id, chef, p.path("version").asLong(), ownerToken, 409);
    removeMember(id, outsider, p.path("version").asLong(), ownerToken, 409);
    removeMember(id, guest, 0, ownerToken, 409);
    p = change(id, p.path("version").asLong(), "END", ownerToken, 200);
    removeMember(id, guest, p.path("version").asLong(), ownerToken, 409);
    assertThat(detail(id).path("memberCount").asInt()).isEqualTo(2);
  }

  @Test
  void removeBlocksTodoThenPreservesPaidAndRotatesInvitation() throws Exception {
    long id = create().path("id").asLong();
    String old = share(id);
    join(old, guestToken, 200);
    var item = add(id, "牛肉", guest, ownerToken, 200);
    removeMember(id, guest, detail(id).path("version").asLong(), ownerToken, 409);
    assertThat(share(id)).isEqualTo(old);
    purchaseChange(
        id, item, "PURCHASE", Map.of("amount", "90.00", "payerId", guest), guestToken, 200);
    var before = purchases(id, ownerToken);
    assertThat(before.path("summary").path("perPerson").asText()).isEqualTo("45.00");
    long version = detail(id).path("version").asLong();
    var result = removeMember(id, guest, version, ownerToken, 200);
    assertThat(result.path("memberCount").asInt()).isEqualTo(1);
    var after = purchases(id, guestToken);
    assertThat(after.path("summary").path("total").asText()).isEqualTo("90.00");
    assertThat(after.path("summary").path("perPerson").asText()).isEqualTo("90.00");
    assertThat(after.path("canAdd").asBoolean()).isFalse();
    assertThat(after.path("summary").path("advances").get(1).path("amount").asText())
        .isEqualTo("90.00");
    call(get("/api/v1/parties/" + id + "/share"), guestToken, 404);
    join(old, guestToken, 404);
    join(old, outsiderToken, 404);
    assertThat(
            call(get("/api/v1/party-invitations/" + old), guestToken, 200)
                .path("canJoin")
                .asBoolean())
        .isFalse();
    String next = share(id);
    assertThat(next).isNotEqualTo(old);
    join(next, guestToken, 200);
    assertThat(
            jdbc.queryForObject(
                "SELECT removed_reason FROM party_member WHERE party_id=? AND user_id=?",
                String.class,
                id,
                guest))
        .isNull();
    String audit =
        jdbc.queryForObject(
            "SELECT summary_json FROM biz_operation_log WHERE business_id=? AND operation_type='REMOVE_MEMBER'",
            String.class,
            id);
    assertThat(audit).contains("误报名👩🏽‍🍳").doesNotContain(old).doesNotContain(next);
  }

  @Test
  void removalIdempotencyCannotRemoveRejoinedMemberAgain() throws Exception {
    long id = create().path("id").asLong();
    join(share(id), guestToken, 200);
    String key = Tokens.random(),
        body =
            json.writeValueAsString(new RemoveMember(detail(id).path("version").asLong(), "误报名"));
    var first =
        call(
            put("/api/v1/parties/" + id + "/members/" + guest)
                .contentType("application/json")
                .content(body)
                .header("Idempotency-Key", key),
            ownerToken,
            200);
    String next = share(id);
    join(next, guestToken, 200);
    var replay =
        call(
            put("/api/v1/parties/" + id + "/members/" + guest)
                .contentType("application/json")
                .content(body)
                .header("Idempotency-Key", key),
            ownerToken,
            200);
    assertThat(replay).isEqualTo(first);
    assertThat(detail(id).path("memberCount").asInt()).isEqualTo(2);
    assertThat(share(id)).isEqualTo(next);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_operation_log WHERE business_id=? AND operation_type='REMOVE_MEMBER'",
                Integer.class,
                id))
        .isEqualTo(1);
  }

  @Test
  void removalAndOldInvitationJoinSerialize() throws Exception {
    long id = create().path("id").asLong();
    String old = share(id);
    join(old, guestToken, 200);
    long version = detail(id).path("version").asLong();
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var removal =
          pool.submit(
              () -> {
                start.await();
                return service.removeMember(
                    new UserContext(chef, restaurant, null),
                    id,
                    guest,
                    new RemoveMember(version, "误报名"),
                    Tokens.random());
              });
      var joining =
          pool.submit(
              () -> {
                start.await();
                try {
                  service.join(new UserContext(guest, null, null), old, Tokens.random());
                  return true;
                } catch (ApiException e) {
                  assertThat(e.code()).isEqualTo("PARTY_UNAVAILABLE");
                  return false;
                }
              });
      start.countDown();
      removal.get(10, TimeUnit.SECONDS);
      joining.get(10, TimeUnit.SECONDS);
      assertThat(detail(id).path("memberCount").asInt()).isEqualTo(1);
      join(old, guestToken, 404);
    } finally {
      pool.shutdownNow();
    }
  }

  JsonNode createCover(String code, int expected) throws Exception {
    var f = form();
    return call(
        post("/api/v1/parties")
            .contentType("application/json")
            .content(
                body(
                    new Create(
                        f.theme(), f.location(), f.startAt(), f.plannedEndAt(), false, code)))
            .header("Idempotency-Key", Tokens.random()),
        ownerToken,
        expected);
  }

  Edit coverEdit(JsonNode p, String code) {
    var f = editForm(p, p.path("theme").asText());
    return new Edit(
        f.version(), f.theme(), f.location(), f.startAt(), f.plannedEndAt(), false, code);
  }

  @Test
  void presetCoverDefaultSelectedPreviewListAndHistory() throws Exception {
    var original = create();
    assertThat(original.path("coverPreset").asText()).isEqualTo("DEFAULT");
    var selected = createCover("TABLE", 200);
    long id = selected.path("id").asLong();
    assertThat(selected.path("coverPreset").asText()).isEqualTo("TABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT ma.object_key FROM party p JOIN media_asset ma ON ma.id=p.cover_media_id WHERE p.id=?",
                String.class,
                id))
        .isEqualTo("builtin/party/table-v1.png");
    var preview = call(get("/api/v1/party-invitations/" + share(id)), outsiderToken, 200);
    assertThat(preview.path("coverPreset").asText()).isEqualTo("TABLE");
    assertThat(preview.path("members").isEmpty()).isTrue();
    var ended = change(id, selected.path("version").asLong(), "END", ownerToken, 200);
    createCover("HOT_POT", 200);
    var history = call(get("/api/v1/my/parties?state=ENDED"), ownerToken, 200);
    assertThat(history.path("parties").get(0).path("coverPreset").asText()).isEqualTo("TABLE");
    assertThat(detail(id).path("coverPreset")).isEqualTo(ended.path("coverPreset"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_asset WHERE usage_type='PARTY_PRESET' AND owner_user_id IS NULL AND status='ACTIVE'",
                Integer.class))
        .isEqualTo(3);
  }

  @Test
  void presetRejectsUnlistedAndUnavailableMediaAndFallback() throws Exception {
    for (String invalid :
        List.of("", "table", "../table", "https://example.com/a.png", "builtin/party/table-v1.png"))
      createCover(invalid, 400);
    long media =
        jdbc.queryForObject(
            "SELECT id FROM media_asset WHERE object_key='builtin/party/tea-v1.png'", Long.class);
    long id = createCover("TEA", 200).path("id").asLong();
    try {
      jdbc.update("UPDATE media_asset SET status='QUARANTINED' WHERE id=?", media);
      createCover("TEA", 409);
      assertThat(detail(id).path("coverPreset").asText()).isEqualTo("DEFAULT");
      assertThat(
              call(get("/api/v1/my/parties"), ownerToken, 200)
                  .path("parties")
                  .get(0)
                  .path("coverPreset")
                  .asText())
          .isEqualTo("DEFAULT");
      assertThat(jdbc.queryForObject("SELECT cover_media_id FROM party WHERE id=?", Long.class, id))
          .isEqualTo(media);
      jdbc.update(
          "UPDATE media_asset SET status='ACTIVE',owner_user_id=? WHERE id=?", guest, media);
      createCover("TEA", 409);
    } finally {
      jdbc.update("UPDATE media_asset SET status='ACTIVE',owner_user_id=NULL WHERE id=?", media);
    }
    assertThat(detail(id).path("coverPreset").asText()).isEqualTo("TEA");
  }

  @Test
  void presetEditIsVersionedPreservesLegacyAndAuditsSelection() throws Exception {
    var p = createCover("TABLE", 200);
    long id = p.path("id").asLong();
    join(share(id), guestToken, 200);
    p = detail(id);
    editInfo(id, coverEdit(p, "TEA"), guestToken, 403);
    p = editInfo(id, coverEdit(p, "TEA"), ownerToken, 200);
    assertThat(p.path("coverPreset").asText()).isEqualTo("TEA");
    var log =
        json.readTree(
            jdbc.queryForObject(
                "SELECT summary_json FROM biz_operation_log WHERE business_id=? AND operation_type='EDIT_INFO' ORDER BY id DESC LIMIT 1",
                String.class,
                id));
    assertThat(log.path("before").path("coverPreset").asText()).isEqualTo("TABLE");
    assertThat(log.path("after").path("coverPreset").asText()).isEqualTo("TEA");
    var stale = p;
    p = editInfo(id, editForm(p, "旧版文字编辑保留封面"), ownerToken, 200);
    assertThat(p.path("coverPreset").asText()).isEqualTo("TEA");
    editInfo(id, coverEdit(stale, "TABLE"), ownerToken, 409);
    p = editInfo(id, coverEdit(p, "DEFAULT"), ownerToken, 200);
    assertThat(jdbc.queryForObject("SELECT cover_media_id FROM party WHERE id=?", Long.class, id))
        .isNull();
    p = change(id, p.path("version").asLong(), "END", ownerToken, 200);
    editInfo(id, coverEdit(p, "HOT_POT"), ownerToken, 409);
  }

  @Test
  void presetPendingRequestKeepsChosenCoverAndOldFingerprintCompatibility() throws Exception {
    var f = form();
    assertThat(f.toString())
        .isEqualTo(
            "Create[theme="
                + f.theme()
                + ", location="
                + f.location()
                + ", startAt="
                + f.startAt()
                + ", plannedEndAt="
                + f.plannedEndAt()
                + ", longDurationConfirmed=false]");
    var p = createCover("HOT_POT", 200);
    long id = p.path("id").asLong();
    var edit = coverEdit(p, "TEA");
    String body = json.writeValueAsString(edit), key = Tokens.random();
    var first =
        call(
            put("/api/v1/parties/" + id + "/info")
                .contentType("application/json")
                .content(body)
                .header("Idempotency-Key", key),
            ownerToken,
            200);
    var changed = editInfo(id, coverEdit(first, "TABLE"), ownerToken, 200);
    var replay =
        call(
            put("/api/v1/parties/" + id + "/info")
                .contentType("application/json")
                .content(body)
                .header("Idempotency-Key", key),
            ownerToken,
            200);
    assertThat(replay).isEqualTo(first);
    assertThat(detail(id).path("coverPreset").asText()).isEqualTo("TABLE");
    assertThat(detail(id).path("version")).isEqualTo(changed.path("version"));
    var legacy = editForm(changed, "仅修改文字");
    assertThat(legacy.toString())
        .isEqualTo(
            "Edit[version="
                + legacy.version()
                + ", theme="
                + legacy.theme()
                + ", location="
                + legacy.location()
                + ", startAt="
                + legacy.startAt()
                + ", plannedEndAt="
                + legacy.plannedEndAt()
                + ", longDurationConfirmed=false]");
  }
}
