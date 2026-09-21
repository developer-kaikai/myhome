package com.myhome.table.common.util;

import com.myhome.table.common.exception.ApiException;
import java.time.*;

public final class BusinessTime {
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  public enum Meal {
    BREAKFAST,
    LUNCH,
    DINNER,
    SUPPER
  }

  public record Slot(LocalDate date, Meal meal, Instant cutoff) {}

  private BusinessTime() {}

  public static Instant cutoff(LocalDate date, Meal meal) {
    return switch (meal) {
      case BREAKFAST -> date.atTime(11, 0).atZone(ZONE).toInstant();
      case LUNCH -> date.atTime(15, 0).atZone(ZONE).toInstant();
      case DINNER -> date.atTime(21, 0).atZone(ZONE).toInstant();
      case SUPPER -> date.plusDays(1).atTime(2, 0).atZone(ZONE).toInstant();
    };
  }

  public static Slot defaultSlot(Instant now) {
    var time = now.atZone(ZONE);
    var date = time.toLocalDate();
    int mins = time.getHour() * 60 + time.getMinute();
    Meal meal;
    if (mins < 120) {
      date = date.minusDays(1);
      meal = Meal.SUPPER;
    } else if (mins < 630) meal = Meal.BREAKFAST;
    else if (mins < 870) meal = Meal.LUNCH;
    else if (mins < 1260) meal = Meal.DINNER;
    else meal = Meal.SUPPER;
    return new Slot(date, meal, cutoff(date, meal));
  }

  public static void requireOpen(LocalDate date, Meal meal, Instant now) {
    var today = now.atZone(ZONE).toLocalDate();
    boolean allowed =
        date.equals(today)
            || date.equals(today.plusDays(1))
            || (date.equals(today.minusDays(1)) && meal == Meal.SUPPER);
    if (!allowed || !now.isBefore(cutoff(date, meal)))
      throw new ApiException(409, "MEAL_SLOT_CLOSED", "该日期餐次已截止或尚未开放");
  }
}
