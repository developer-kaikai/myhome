package com.myhome.table.common.exception;

public class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException invalid(String message) {
    return new ApiException(400, "VALIDATION_FAILED", message);
  }

  public static ApiException conflict() {
    return new ApiException(409, "VERSION_CONFLICT", "内容已被更新，请刷新后重试");
  }

  public static ApiException unavailable() {
    return new ApiException(503, "SERVICE_UNAVAILABLE", "服务暂不可用，请稍后重试");
  }
}
