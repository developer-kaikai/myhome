package com.myhome.table.core.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhome.table.common.exception.ApiException;
import com.myhome.table.core.domain.WechatGateway;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WechatHttpGateway implements WechatGateway {
  private final String appId, secret;
  private final ObjectMapper json;
  private final HttpClient client;

  public WechatHttpGateway(
      @Value("${app.wechat.app-id:}") String appId,
      @Value("${app.wechat.app-secret:}") String secret,
      ObjectMapper json) {
    this.appId = appId;
    this.secret = secret;
    this.json = json;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public Identity exchange(String code) {
    if (appId.isBlank() || secret.isBlank())
      throw new ApiException(503, "WECHAT_NOT_CONFIGURED", "微信登录尚未配置，请联系主厨");
    try {
      // 固定官方地址；不允许请求指定网关，也不记录带凭据的URL或响应原文。
      URI uri =
          URI.create(
              "https://api.weixin.qq.com/sns/jscode2session?appid="
                  + enc(appId)
                  + "&secret="
                  + enc(secret)
                  + "&js_code="
                  + enc(code)
                  + "&grant_type=authorization_code");
      var response =
          client.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw ApiException.unavailable();
      var data = json.readTree(response.body());
      if (data.path("errcode").asInt(0) != 0)
        throw new ApiException(401, "WECHAT_LOGIN_FAILED", "微信登录失败，请重新登录");
      String openid = data.path("openid").asText("");
      if (openid.isBlank() || openid.length() > 64) throw ApiException.unavailable();
      return new Identity(openid, data.hasNonNull("unionid") ? data.get("unionid").asText() : null);
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.unavailable();
    } catch (Exception e) {
      throw ApiException.unavailable();
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
