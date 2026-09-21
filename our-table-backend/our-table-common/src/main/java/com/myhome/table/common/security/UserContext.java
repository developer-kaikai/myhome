package com.myhome.table.common.security;

import com.myhome.table.common.exception.ApiException;
import java.time.Instant;

public record UserContext(Long userId, Long chefRestaurantId, Instant dailyExpiresAt) {
  public boolean dailyAllowed(Instant now) {
    return chefRestaurantId != null || (dailyExpiresAt != null && now.isBefore(dailyExpiresAt));
  }

  public void requireDaily(Instant now) {
    if (!dailyAllowed(now)) throw new ApiException(403, "ACCESS_REQUIRED", "请先验证日常密令");
  }

  public Long requireChef() {
    if (chefRestaurantId == null) throw new ApiException(403, "CHEF_ONLY", "仅主厨可以操作");
    return chefRestaurantId;
  }

  public void requireChef(Long restaurant) {
    if (!requireChef().equals(restaurant)) throw new ApiException(403, "CHEF_ONLY", "仅本餐厅主厨可以操作");
  }
}
