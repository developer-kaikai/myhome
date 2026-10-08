package com.myhome.table.core.domain;

import java.time.LocalDate;
import java.util.List;

/** 顾客数据与主厨数据使用不同契约，做法不进入任何顾客响应。 */
public final class MenuModels {
  private MenuModels() {}

  public record RestaurantView(long id, String name, long version) {}

  public record Category(long id, long restaurantId, String type, String name, long version) {}

  public record Option(Long id, String name, boolean isDefault) {}

  public record Dimension(Long id, String name, List<Option> options) {}

  public record DishView(
      long id,
      long categoryId,
      String name,
      String introduction,
      boolean onShelf,
      long version,
      List<Integer> supplyMonths,
      List<Dimension> dimensions,
      Double average,
      long reviewCount,
      boolean hot) {}

  public record ChefDish(DishView dish, String recipe) {}

  public record Menu(
      RestaurantView restaurant,
      LocalDate date,
      List<Category> categories,
      List<DishView> dishes) {}

  public record DishForm(
      Long version,
      Long categoryId,
      String name,
      String introduction,
      String recipe,
      Boolean onShelf,
      List<Integer> supplyMonths,
      List<Dimension> dimensions) {}

  public record Created(long id) {}
}
