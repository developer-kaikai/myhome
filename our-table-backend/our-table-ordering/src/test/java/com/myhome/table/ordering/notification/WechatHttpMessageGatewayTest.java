package com.myhome.table.ordering.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;

class WechatHttpMessageGatewayTest {
  HttpClient http;
  StringRedisTemplate redis;
  WechatHttpMessageGateway gateway;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setup() {
    http = mock(HttpClient.class);
    redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenReturn("mock-access-token");
    gateway =
        new WechatHttpMessageGateway(new ObjectMapper(), redis, "mock-app-id", "mock-secret", http);
  }

  WechatMessageGateway.Message message() {
    return new WechatMessageGateway.Message(
        "mock-openid",
        "test_template_6633",
        "subpackages/reviews/sheet?id=1",
        "developer",
        Map.of("thing6", Map.of("value", "我们的厨房")));
  }

  @SuppressWarnings("unchecked")
  void respond(int status, String body) throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
  }

  @Test
  void onlyExplicitZeroReceiptIsAccepted() throws Exception {
    respond(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
    var result = gateway.send(message());
    assertThat(result.accepted()).isTrue();
    assertThat(result.uncertain()).isFalse();
    verify(http)
        .send(
            argThat(
                r ->
                    r.uri().getHost().equals("api.weixin.qq.com")
                        && r.uri().getPath().equals("/cgi-bin/message/subscribe/send")
                        && r.method().equals("POST")),
            any(HttpResponse.BodyHandler.class));
  }

  @Test
  void httpSuccessWithoutErrcodeIsUnknown() throws Exception {
    respond(200, "{}");
    assertThat(gateway.send(message()).uncertain()).isTrue();
    respond(200, "{\"errcode\":4294967296}");
    assertThat(gateway.send(message()).uncertain()).isTrue();
    respond(200, "{\"errcode\":\"0\"}");
    assertThat(gateway.send(message()).uncertain()).isTrue();
  }

  @Test
  void definiteRefusalDoesNotConsumeCredit() throws Exception {
    respond(200, "{\"errcode\":43101}");
    var r = gateway.send(message());
    assertThat(r.accepted()).isFalse();
    assertThat(r.retryable()).isFalse();
    assertThat(r.uncertain()).isFalse();
    assertThat(r.code()).isEqualTo("43101");
  }

  @Test
  void httpErrorAndTimeoutAreNeverBlindlyRetried() throws Exception {
    respond(503, "unavailable");
    assertThat(gateway.send(message()).uncertain()).isTrue();
    reset(http);
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("mock timeout"));
    assertThat(gateway.send(message()).uncertain()).isTrue();
  }

  @Test
  void noTokenNeverCallsSendEndpoint() {
    when(redis.opsForValue().get(anyString())).thenReturn(null);
    when(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
        .thenReturn(false);
    var r = gateway.send(message());
    assertThat(r.code()).isEqualTo("TOKEN_UNAVAILABLE");
    assertThat(r.uncertain()).isFalse();
    verifyNoInteractions(http);
  }

  @Test
  void redactedMessageCannotLeakOpenidFromDefaultLogging() {
    assertThat(message().toString()).doesNotContain("mock-openid", "test_template_6633");
  }
}
