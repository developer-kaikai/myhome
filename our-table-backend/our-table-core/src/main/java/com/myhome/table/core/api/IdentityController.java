package com.myhome.table.core.api;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import com.myhome.table.core.application.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
  private final IdentityService identity;
  private final SessionStore sessions;
  private final Clock clock;

  public IdentityController(IdentityService identity, SessionStore sessions, Clock clock) {
    this.identity = identity;
    this.sessions = sessions;
    this.clock = clock;
  }

  public record LoginRequest(
      @NotBlank @Size(max = 256) String code, @Size(max = 1024) String nickname) {}

  public record RenameRequest(
      @NotBlank @Size(max = 1024) String nickname, @NotNull @PositiveOrZero Long version) {}

  public record Me(
      IdentityService.Profile user,
      Long chefRestaurantId,
      boolean dailyAllowed,
      Instant dailyExpiresAt) {}

  @PostMapping("/auth/wechat/login")
  public ApiResponse<?> login(@Valid @RequestBody LoginRequest body) {
    return ApiResponse.ok(identity.login(body.code(), body.nickname()));
  }

  @PostMapping("/auth/logout")
  public ApiResponse<?> logout(@RequestHeader("Authorization") String header) {
    sessions.revoke(header.substring(7));
    return ApiResponse.ok(null);
  }

  @GetMapping("/users/me")
  public ApiResponse<Me> me(@RequestAttribute(RequestFilter.USER) UserContext user) {
    return ApiResponse.ok(
        new Me(
            identity.current(user.userId()),
            user.chefRestaurantId(),
            user.dailyAllowed(clock.instant()),
            user.dailyExpiresAt()));
  }

  @PutMapping("/users/me/profile")
  public ApiResponse<?> rename(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @Valid @RequestBody RenameRequest body) {
    return ApiResponse.ok(identity.rename(user.userId(), body.nickname(), body.version()));
  }
}
