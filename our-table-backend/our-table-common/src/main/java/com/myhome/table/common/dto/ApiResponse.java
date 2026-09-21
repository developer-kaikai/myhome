package com.myhome.table.common.dto;

import org.slf4j.MDC;

public record ApiResponse<T>(String code, String message, T data, String requestId) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>("OK", "", data, MDC.get("requestId"));
  }

  public static ApiResponse<Void> error(String code, String message) {
    return new ApiResponse<>(code, message, null, MDC.get("requestId"));
  }
}
