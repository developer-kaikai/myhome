package com.myhome.table.core.infrastructure;

import com.myhome.table.common.exception.ApiException;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class PasscodeLimiter {
  private final StringRedisTemplate redis;

  public PasscodeLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  private String fail(Long id) {
    return "core:passcode:fail:" + id;
  }

  private String lock(Long id) {
    return "core:passcode:lock:" + id;
  }

  private static final DefaultRedisScript<Long> FAILURE =
      new DefaultRedisScript<>(
          "if redis.call('EXISTS',KEYS[2])==1 then return 5 end local n=redis.call('INCR',KEYS[1]); redis.call('EXPIRE',KEYS[1],600); if n>=5 then redis.call('SET',KEYS[2],'1','EX',600); redis.call('DEL',KEYS[1]); end return n",
          Long.class);

  public void check(Long user) {
    if (Boolean.TRUE.equals(redis.hasKey(lock(user)))) throw locked();
  }

  public void failure(Long user) {
    Long n = redis.execute(FAILURE, List.of(fail(user), lock(user)));
    if (n == null) throw ApiException.unavailable();
    if (n >= 5) throw locked();
    throw new ApiException(403, "PASSCODE_INVALID", "密令不正确，请重新输入");
  }

  public void success(Long user) {
    redis.delete(fail(user));
  }

  private ApiException locked() {
    return new ApiException(423, "PASSCODE_LOCKED", "密令错误次数过多，请10分钟后再试");
  }
}
