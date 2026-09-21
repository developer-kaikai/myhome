package com.myhome.table.common.security;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserContextTest {
  @Test
  void grantExpiresAtExactInstant() {
    Instant now = Instant.parse("2026-09-05T00:00:00Z");
    assertThat(new UserContext(1L, null, now).dailyAllowed(now)).isFalse();
    assertThat(new UserContext(1L, null, now.plusSeconds(1)).dailyAllowed(now)).isTrue();
  }

  @Test
  void chefCannotManageOtherRestaurant() {
    var chef = new UserContext(1L, 10L, null);
    assertThat(chef.dailyAllowed(Instant.now())).isTrue();
    assertThatCode(() -> chef.requireChef(10L)).doesNotThrowAnyException();
    assertThatThrownBy(() -> chef.requireChef(20L)).hasMessageContaining("本餐厅");
  }

  @Test
  void loginDoesNotGrantDailyAccess() {
    assertThatThrownBy(() -> new UserContext(1L, null, null).requireDaily(Instant.now()))
        .hasMessageContaining("密令");
  }
}
