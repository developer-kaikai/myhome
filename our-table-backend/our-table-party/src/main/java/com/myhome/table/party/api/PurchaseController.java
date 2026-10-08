package com.myhome.table.party.api;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import com.myhome.table.party.application.PurchaseService;
import com.myhome.table.party.domain.PurchaseModels.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/parties/{partyId}/items")
public class PurchaseController {
  private final PurchaseService service;

  public PurchaseController(PurchaseService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<Listing> list(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long partyId,
      @RequestParam(defaultValue = "1") int page) {
    return ApiResponse.ok(service.list(u, partyId, page));
  }

  @GetMapping("/{id}")
  public ApiResponse<Item> get(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long partyId,
      @PathVariable long id) {
    return ApiResponse.ok(service.get(u, partyId, id));
  }

  @PostMapping
  public ApiResponse<Item> create(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long partyId,
      @RequestBody Create f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.create(u, partyId, f, key));
  }

  @PutMapping("/{id}")
  public ApiResponse<Item> change(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long partyId,
      @PathVariable long id,
      @RequestBody Change f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.change(u, partyId, id, f, key));
  }
}
