package com.myhome.table.common.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 公共缓存工具：提交后更换代次，旧查询即使迟到回写也不会污染新代次。 */
@Component
public class HomeStatisticsCache {
  private static final Logger LOG = LoggerFactory.getLogger(HomeStatisticsCache.class);
  private static final String GENERATION = "core:home:generation";
  private final StringRedisTemplate redis;
  private final ObjectMapper json;

  public HomeStatisticsCache(StringRedisTemplate redis, ObjectMapper json) {
    this.redis = redis;
    this.json = json;
  }

  public <T> T read(String scope, Class<T> type, Supplier<T> loader) {
    String key = null;
    try {
      redis.opsForValue().setIfAbsent(GENERATION, UUID.randomUUID().toString());
      String generation = redis.opsForValue().get(GENERATION);
      if (generation != null) key = "core:home:stats:" + generation + ":" + scope;
      String cached = key == null ? null : redis.opsForValue().get(key);
      if (cached != null) {
        try {
          return json.readValue(cached, type);
        } catch (Exception ignored) {
          /* 不可信/旧结构缓存回源，不吞掉业务异常。 */
        }
      }
    } catch (RuntimeException unavailable) {
      return loader.get();
    }
    T value = loader.get();
    if (key == null) return value;
    try {
      redis.opsForValue().set(key, json.writeValueAsString(value), Duration.ofSeconds(60));
    } catch (Exception ignored) {
      LOG.warn("首页统计缓存写入不可用，已返回数据库结果");
    }
    return value;
  }

  public void invalidateAfterCommit() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      invalidate();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            invalidate();
          }
        });
  }

  private void invalidate() {
    try {
      redis.opsForValue().set(GENERATION, UUID.randomUUID().toString());
    } catch (RuntimeException unavailable) {
      LOG.warn("首页统计缓存失效暂不可用，旧数据最多保留60秒");
    }
  }
}
