package com.myhome.table.core.api;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import com.myhome.table.core.application.PasscodeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AccessController {
  private final PasscodeService service;
  private final Clock clock;

  public AccessController(PasscodeService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  public record Verify(@NotBlank @Size(min = 6, max = 20) String passcode) {}

  public record Change(
      @NotBlank @Size(min = 6, max = 20) String passcode, @NotNull @PositiveOrZero Long version) {}

  public record Revoke(@NotNull @PositiveOrZero Long version, @AssertTrue boolean confirmed) {}

  @GetMapping("/access/status")
  public ApiResponse<?> status(@RequestAttribute(RequestFilter.USER) UserContext user) {
    return ApiResponse.ok(
        new PasscodeService.Status(user.dailyAllowed(clock.instant()), user.dailyExpiresAt()));
  }

  @PostMapping("/access/passcode/verify")
  public ApiResponse<?> verify(
      @RequestAttribute(RequestFilter.USER) UserContext user, @Valid @RequestBody Verify body) {
    return ApiResponse.ok(service.verify(user, body.passcode()));
  }

  @GetMapping("/chef/access/passcode")
  public ApiResponse<?> read(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @RequestParam(defaultValue = "false") boolean reveal,
      jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(service.read(user, reveal));
  }

  @GetMapping("/chef/access/passcode/suggestion")
  public ApiResponse<?> suggestion(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      jakarta.servlet.http.HttpServletResponse response) {
    user.requireChef();
    response.setHeader("Cache-Control", "no-store");
    String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    var random = new java.security.SecureRandom();
    var value = new StringBuilder();
    for (int i = 0; i < 6; i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
    return ApiResponse.ok(java.util.Map.of("passcode", value.toString()));
  }

  @PutMapping("/chef/access/passcode")
  public ApiResponse<?> change(
      @RequestAttribute(RequestFilter.USER) UserContext user, @Valid @RequestBody Change body) {
    return ApiResponse.ok(service.change(user, body.passcode(), body.version()));
  }

  @PostMapping("/chef/access/grants/revoke-all")
  public ApiResponse<?> revoke(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @Valid @RequestBody Revoke body,
      @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.ok(service.revoke(user, body.version(), key));
  }
}
