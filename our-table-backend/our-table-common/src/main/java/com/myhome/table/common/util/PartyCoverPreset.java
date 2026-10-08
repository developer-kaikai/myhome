package com.myhome.table.common.util;

import com.myhome.table.common.exception.ApiException;
import java.util.Arrays;

/** 内置聚会封面白名单。版本文件名不可复用，历史活动保留原媒体引用。 */
public enum PartyCoverPreset {
  TABLE("builtin/party/table-v1.png"),
  HOT_POT("builtin/party/hot-pot-v1.png"),
  TEA("builtin/party/tea-v1.png");

  private final String objectKey;

  PartyCoverPreset(String objectKey) {
    this.objectKey = objectKey;
  }

  public String objectKey() {
    return objectKey;
  }

  public static String objectKeyFor(String code) {
    if (code == null || "DEFAULT".equals(code)) return null;
    return Arrays.stream(values())
        .filter(p -> p.name().equals(code))
        .findFirst()
        .orElseThrow(() -> ApiException.invalid("请选择有效的预设封面"))
        .objectKey;
  }

  public static String codeFor(String objectKey) {
    return Arrays.stream(values())
        .filter(p -> p.objectKey.equals(objectKey))
        .map(Enum::name)
        .findFirst()
        .orElse("DEFAULT");
  }
}
