package com.myhome.table.ordering.api;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import com.myhome.table.ordering.application.OrderService;
import com.myhome.table.ordering.domain.CartModels.*;
import com.myhome.table.ordering.domain.OrderModels.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OrderController {
  private final OrderService service;
  private final com.myhome.table.ordering.application.MealConfirmationService confirmation;

  public OrderController(
      OrderService service,
      com.myhome.table.ordering.application.MealConfirmationService confirmation) {
    this.confirmation = confirmation;
    this.service = service;
  }

  @GetMapping("/meal-orders")
  public ApiResponse<Page> list(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @RequestParam(defaultValue = "IN_PROGRESS") String state,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.list(u, state, page, size));
  }

  @GetMapping("/chef/meal-orders")
  public ApiResponse<Page> chefList(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @RequestParam(defaultValue = "IN_PROGRESS") String state,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(service.chefList(u, state, page, size));
  }

  @GetMapping("/meal-orders/{id}")
  public ApiResponse<Detail> detail(
      @RequestAttribute(RequestFilter.USER) UserContext u, @PathVariable long id) {
    return ApiResponse.ok(service.detail(u, id));
  }

  @GetMapping("/meal-orders/{id}/submission-preview")
  public ApiResponse<Preview> preview(
      @RequestAttribute(RequestFilter.USER) UserContext u, @PathVariable long id) {
    return ApiResponse.ok(service.preview(u, id));
  }

  @PostMapping("/meal-orders/{id}/submit")
  public ApiResponse<Mutation> submit(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestBody Submit f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.submit(u, id, f, key));
  }

  @PutMapping("/meal-orders/{id}/meta")
  public ApiResponse<Mutation> meta(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestBody Submit f) {
    return ApiResponse.ok(service.meta(u, id, f));
  }

  @PostMapping("/meal-orders/{id}/cancel")
  public ApiResponse<Mutation> cancel(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestBody Cancel f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.cancel(u, id, f, key));
  }

  @PostMapping("/meal-orders/{id}/reopen")
  public ApiResponse<Mutation> reopen(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestBody Version f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.reopen(u, id, f.version(), key));
  }

  @GetMapping("/chef/meal-orders/{id}/confirmation-preview")
  public ApiResponse<Confirmation> confirmation(
      @RequestAttribute(RequestFilter.USER) UserContext u, @PathVariable long id) {
    return ApiResponse.ok(confirmation.preview(u, id));
  }

  @PostMapping("/chef/meal-orders/{id}/complete")
  public ApiResponse<Mutation> complete(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestBody Complete f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(confirmation.complete(u, id, f, key));
  }

  @PostMapping("/chef/meal-orders/{id}/not-cooked")
  public ApiResponse<Mutation> notCooked(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestBody Cancel f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(confirmation.notCooked(u, id, f, key));
  }

  @GetMapping("/chef/meal-orders/{id}/items/{item}/recipe")
  public ApiResponse<Map<String, String>> recipe(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @PathVariable long item) {
    return ApiResponse.ok(service.recipe(u, id, item));
  }
}
