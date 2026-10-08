package com.myhome.table.common.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.support.*;

class HomeStatisticsCacheTest {
  public record Value(int count) {}

  private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private final HomeStatisticsCache cache = new HomeStatisticsCache(redis, new ObjectMapper());

  HomeStatisticsCacheTest() {
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString()))
        .thenAnswer(i -> store.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
    when(values.get(anyString())).thenAnswer(i -> store.get(i.getArgument(0)));
    doAnswer(
            i -> {
              store.put(i.getArgument(0), i.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString());
    doAnswer(
            i -> {
              store.put(i.getArgument(0), i.getArgument(1));
              return null;
            })
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void cacheHitAndCorruptPayloadFallback() {
    assertThat(cache.read("family", Value.class, () -> new Value(2)).count()).isEqualTo(2);
    assertThat(
            cache
                .read(
                    "family",
                    Value.class,
                    () -> {
                      throw new AssertionError("No reload on hit");
                    })
                .count())
        .isEqualTo(2);
    String key =
        store.keySet().stream()
            .filter(k -> k.startsWith("core:home:stats:"))
            .findFirst()
            .orElseThrow();
    store.put(key, "broken");
    assertThat(cache.read("family", Value.class, () -> new Value(3)).count()).isEqualTo(3);
    verify(values, atLeastOnce()).set(eq(key), anyString(), eq(Duration.ofSeconds(60)));
  }

  @Test
  void lateOldLoadCannotOverwriteNewGeneration() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    try {
      var old =
          pool.submit(
              () ->
                  cache.read(
                      "family",
                      Value.class,
                      () -> {
                        started.countDown();
                        try {
                          release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          throw new RuntimeException(e);
                        }
                        return new Value(1);
                      }));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      cache.invalidateAfterCommit();
      assertThat(cache.read("family", Value.class, () -> new Value(2)).count()).isEqualTo(2);
      release.countDown();
      assertThat(old.get(5, TimeUnit.SECONDS).count()).isEqualTo(1);
      assertThat(cache.read("family", Value.class, () -> new Value(9)).count()).isEqualTo(2);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void redisFailureFallsBackOnceAndDoesNotSwallowBusinessErrors() {
    when(values.setIfAbsent(anyString(), anyString()))
        .thenThrow(new IllegalStateException("offline"));
    var calls = new AtomicInteger();
    assertThat(
            cache
                .read(
                    "family",
                    Value.class,
                    () -> {
                      calls.incrementAndGet();
                      return new Value(4);
                    })
                .count())
        .isEqualTo(4);
    assertThat(calls.get()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                cache.read(
                    "family",
                    Value.class,
                    () -> {
                      throw new IllegalArgumentException("business");
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("business");
  }

  @Test
  void invalidationWaitsForCommitAndRollbackKeepsGeneration() {
    cache.read("family", Value.class, () -> new Value(1));
    String generation = store.get("core:home:generation");
    TransactionSynchronizationManager.initSynchronization();
    try {
      cache.invalidateAfterCommit();
      assertThat(store.get("core:home:generation")).isEqualTo(generation);
      for (var sync : TransactionSynchronizationManager.getSynchronizations())
        sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(store.get("core:home:generation")).isEqualTo(generation);
    TransactionSynchronizationManager.initSynchronization();
    try {
      cache.invalidateAfterCommit();
      for (var sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(store.get("core:home:generation")).isNotEqualTo(generation);
  }
}
