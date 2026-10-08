package com.myhome.table.core.domain;

import com.myhome.table.core.domain.MenuModels.RestaurantView;
import java.time.LocalDate;
import java.util.List;

public final class HomeModels {
  private HomeModels() {}

  public record Point(String label, LocalDate date, Long count, boolean future) {}

  public record Popular(
      long dishId,
      long restaurantId,
      String dishName,
      String restaurantName,
      long orderCount,
      LocalDate lastMealDate,
      Double average,
      long reviewCount) {}

  public record Statistics(long cookCount, List<Point> trend, List<Popular> popular) {}

  public record Recommendation(
      long dishId,
      long restaurantId,
      String dishName,
      String restaurantName,
      String introduction,
      Double average,
      long reviewCount) {}

  public record Summary(
      LocalDate today,
      String greeting,
      String period,
      String scope,
      LocalDate rangeStart,
      LocalDate rangeEnd,
      List<RestaurantView> restaurants,
      long cookCount,
      List<Point> trend,
      List<Popular> popular,
      Recommendation recommendation) {}
}
