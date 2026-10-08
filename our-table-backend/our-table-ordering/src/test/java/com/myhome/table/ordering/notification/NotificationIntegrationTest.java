package com.myhome.table.ordering.notification;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhome.table.common.security.*;
import com.myhome.table.common.util.Tokens;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = com.myhome.table.ordering.OrderingApplication.class)
@AutoConfigureMockMvc
@Import(NotificationIntegrationTest.Config.class)
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_INTEGRATION", matches = "true")
class NotificationIntegrationTest {
  static final String ORDER = "test_order_template_3988", REVIEW = "test_review_template_6633";

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
    registry.add("app.notifications.enabled", () -> true);
    registry.add("app.notifications.worker-enabled", () -> false);
    registry.add("app.notifications.activated-at", () -> "2026-10-01T00:00:00Z");
    registry.add("app.notifications.order-template-id", () -> ORDER);
    registry.add("app.notifications.review-template-id", () -> REVIEW);
    registry.add("app.notifications.order-time-policy", () -> "CUTOFF");
  }

  static class TestClock extends Clock {
    volatile Instant now = Instant.parse("2026-10-05T08:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return Clock.fixed(now, z);
    }

    public Instant instant() {
      return now;
    }
  }

  static class FakeGateway implements WechatMessageGateway {
    AtomicInteger calls = new AtomicInteger();
    volatile Result result = new Result("0", true, false, false);
    volatile Message message;
    volatile Runnable during = () -> {};

    public Result send(Message message) {
      this.message = message;
      calls.incrementAndGet();
      during.run();
      return result;
    }

    void reset() {
      calls.set(0);
      result = new Result("0", true, false, false);
      message = null;
      during = () -> {};
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }

    @Bean
    @Primary
    FakeGateway fakeGateway() {
      return new FakeGateway();
    }
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired StringRedisTemplate redis;
  @Autowired TestClock clock;
  @Autowired FakeGateway gateway;
  @Autowired NotificationDelivery delivery;
  @Autowired SubscriptionService subscriptions;
  long chef, friend, restaurant, order;
  String token;
  String redisKey;

  @BeforeEach
  void setup() {
    clock.now = Instant.parse("2026-10-05T08:00:00Z");
    gateway.reset();
    chef = insert("INSERT INTO app_user(openid,nickname) VALUES(?,'主厨')", Tokens.random());
    friend = insert("INSERT INTO app_user(openid,nickname) VALUES(?,'朋友')", Tokens.random());
    restaurant =
        jdbc.queryForObject(
            "SELECT id FROM restaurant WHERE restaurant_code='RESTAURANT_A'", Long.class);
    jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE id=?", chef, restaurant);
    order =
        insert(
            "INSERT INTO meal_order(restaurant_id,meal_date,meal_period,meal_slot,status,initiator_user_id,restaurant_name_snapshot,cutoff_at,submitted_at) VALUES(?,'2026-10-05','DINNER',202610053,'IN_PROGRESS',?,'我们的厨房',?,?)",
            restaurant,
            friend,
            Timestamp.from(clock.instant().plusSeconds(3600)),
            Timestamp.from(clock.instant()));
    jdbc.update(
        "INSERT INTO meal_order_participant(meal_order_id,user_id,display_name_snapshot,is_orderer) VALUES(?,?,'朋友',1)",
        order,
        friend);
    token = Tokens.random();
    redisKey = SessionStore.key(token);
    redis.opsForValue().set(redisKey, Long.toString(chef), Duration.ofMinutes(5));
  }

  long insert(String sql, Object... args) {
    return tx.execute(
        s -> {
          jdbc.update(sql, args);
          return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        });
  }

  UserContext chefContext() {
    return new UserContext(chef, restaurant, null);
  }

  void grant(long user, String event, int count) {
    jdbc.update(
        "INSERT INTO wx_subscription_grant(user_id,notification_template_id,latest_response,accepted_count,last_consented_at) SELECT ?,id,'ACCEPT',?,? FROM notification_template WHERE business_event=?",
        user,
        count,
        Timestamp.from(clock.instant()),
        event);
  }

  long event(String event, long recipient) {
    return insert(
        "INSERT INTO notification_outbox(event_key,event_type,business_type,business_id,recipient_user_id,payload_json,page_path,first_event_at,available_at) VALUES(?,?,'MEAL_ORDER',?,?,JSON_OBJECT('orderId',?),?,?,?)",
        Tokens.random(),
        event,
        order,
        recipient,
        order,
        "REVIEW_INVITED".equals(event)
            ? "subpackages/reviews/sheet?id=" + order
            : "pages/orders/detail?id=" + order,
        Timestamp.from(clock.instant()),
        Timestamp.from(clock.instant()));
  }

  String taskStatus(long id) {
    return jdbc.queryForObject(
        "SELECT status FROM notification_outbox WHERE id=?", String.class, id);
  }

  int count(long user, String name) {
    return jdbc.queryForObject(
        "SELECT " + name + " FROM wx_subscription_grant WHERE user_id=?", Integer.class, user);
  }

  void complete() {
    jdbc.update(
        "UPDATE meal_order SET status='COMPLETED',completed_at=? WHERE id=?",
        Timestamp.from(clock.instant()),
        order);
  }

  @AfterEach
  void cleanup() {
    redis.delete(redisKey);
    tx.executeWithoutResult(
        s -> {
          jdbc.update(
              "DELETE FROM notification_outbox WHERE recipient_user_id IN (?,?)", chef, friend);
          jdbc.update("DELETE FROM wx_subscription_grant WHERE user_id IN (?,?)", chef, friend);
          jdbc.update("DELETE FROM meal_order WHERE id=?", order);
          jdbc.update("UPDATE restaurant SET chef_user_id=NULL WHERE id=?", restaurant);
          jdbc.update("DELETE FROM api_idempotency_record WHERE user_id IN (?,?)", chef, friend);
          jdbc.update("DELETE FROM app_user WHERE id IN (?,?)", chef, friend);
        });
  }

  @Test
  void consentIsAuthenticatedAndReplayDoesNotAddQuota() throws Exception {
    var body = new SubscriptionService.Consent(chef, "ORDER_SUBMITTED", ORDER, "ACCEPT");
    String key = Tokens.random();
    mvc.perform(
            post("/api/v1/notifications/subscriptions")
                .contentType("application/json")
                .content(json.writeValueAsString(body))
                .header("Idempotency-Key", key))
        .andExpect(status().isUnauthorized());
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/v1/notifications/subscriptions")
                  .contentType("application/json")
                  .content(json.writeValueAsString(body))
                  .header("Authorization", "Bearer " + token)
                  .header("Idempotency-Key", key))
          .andExpect(status().isOk());
    assertThat(count(chef, "accepted_count")).isEqualTo(1);
    assertThatThrownBy(
            () ->
                subscriptions.consent(
                    chefContext(),
                    new SubscriptionService.Consent(friend, "ORDER_SUBMITTED", ORDER, "ACCEPT"),
                    Tokens.random()))
        .isInstanceOf(com.myhome.table.common.exception.ApiException.class);
  }

  @Test
  void guestCannotSubscribeChefTemplateOrUseWrongTemplate() {
    assertThatThrownBy(
            () ->
                subscriptions.consent(
                    new UserContext(friend, null, clock.instant().plusSeconds(3600)),
                    new SubscriptionService.Consent(friend, "ORDER_SUBMITTED", ORDER, "ACCEPT"),
                    Tokens.random()))
        .isInstanceOf(com.myhome.table.common.exception.ApiException.class);
    assertThatThrownBy(
            () ->
                subscriptions.consent(
                    chefContext(),
                    new SubscriptionService.Consent(chef, "ORDER_SUBMITTED", REVIEW, "ACCEPT"),
                    Tokens.random()))
        .isInstanceOf(com.myhome.table.common.exception.ApiException.class);
  }

  @Test
  void rejectionNeverBlocksBusinessOrAddsQuota() {
    subscriptions.consent(
        chefContext(),
        new SubscriptionService.Consent(chef, "ORDER_SUBMITTED", ORDER, "REJECT"),
        Tokens.random());
    assertThat(count(chef, "accepted_count")).isZero();
    assertThat(jdbc.queryForObject("SELECT status FROM meal_order WHERE id=?", String.class, order))
        .isEqualTo("IN_PROGRESS");
  }

  @Test
  void acceptedSendConsumesOneAndDoesNotReplay() {
    grant(chef, "ORDER_SUBMITTED", 1);
    long id = event("ORDER_SUBMITTED", chef);
    delivery.scan();
    delivery.scan();
    assertThat(taskStatus(id)).isEqualTo("SENT");
    assertThat(gateway.calls.get()).isEqualTo(1);
    assertThat(count(chef, "reserved_count")).isZero();
    assertThat(count(chef, "consumed_count")).isEqualTo(1);
    assertThat(gateway.message.data().keySet())
        .containsExactly("thing12", "thing41", "time9", "time57", "thing3");
    assertThat(gateway.message.page()).isEqualTo("pages/orders/detail?id=" + order);
  }

  @Test
  void noConsentSkipsAndLateConsentDoesNotReplay() {
    long id = event("ORDER_SUBMITTED", chef);
    delivery.scan();
    grant(chef, "ORDER_SUBMITTED", 1);
    delivery.scan();
    assertThat(taskStatus(id)).isEqualTo("SKIPPED");
    assertThat(gateway.calls.get()).isZero();
  }

  @Test
  void historicalEventsNeverFlushWithNewConsent() {
    grant(chef, "ORDER_SUBMITTED", 1);
    long id = event("ORDER_SUBMITTED", chef);
    jdbc.update(
        "UPDATE notification_outbox SET first_event_at='2026-09-30 23:59:59' WHERE id=?", id);
    delivery.scan();
    assertThat(taskStatus(id)).isEqualTo("SKIPPED");
    assertThat(count(chef, "consumed_count")).isZero();
  }

  @Test
  void cancellationAndChefChangeSkipOldRecipients() {
    grant(chef, "ORDER_SUBMITTED", 1);
    long id = event("ORDER_SUBMITTED", chef);
    jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE id=?", friend, restaurant);
    delivery.scan();
    assertThat(taskStatus(id)).isEqualTo("SKIPPED");
    jdbc.update("UPDATE restaurant SET chef_user_id=? WHERE id=?", chef, restaurant);
    long cancelled = event("ORDER_SUBMITTED", chef);
    jdbc.update("UPDATE meal_order SET status='CANCELLED' WHERE id=?", order);
    delivery.scan();
    assertThat(taskStatus(cancelled)).isEqualTo("SKIPPED");
    assertThat(gateway.calls.get()).isZero();
  }

  @Test
  void reviewsUseExactFieldsAndOnlyRecordedOrderers() {
    complete();
    grant(friend, "REVIEW_INVITED", 1);
    long id = event("REVIEW_INVITED", friend);
    delivery.scan();
    assertThat(taskStatus(id)).isEqualTo("SENT");
    assertThat(gateway.message.data().keySet())
        .containsExactly("thing6", "name4", "date2", "thing9");
    assertThat(gateway.message.data().get("date2").get("value")).isEqualTo("2026-10-05");
    grant(chef, "REVIEW_INVITED", 1);
    long fake = event("REVIEW_INVITED", chef);
    delivery.scan();
    assertThat(taskStatus(fake)).isEqualTo("SKIPPED");
  }

  @Test
  void reviewExpiresAtExactlySevenDays() {
    complete();
    grant(friend, "REVIEW_INVITED", 1);
    long id = event("REVIEW_INVITED", friend);
    clock.now = clock.now.plus(Duration.ofDays(7));
    delivery.scan();
    assertThat(taskStatus(id)).isEqualTo("SKIPPED");
    assertThat(gateway.calls.get()).isZero();
  }

  @Test
  void unknownSendIsNotRetriedAndReservesNoAdditionalCredit() {
    grant(chef, "ORDER_SUBMITTED", 2);
    long id = event("ORDER_SUBMITTED", chef);
    gateway.result = WechatMessageGateway.Result.unknown();
    delivery.scan();
    clock.now = clock.now.plusSeconds(600);
    delivery.scan();
    assertThat(taskStatus(id)).isEqualTo("FAILED_FINAL");
    assertThat(gateway.calls.get()).isEqualTo(1);
    assertThat(count(chef, "consumed_count")).isEqualTo(1);
    assertThat(count(chef, "reserved_count")).isZero();
  }

  @Test
  void definiteBusyRetriesAtMostThreeTimesWithNoCreditConsumption() {
    grant(chef, "ORDER_SUBMITTED", 1);
    long id = event("ORDER_SUBMITTED", chef);
    gateway.result = new WechatMessageGateway.Result("-1", false, true, false);
    for (int i = 0; i < 4; i++) {
      delivery.scan();
      clock.now = clock.now.plusSeconds(120);
    }
    assertThat(taskStatus(id)).isEqualTo("FAILED_FINAL");
    assertThat(gateway.calls.get()).isEqualTo(3);
    assertThat(count(chef, "accepted_count")).isEqualTo(1);
    assertThat(count(chef, "reserved_count")).isZero();
    assertThat(count(chef, "consumed_count")).isZero();
  }

  @Test
  void platformNoAuthorizationClearsOldAvailability() {
    grant(chef, "ORDER_SUBMITTED", 3);
    long id = event("ORDER_SUBMITTED", chef);
    gateway.result = new WechatMessageGateway.Result("43101", false, false, false);
    delivery.scan();
    assertThat(taskStatus(id)).isEqualTo("FAILED_FINAL");
    assertThat(count(chef, "accepted_count")).isZero();
  }

  @Test
  void lateOldRejectionDoesNotEraseNewConsent() {
    grant(chef, "ORDER_SUBMITTED", 1);
    event("ORDER_SUBMITTED", chef);
    gateway.result = new WechatMessageGateway.Result("43101", false, false, false);
    gateway.during =
        () ->
            subscriptions.consent(
                chefContext(),
                new SubscriptionService.Consent(chef, "ORDER_SUBMITTED", ORDER, "ACCEPT"),
                Tokens.random());
    delivery.scan();
    assertThat(count(chef, "accepted_count")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT latest_response FROM wx_subscription_grant WHERE user_id=?",
                String.class,
                chef))
        .isEqualTo("ACCEPT");
  }

  @Test
  void concurrentScansSendOnlyOnce() throws Exception {
    grant(chef, "ORDER_SUBMITTED", 1);
    long id = event("ORDER_SUBMITTED", chef);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(delivery::scan);
      var b = pool.submit(delivery::scan);
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(taskStatus(id)).isEqualTo("SENT");
    assertThat(gateway.calls.get()).isEqualTo(1);
  }

  @Test
  void staleSendingBecomesUnknownWithoutResending() {
    grant(chef, "ORDER_SUBMITTED", 1);
    long id = event("ORDER_SUBMITTED", chef);
    long template =
        jdbc.queryForObject(
            "SELECT id FROM notification_template WHERE business_event='ORDER_SUBMITTED'",
            Long.class);
    jdbc.update("UPDATE wx_subscription_grant SET reserved_count=1 WHERE user_id=?", chef);
    jdbc.update(
        "UPDATE notification_outbox SET status='SENDING',notification_template_id=?,attempt_token='abandoned',sending_started_at=?,subscription_version=0 WHERE id=?",
        template,
        Timestamp.from(clock.instant().minusSeconds(301)),
        id);
    delivery.scan();
    assertThat(taskStatus(id)).isEqualTo("FAILED_FINAL");
    assertThat(gateway.calls.get()).isZero();
    assertThat(count(chef, "consumed_count")).isEqualTo(1);
    delivery.finish(id, "abandoned", new WechatMessageGateway.Result("0", true, false, false));
    assertThat(count(chef, "consumed_count")).isEqualTo(1);
  }

  @Test
  void otherEventTypesDoNotReuseNewOrderTemplate() {
    grant(chef, "ORDER_SUBMITTED", 1);
    long id = event("MEAL_CONFIRM_DUE", chef);
    delivery.scan();
    assertThat(taskStatus(id)).isEqualTo("SKIPPED");
    assertThat(gateway.calls.get()).isZero();
  }
}
