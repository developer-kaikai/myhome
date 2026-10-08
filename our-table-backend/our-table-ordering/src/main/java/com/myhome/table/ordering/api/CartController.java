package com.myhome.table.ordering.api;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import com.myhome.table.common.util.BusinessTime.Meal;
import com.myhome.table.ordering.application.CartService;
import com.myhome.table.ordering.domain.CartModels.*;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CartController {
  private final CartService service;

  public CartController(CartService service) {
    this.service = service;
  }

  @GetMapping("/meal-slots/{restaurant}/{date}/{meal}")
  public ApiResponse<Cart> read(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long restaurant,
      @PathVariable LocalDate date,
      @PathVariable Meal meal) {
    return ApiResponse.ok(service.read(user, restaurant, date, meal));
  }

  @PutMapping("/meal-slots/{restaurant}/{date}/{meal}/draft")
  public ApiResponse<Mutation> ensure(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long restaurant,
      @PathVariable LocalDate date,
      @PathVariable Meal meal) {
    return ApiResponse.ok(service.ensure(user, restaurant, date, meal));
  }

  @PostMapping("/meal-orders/{id}/items")
  public ApiResponse<Mutation> add(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @RequestBody Add form,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.add(user, id, form, key));
  }

  @PostMapping("/meal-orders/{id}/items/{item}/quantity")
  public ApiResponse<Mutation> delta(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @PathVariable long item,
      @RequestBody Delta form,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.delta(user, id, item, form, key));
  }

  @PutMapping("/meal-orders/{id}/items/{item}")
  public ApiResponse<Mutation> edit(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @PathVariable long item,
      @RequestBody Edit form) {
    return ApiResponse.ok(service.edit(user, id, item, form));
  }

  @DeleteMapping("/meal-orders/{id}/items/{item}")
  public ApiResponse<Mutation> delete(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @PathVariable long item,
      @RequestBody Version form) {
    return ApiResponse.ok(service.delete(user, id, item, form.version()));
  }

  @PostMapping("/meal-orders/{id}/pending/clear")
  public ApiResponse<Mutation> clear(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @PathVariable long id,
      @RequestBody Version form) {
    return ApiResponse.ok(service.clear(user, id, form.version()));
  }
}
