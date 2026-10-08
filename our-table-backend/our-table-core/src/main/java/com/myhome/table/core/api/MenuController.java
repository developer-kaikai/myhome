package com.myhome.table.core.api;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import com.myhome.table.core.application.MenuService;
import com.myhome.table.core.domain.MenuModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MenuController {
  private final MenuService menus;
  private final Clock clock;

  public MenuController(MenuService menus, Clock clock) {
    this.menus = menus;
    this.clock = clock;
  }

  public record NameForm(@NotBlank @Size(max = 1024) String name, @PositiveOrZero Long version) {}

  public record VersionForm(@NotNull @PositiveOrZero Long version) {}

  @GetMapping("/restaurants")
  public ApiResponse<?> restaurants(@RequestAttribute(RequestFilter.USER) UserContext user) {
    return ApiResponse.ok(menus.restaurants(user));
  }

  @GetMapping("/restaurants/{id}/menu")
  public ApiResponse<?> menu(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @RequestParam(required = false) LocalDate date) {
    return ApiResponse.ok(menus.menu(user, id, date(date), false));
  }

  @GetMapping("/restaurants/{id}/dishes/{dish}")
  public ApiResponse<?> dish(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @PathVariable long dish,
      @RequestParam(required = false) LocalDate date) {
    return ApiResponse.ok(menus.customerDish(user, id, dish, date(date)));
  }

  @GetMapping("/chef/restaurants/{id}/menu")
  public ApiResponse<?> chefMenu(
      @RequestAttribute(RequestFilter.USER) UserContext user, @PathVariable long id) {
    return ApiResponse.ok(menus.menu(user, id, date(null), true));
  }

  @PutMapping("/chef/restaurants/{id}")
  public ApiResponse<?> renameRestaurant(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @Valid @RequestBody NameForm body) {
    return ApiResponse.ok(menus.renameRestaurant(user, id, body.name(), body.version()));
  }

  @PostMapping("/chef/restaurants/{id}/categories")
  public ApiResponse<?> createCategory(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @Valid @RequestBody NameForm body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(menus.createCategory(user, id, body.name(), key));
  }

  @PutMapping("/chef/categories/{id}")
  public ApiResponse<?> renameCategory(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @Valid @RequestBody NameForm body) {
    return ApiResponse.ok(menus.renameCategory(user, id, body.name(), body.version()));
  }

  @DeleteMapping("/chef/categories/{id}")
  public ApiResponse<?> deleteCategory(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @Valid @RequestBody VersionForm body) {
    menus.deleteCategory(user, id, body.version());
    return ApiResponse.ok(null);
  }

  @PostMapping("/chef/restaurants/{id}/dishes")
  public ApiResponse<?> createDish(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @RequestBody DishForm body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(menus.createDish(user, id, body, key));
  }

  @GetMapping("/chef/dishes/{id}")
  public ApiResponse<?> chefDish(
      @RequestAttribute(RequestFilter.USER) UserContext user, @PathVariable long id) {
    return ApiResponse.ok(menus.chefDish(user, id));
  }

  @GetMapping("/chef/dishes/{id}/recipe")
  public ApiResponse<?> recipe(
      @RequestAttribute(RequestFilter.USER) UserContext user, @PathVariable long id) {
    return ApiResponse.ok(menus.chefDish(user, id).recipe());
  }

  @PutMapping("/chef/dishes/{id}")
  public ApiResponse<?> updateDish(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @RequestBody DishForm body) {
    return ApiResponse.ok(menus.updateDish(user, id, body));
  }

  @DeleteMapping("/chef/dishes/{id}")
  public ApiResponse<?> deleteDish(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @Valid @RequestBody VersionForm body) {
    menus.deleteDish(user, id, body.version());
    return ApiResponse.ok(null);
  }

  private LocalDate date(LocalDate requested) {
    return requested == null
        ? LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))
        : requested;
  }
}
