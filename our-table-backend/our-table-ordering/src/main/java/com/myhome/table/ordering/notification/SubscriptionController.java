package com.myhome.table.ordering.notification;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class SubscriptionController {
  private final SubscriptionService service;

  public SubscriptionController(SubscriptionService service) {
    this.service = service;
  }

  @GetMapping("/subscriptions")
  public ApiResponse<List<SubscriptionService.Subscription>> list(
      @RequestAttribute(RequestFilter.USER) UserContext user) {
    return ApiResponse.ok(service.list(user));
  }

  @PostMapping("/subscriptions")
  public ApiResponse<SubscriptionService.Subscription> consent(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @RequestBody SubscriptionService.Consent input,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.consent(user, input, key));
  }
}
