package com.myhome.table.core;

import static org.assertj.core.api.Assertions.*;

import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.IdempotencyService;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_INTEGRATION", matches = "true")
class IdempotencyConcurrencyIntegrationTest {
  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) throws Exception {
    IdentityIntegrationTest.settings(registry);
  }

  @Autowired IdempotencyService idempotency;
  @Autowired JdbcTemplate jdbc;
  long user;

  public record Result(int count) {}

  @BeforeEach
  void prepare() {
    String openid = "concurrent_" + UUID.randomUUID();
    jdbc.update("INSERT INTO app_user(openid) VALUES(?)", openid);
    user = jdbc.queryForObject("SELECT id FROM app_user WHERE openid=?", Long.class, openid);
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM biz_operation_log WHERE actor_user_id=?", user);
    jdbc.update("DELETE FROM api_idempotency_record WHERE user_id=?", user);
    jdbc.update("DELETE FROM app_user WHERE id=?", user);
  }

  @Test
  void concurrentDuplicateHasOneDurableSideEffect() throws Exception {
    var calls = new AtomicInteger();
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    Callable<Result> action =
        () -> {
          start.await();
          return idempotency.execute(
              user,
              "core:test",
              "same-request-0001",
              "same-body",
              Result.class,
              () -> {
                int count = calls.incrementAndGet();
                jdbc.update(
                    "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id) VALUES('TEST',1,'ONCE',?)",
                    user);
                return new Result(count);
              });
        };
    try {
      Future<Result> first = pool.submit(action), second = pool.submit(action);
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(new Result(1));
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(new Result(1));
      assertThat(calls.get()).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM biz_operation_log WHERE actor_user_id=?",
                  Integer.class,
                  user))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void changedBodyCannotReuseKey() {
    idempotency.execute(
        user, "core:test", "same-request-0002", "body-a", Result.class, () -> new Result(1));
    assertThatThrownBy(
            () ->
                idempotency.execute(
                    user,
                    "core:test",
                    "same-request-0002",
                    "body-b",
                    Result.class,
                    () -> new Result(2)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void failureRollsBackBusinessAndIdempotencyTogether() {
    assertThatThrownBy(
            () ->
                idempotency.execute(
                    user,
                    "core:test",
                    "rollback-key-0001",
                    "body",
                    Result.class,
                    () -> {
                      jdbc.update(
                          "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id) VALUES('TEST',1,'ROLLBACK',?)",
                          user);
                      throw new IllegalStateException("Simulated transaction failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_operation_log WHERE actor_user_id=?",
                Integer.class,
                user))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_idempotency_record WHERE user_id=?", Integer.class, user))
        .isZero();
  }
}
