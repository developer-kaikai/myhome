package com.myhome.table.common.util;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class BusinessTimeTest {
  private Instant time(String t) {
    return LocalDateTime.parse(t).atZone(BusinessTime.ZONE).toInstant();
  }

  @Test
  void midnightBelongsToPreviousSupper() {
    var slot = BusinessTime.defaultSlot(time("2026-09-05T00:30:00"));
    assertThat(slot.date()).isEqualTo(LocalDate.of(2026, 9, 4));
    assertThat(slot.meal()).isEqualTo(BusinessTime.Meal.SUPPER);
    assertThat(slot.cutoff()).isEqualTo(time("2026-09-05T02:00:00"));
  }

  @Test
  void cutoffIsExclusive() {
    assertThatThrownBy(
            () ->
                BusinessTime.requireOpen(
                    LocalDate.of(2026, 9, 4),
                    BusinessTime.Meal.SUPPER,
                    time("2026-09-05T02:00:00")))
        .hasMessageContaining("截止");
    assertThat(BusinessTime.defaultSlot(time("2026-09-05T02:00:00")).meal())
        .isEqualTo(BusinessTime.Meal.BREAKFAST);
  }

  @Test
  void defaultAndAvailableAreDifferent() {
    assertThat(BusinessTime.defaultSlot(time("2026-09-05T10:45:00")).meal())
        .isEqualTo(BusinessTime.Meal.LUNCH);
    assertThatCode(
            () ->
                BusinessTime.requireOpen(
                    LocalDate.of(2026, 9, 5),
                    BusinessTime.Meal.BREAKFAST,
                    time("2026-09-05T10:45:00")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsDistantFuture() {
    assertThatThrownBy(
            () ->
                BusinessTime.requireOpen(
                    LocalDate.of(2026, 9, 7),
                    BusinessTime.Meal.DINNER,
                    time("2026-09-05T16:00:00")))
        .isInstanceOf(RuntimeException.class);
  }
}
