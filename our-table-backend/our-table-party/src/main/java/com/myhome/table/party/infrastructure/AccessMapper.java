package com.myhome.table.party.infrastructure;

import com.myhome.table.common.security.AccessFacts;
import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AccessMapper extends AccessFacts {
  @Select("SELECT id FROM app_user WHERE id=#{userId} AND status='ACTIVE'")
  Long activeUser(Long userId);

  @Select("SELECT id FROM restaurant WHERE chef_user_id=#{userId} AND status='ACTIVE'")
  Long chefRestaurant(Long userId);

  @Select(
      "SELECT MAX(g.expires_at) FROM daily_access_grant g JOIN daily_access_secret s ON s.id=1 AND s.grant_generation=g.grant_generation WHERE g.user_id=#{userId} AND g.revoked_at IS NULL")
  Instant grantExpiresAt(Long userId);
}
