package com.myhome.table.core.api;

import com.myhome.table.common.dto.ApiResponse;
import com.myhome.table.common.security.*;
import com.myhome.table.core.application.HomeService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/home")
public class HomeController {
  private final HomeService home;

  public HomeController(HomeService home) {
    this.home = home;
  }

  @GetMapping("/summary")
  public ApiResponse<?> summary(
      @RequestAttribute(RequestFilter.USER) UserContext user,
      @RequestParam(defaultValue = "week") String period,
      @RequestParam(defaultValue = "family") String scope) {
    return ApiResponse.ok(home.summary(user, period, scope));
  }
}
