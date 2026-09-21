package com.myhome.table.core.application;

import com.myhome.table.common.entity.AppUser;
import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.security.*;
import com.myhome.table.common.util.*;
import com.myhome.table.core.domain.WechatGateway;
import com.myhome.table.core.infrastructure.UserMapper;
import java.time.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdentityService {
  private final UserMapper users;
  private final WechatGateway wechat;
  private final StringRedisTemplate redis;
  private final TransactionTemplate tx;
  private final Clock clock;

  public IdentityService(
      UserMapper users,
      WechatGateway wechat,
      StringRedisTemplate redis,
      TransactionTemplate tx,
      Clock clock) {
    this.users = users;
    this.wechat = wechat;
    this.redis = redis;
    this.tx = tx;
    this.clock = clock;
  }

  public record Profile(Long id, String nickname, String avatarUrl, Long version) {}

  public record LoginResult(String token, Instant expiresAt, Profile user) {}

  public static Profile profile(AppUser u) {
    return new Profile(u.id(), u.nickname(), u.avatarUrl(), u.version());
  }

  public LoginResult login(String code, String nickname) {
    String display =
        nickname == null || nickname.isBlank() ? "微信用户" : TextRules.required(nickname, 20);
    var identity = wechat.exchange(code);
    AppUser user =
        tx.execute(
            status -> {
              users.register(identity.openid(), identity.unionid(), display);
              return users.findByOpenid(identity.openid());
            });
    if (user == null || !"ACTIVE".equals(user.status()))
      throw new ApiException(401, "AUTH_REQUIRED", "账号不可用");
    String token = Tokens.random();
    redis.opsForValue().set(SessionStore.key(token), user.id().toString(), Duration.ofDays(30));
    return new LoginResult(token, clock.instant().plus(Duration.ofDays(30)), profile(user));
  }

  public Profile current(Long id) {
    return profile(users.find(id));
  }

  public Profile rename(Long id, String nickname, Long version) {
    String name = TextRules.required(nickname, 20);
    return tx.execute(
        status -> {
          if (users.updateNickname(id, name, version) != 1) throw ApiException.conflict();
          return current(id);
        });
  }
}
