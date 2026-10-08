package com.myhome.table.ordering.notification;

import java.util.Map;

public interface WechatMessageGateway {
  record Message(
      String openid,
      String templateId,
      String page,
      String state,
      Map<String, Map<String, String>> data) {
    @Override
    public String toString() {
      return "WechatMessage[redacted]";
    }
  }

  record Result(String code, boolean accepted, boolean retryable, boolean uncertain) {
    public static Result unknown() {
      return new Result("DELIVERY_UNKNOWN", false, false, true);
    }
  }

  Result send(Message message);
}
