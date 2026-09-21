package com.myhome.table.ordering.api;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import com.myhome.table.common.util.BusinessTime;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/meal-slots")
public class MealSlotController {
  private final Clock clock;

  public MealSlotController(Clock clock) {
    this.clock = clock;
  }

  public record Options(
      Instant serverTime, BusinessTime.Slot defaultSlot, List<BusinessTime.Slot> slots) {}

  @GetMapping
  public ApiResponse<Options> slots(@RequestAttribute(RequestFilter.USER) UserContext user) {
    Instant now = clock.instant();
    user.requireDaily(now);
    var today = now.atZone(BusinessTime.ZONE).toLocalDate();
    List<BusinessTime.Slot> slots = new ArrayList<>();
    for (int d = -1; d <= 1; d++)
      for (var meal : BusinessTime.Meal.values()) {
        var date = today.plusDays(d);
        var cutoff = BusinessTime.cutoff(date, meal);
        if ((d >= 0 || meal == BusinessTime.Meal.SUPPER) && now.isBefore(cutoff))
          slots.add(new BusinessTime.Slot(date, meal, cutoff));
      }
    return ApiResponse.ok(new Options(now, BusinessTime.defaultSlot(now), slots));
  }
}
