package com.myhome.table.common.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.util.Tokens;
import java.sql.Timestamp;
import java.time.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 只写公共幂等表，与调用方业务使用同一数据库事务；不在事务内调用外部平台。 */
@Component
public class IdempotencyService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final Clock clock;

  public IdempotencyService(
      JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
  }

  public <T> T execute(
      Long user, String route, String key, String request, Class<T> type, Supplier<T> work) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{16,64}")) throw ApiException.invalid("请提供有效幂等键");
    String hash = Tokens.sha256(request);
    Instant now = clock.instant();
    return tx.execute(
        status -> {
          jdbc.update(
              "INSERT INTO api_idempotency_record(user_id,route_key,idempotency_key,request_hash,expires_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
              user,
              route,
              key,
              hash,
              Timestamp.from(now.plus(Duration.ofHours(24))));
          var row =
              jdbc.queryForMap(
                  "SELECT request_hash,processing_status,response_json,expires_at FROM api_idempotency_record WHERE user_id=? AND route_key=? AND idempotency_key=? FOR UPDATE",
                  user,
                  route,
                  key);
          if (!hash.equals(row.get("request_hash")))
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "该操作标识已用于不同请求");
          if ("SUCCEEDED".equals(row.get("processing_status"))) {
            try {
              return json.readValue(row.get("response_json").toString(), type);
            } catch (Exception e) {
              throw ApiException.unavailable();
            }
          }
          T result = work.get();
          String response;
          try {
            response = json.writeValueAsString(result);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          jdbc.update(
              "UPDATE api_idempotency_record SET processing_status='SUCCEEDED',http_status=200,response_json=? WHERE user_id=? AND route_key=? AND idempotency_key=?",
              response,
              user,
              route,
              key);
          return result;
        });
  }
}
