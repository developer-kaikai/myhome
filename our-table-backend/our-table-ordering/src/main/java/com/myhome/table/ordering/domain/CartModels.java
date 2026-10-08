package com.myhome.table.ordering.domain;

import com.myhome.table.common.util.BusinessTime.Meal;
import java.time.*;
import java.util.List;

public final class CartModels {
  private CartModels() {}

  public record Selection(
      long dimensionId, String dimensionName, long optionId, String optionName) {}

  public record Item(
      long id,
      long dishId,
      String dishName,
      String imageObjectKey,
      long contributorId,
      String contributorName,
      List<Selection> selections,
      int quantity,
      long version) {}

  public record Cart(
      Long orderId,
      long restaurantId,
      LocalDate date,
      Meal meal,
      Instant cutoff,
      Instant serverTime,
      String status,
      long version,
      List<Item> items,
      List<Item> submittedItems,
      int dishCount,
      int quantity,
      int contributorCount) {}

  public record Add(Long dishId, Long dishVersion, List<Long> optionIds) {}

  public record Edit(Long version, Long itemVersion, Integer quantity, List<Long> optionIds) {}

  public record Delta(Integer delta) {}

  public record Version(Long version) {}

  public record Mutation(long orderId, long version) {}
}
