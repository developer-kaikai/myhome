package com.myhome.table.party.api;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import com.myhome.table.party.application.PartyService;
import com.myhome.table.party.domain.PartyModels.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PartyController {
  private final PartyService service;

  public PartyController(PartyService service) {
    this.service = service;
  }

  @GetMapping("/my/parties")
  public ApiResponse<Listing> list(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @RequestParam(defaultValue = "ACTIVE") String state,
      @RequestParam(defaultValue = "1") int page) {
    return ApiResponse.ok(service.list(u, state, page));
  }

  @PostMapping("/parties")
  public ApiResponse<Detail> create(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @RequestBody Create f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.create(u, f, key));
  }

  @GetMapping("/parties/{id}")
  public ApiResponse<Detail> get(
      @RequestAttribute(RequestFilter.USER) UserContext u, @PathVariable long id) {
    return ApiResponse.ok(service.get(u, id));
  }

  @PutMapping("/parties/{id}")
  public ApiResponse<Detail> change(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestBody Change f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.change(u, id, f, key));
  }

  @PutMapping("/parties/{id}/info")
  public ApiResponse<Detail> edit(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @RequestBody Edit f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.edit(u, id, f, key));
  }

  @PutMapping("/parties/{id}/members/{userId}")
  public ApiResponse<Detail> removeMember(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable long id,
      @PathVariable long userId,
      @RequestBody RemoveMember f,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.removeMember(u, id, userId, f, key));
  }

  @GetMapping("/parties/{id}/share")
  public ApiResponse<Share> share(
      @RequestAttribute(RequestFilter.USER) UserContext u, @PathVariable long id) {
    return ApiResponse.ok(service.share(u, id));
  }

  @GetMapping("/party-invitations/{token}")
  public ApiResponse<Detail> preview(
      @RequestAttribute(RequestFilter.USER) UserContext u, @PathVariable String token) {
    return ApiResponse.ok(service.preview(u, token));
  }

  @PostMapping("/party-invitations/{token}/join")
  public ApiResponse<Detail> join(
      @RequestAttribute(RequestFilter.USER) UserContext u,
      @PathVariable String token,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.join(u, token, key));
  }
}
