package com.myhome.table.common.security;

import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.util.Tokens;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SessionStore {
  private final StringRedisTemplate redis;

  public SessionStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public Long resolve(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new ApiException(401, "AUTH_REQUIRED", "请先登录");
    String user = redis.opsForValue().get(key(token));
    if (user == null) throw new ApiException(401, "AUTH_REQUIRED", "登录已过期，请重新登录");
    return Long.valueOf(user);
  }

  public void revoke(String token) {
    redis.delete(key(token));
  }

  public static String key(String token) {
    return "core:sess:" + Tokens.sha256(token);
  }
}
