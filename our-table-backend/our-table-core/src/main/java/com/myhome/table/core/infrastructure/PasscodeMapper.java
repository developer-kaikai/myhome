package com.myhome.table.core.infrastructure;

import com.myhome.table.common.entity.DailyAccessSecret;
import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PasscodeMapper {
  @Select("SELECT * FROM daily_access_secret WHERE id=1 FOR UPDATE")
  DailyAccessSecret lockSecret();

  @Select("SELECT * FROM daily_access_secret WHERE id=1")
  DailyAccessSecret findSecret();

  @Insert(
      "INSERT INTO daily_access_secret(id,secret_hash,secret_ciphertext,secret_nonce,encryption_key_version,updated_by_user_id) VALUES(1,#{hash},#{cipher},#{nonce},'v1',#{user})")
  void initialize(String hash, byte[] cipher, byte[] nonce, Long user);

  @Update(
      "UPDATE daily_access_secret SET secret_hash=#{hash},secret_ciphertext=#{cipher},secret_nonce=#{nonce},secret_version=secret_version+1,version=version+1,updated_by_user_id=#{user} WHERE id=1 AND version=#{version}")
  int update(String hash, byte[] cipher, byte[] nonce, Long user, Long version);

  @Insert(
      "INSERT INTO daily_access_grant(user_id,secret_version,grant_generation,issued_at,expires_at) VALUES(#{user},#{secretVersion},#{generation},#{issued},#{expires})")
  void grant(Long user, Long secretVersion, Long generation, Instant issued, Instant expires);

  @Update(
      "UPDATE daily_access_secret SET grant_generation=grant_generation+1,version=version+1,updated_by_user_id=#{user} WHERE id=1 AND version=#{version}")
  int revoke(Long user, Long version);

  @Insert(
      "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id,request_id,summary_json) VALUES('CORE_ACCESS',1,#{action},#{user},#{requestId},#{summary})")
  void audit(String action, Long user, String requestId, String summary);
}
