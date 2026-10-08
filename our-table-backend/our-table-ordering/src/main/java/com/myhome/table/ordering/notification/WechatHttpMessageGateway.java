package com.myhome.table.ordering.notification;

import com.fasterxml.jackson.databind.*;
import com.myhome.table.common.util.Tokens;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 只调用官方固定域名；不记录 URL、openid、token、原始响应和用户消息内容。 */
@Component
public class WechatHttpMessageGateway implements WechatMessageGateway {
  private final HttpClient http;
  private final ObjectMapper json;
  private final StringRedisTemplate redis;
  private final String appId, secret;

  @org.springframework.beans.factory.annotation.Autowired
  public WechatHttpMessageGateway(
      ObjectMapper json,
      StringRedisTemplate redis,
      @Value("${app.wechat.app-id:}") String appId,
      @Value("${app.wechat.app-secret:}") String secret) {
    this(
        json,
        redis,
        appId,
        secret,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  WechatHttpMessageGateway(
      ObjectMapper json, StringRedisTemplate redis, String appId, String secret, HttpClient http) {
    this.http = http;
    this.json = json;
    this.redis = redis;
    this.appId = appId;
    this.secret = secret;
  }

  private String tokenKey() {
    return "our-table:wx-message-token:" + Tokens.sha256(appId).substring(0, 16);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String token() throws Exception {
    if (appId.isBlank() || secret.isBlank())
      throw new IllegalStateException("Wechat credentials missing");
    String key = tokenKey(), cached = redis.opsForValue().get(key);
    if (cached != null) return cached;
    String owner = Tokens.random();
    if (!Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent(key + ":lock", owner, Duration.ofSeconds(10))))
      throw new IllegalStateException("Token refresh busy");
    try {
      cached = redis.opsForValue().get(key);
      if (cached != null) return cached;
      URI uri =
          URI.create(
              "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="
                  + encode(appId)
                  + "&secret="
                  + encode(secret));
      var response =
          http.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IllegalStateException("Token unavailable");
      var body = json.readTree(response.body());
      String value = body.path("access_token").asText("");
      int expires = body.path("expires_in").asInt(0);
      if (value.isBlank() || expires <= 120) throw new IllegalStateException("Token unavailable");
      redis.opsForValue().set(key, value, Duration.ofSeconds(expires - 120));
      return value;
    } finally {
      redis.execute(
          new DefaultRedisScript<Long>(
              "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
              Long.class),
          java.util.List.of(key + ":lock"),
          owner);
    }
  }

  @Override
  public Result send(Message message) {
    String token;
    try {
      token = token();
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return new Result("TOKEN_UNAVAILABLE", false, true, false);
    }
    String payload;
    try {
      payload =
          json.writeValueAsString(
              Map.of(
                  "touser",
                  message.openid(),
                  "template_id",
                  message.templateId(),
                  "page",
                  message.page(),
                  "miniprogram_state",
                  message.state(),
                  "lang",
                  "zh_CN",
                  "data",
                  message.data()));
    } catch (Exception e) {
      return new Result("PAYLOAD_INVALID", false, false, false);
    }
    try {
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token="
                          + encode(token)))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) return Result.unknown();
      var body = json.readTree(response.body());
      if (!body.has("errcode")
          || !body.get("errcode").isIntegralNumber()
          || !body.get("errcode").canConvertToInt()) return Result.unknown();
      int code = body.get("errcode").asInt();
      if (code == 40001 || code == 42001) {
        try {
          redis.delete(tokenKey());
        } catch (Exception ignored) {
          /* 明确拒绝仍按明确失败处理 */
        }
      }
      return new Result(
          Integer.toString(code),
          code == 0,
          java.util.Set.of(-1, 40001, 42001, 45009).contains(code),
          false);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return Result.unknown();
    }
  }
}
