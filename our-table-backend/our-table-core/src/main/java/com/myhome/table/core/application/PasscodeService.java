package com.myhome.table.core.application;

import com.myhome.table.common.entity.DailyAccessSecret;
import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.IdempotencyService;
import com.myhome.table.common.security.UserContext;
import com.myhome.table.common.util.TextRules;
import com.myhome.table.core.infrastructure.*;
import java.time.*;
import org.slf4j.MDC;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasscodeService {
  private final PasscodeMapper secrets;
  private final UserMapper users;
  private final AccessMapper access;
  private final SecretCipher cipher;
  private final PasscodeLimiter limiter;
  private final TransactionTemplate tx;
  private final IdempotencyService idempotency;
  private final Clock clock;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);

  public PasscodeService(
      PasscodeMapper secrets,
      UserMapper users,
      AccessMapper access,
      SecretCipher cipher,
      PasscodeLimiter limiter,
      TransactionTemplate tx,
      IdempotencyService idempotency,
      Clock clock) {
    this.secrets = secrets;
    this.users = users;
    this.access = access;
    this.cipher = cipher;
    this.limiter = limiter;
    this.tx = tx;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public record Status(boolean dailyAllowed, Instant expiresAt) {}

  public record SecretView(
      boolean configured, String passcode, Long version, Long grantGeneration) {}

  public record Revoked(Long generation, Long version) {}

  public Status verify(UserContext user, String passcode) {
    TextRules.passcode(passcode);
    return tx.execute(
        status -> {
          // 全局密令行锁使验证、改密和撤销按确定顺序执行；用户行锁阻止重复发放。
          DailyAccessSecret secret = secrets.lockSecret();
          if (secret == null) throw new ApiException(409, "PASSCODE_NOT_CONFIGURED", "请主厨先设置日常密令");
          users.lock(user.userId());
          limiter.check(user.userId());
          if (!bcrypt.matches(passcode, secret.secretHash())) limiter.failure(user.userId());
          limiter.success(user.userId());
          Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
              existing = access.grantExpiresAt(user.userId());
          if (user.chefRestaurantId() != null) return new Status(true, null);
          if (existing != null && now.isBefore(existing)) return new Status(true, existing);
          Instant expiry = now.plus(Duration.ofHours(168));
          secrets.grant(
              user.userId(), secret.secretVersion(), secret.grantGeneration(), now, expiry);
          return new Status(true, expiry);
        });
  }

  public SecretView read(UserContext user, boolean reveal) {
    user.requireChef();
    return tx.execute(
        status -> {
          var s = secrets.findSecret();
          if (s == null) return new SecretView(false, null, 0L, 0L);
          if (reveal) secrets.audit("VIEW_SECRET", user.userId(), MDC.get("requestId"), "{}");
          return new SecretView(
              true,
              reveal ? cipher.decrypt(s.secretCiphertext(), s.secretNonce()) : null,
              s.version(),
              s.grantGeneration());
        });
  }

  public SecretView change(UserContext user, String passcode, Long version) {
    user.requireChef();
    String text = TextRules.passcode(passcode);
    String hash = bcrypt.encode(text);
    var encrypted = cipher.encrypt(text);
    return tx.execute(
        status -> {
          // 初始化由单例主键兜底；已有配置通过行锁和版本号防止覆盖。
          var s = secrets.lockSecret();
          if (s == null) {
            if (version != 0L) throw ApiException.conflict();
            try {
              secrets.initialize(hash, encrypted.ciphertext(), encrypted.nonce(), user.userId());
            } catch (org.springframework.dao.DuplicateKeyException e) {
              throw ApiException.conflict();
            }
          } else if (secrets.update(
                  hash, encrypted.ciphertext(), encrypted.nonce(), user.userId(), version)
              != 1) throw ApiException.conflict();
          secrets.audit("CHANGE_SECRET", user.userId(), MDC.get("requestId"), "{}");
          var current = secrets.findSecret();
          return new SecretView(true, null, current.version(), current.grantGeneration());
        });
  }

  public Revoked revoke(UserContext user, Long version, String key) {
    user.requireChef();
    return idempotency.execute(
        user.userId(),
        "core:revoke-grants",
        key,
        version.toString(),
        Revoked.class,
        () -> {
          var s = secrets.lockSecret();
          if (s == null) throw new ApiException(409, "PASSCODE_NOT_CONFIGURED", "尚未设置密令");
          if (secrets.revoke(user.userId(), version) != 1) throw ApiException.conflict();
          secrets.audit("REVOKE_GRANTS", user.userId(), MDC.get("requestId"), "{}");
          return new Revoked(s.grantGeneration() + 1, s.version() + 1);
        });
  }
}
