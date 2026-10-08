package com.myhome.table.ordering.api;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import com.myhome.table.ordering.application.ReviewService;
import com.myhome.table.ordering.domain.ReviewModels.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {
  private final ReviewService service;

  public ReviewController(ReviewService service) {
    this.service = service;
  }

  @GetMapping("/meal-orders/{id}/review-sheet")
  public ApiResponse<Sheet> sheet(
      @RequestAttribute(RequestFilter.USER) UserContext u, @PathVariable long id) {
    return ApiResponse.ok(service.sheet(u, id, null));
  }

  @GetMapping("/review-invitations/{token}")
  public ApiResponse<Sheet> invitation(
      @RequestAttribute(RequestFilter.USER) UserContext u, @PathVariable String token) {
    return ApiResponse.ok(service.sheet(u, 0, token));
  }

  @PostMapping("/meal-orders/{id}/reviews")
  public ApiResponse<Review> write(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestBody Write f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.write(u, id, null, f, key));
  }

  @PostMapping("/review-invitations/{token}/reviews")
  public ApiResponse<Review> invitedWrite(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable String token,
      @RequestBody Write f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.write(u, 0, token, f, key));
  }

  @PutMapping("/reviews/{id}")
  public ApiResponse<Review> modify(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestBody Modify f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.modify(u, id, f, key));
  }

  @PostMapping("/meal-orders/{id}/review-shares")
  public ApiResponse<Share> share(
      @RequestAttribute(RequestFilter.USER) UserContext u, @PathVariable long id) {
    return ApiResponse.ok(service.share(u, id));
  }

  @GetMapping("/my/reviews")
  public ApiResponse<Reviews> mine(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @RequestParam(defaultValue = "1") int page) {
    return ApiResponse.ok(service.mine(u, page));
  }

  @GetMapping("/my/review-invitations")
  public ApiResponse<Invitations> invitations(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @RequestParam(defaultValue = "1") int page) {
    return ApiResponse.ok(service.invitations(u, page));
  }

  @GetMapping("/dishes/{id}/reviews")
  public ApiResponse<DishReviews> dish(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestParam(defaultValue = "1") int page) {
    return ApiResponse.ok(service.dish(u, id, page));
  }
}
