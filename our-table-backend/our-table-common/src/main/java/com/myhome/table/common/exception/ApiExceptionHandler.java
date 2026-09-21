package com.myhome.table.common.exception;

import com.myhome.table.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<?> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(), e.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class
  })
  public ResponseEntity<?> invalid(Exception e) {
    return api(ApiException.invalid("请检查填写内容和请求参数"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<?> notFound() {
    return api(new ApiException(404, "RESOURCE_NOT_FOUND", "资源不存在"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    // 不打印异常 message / cause，避免驱动或第三方异常携带连接参数、令牌、用户输入。
    log.error("request failed exceptionType={}", e.getClass().getSimpleName());
    return api(ApiException.unavailable());
  }
}
