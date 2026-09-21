package com.myhome.table.common.security;

import java.time.Instant;

/** 各服务提供只读实现，不能通过此接口修改用户、授权或主厨绑定。 */
public interface AccessFacts {
  Long activeUser(Long userId);

  Long chefRestaurant(Long userId);

  Instant grantExpiresAt(Long userId);
}
