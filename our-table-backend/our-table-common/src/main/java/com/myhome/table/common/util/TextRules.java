package com.myhome.table.common.util;

import com.myhome.table.common.exception.ApiException;
import java.util.regex.Pattern;

public final class TextRules {
  private static final Pattern GRAPHEME = Pattern.compile("\\X");

  private TextRules() {}

  public static String required(String value, int max) {
    return required(value, max, 255, false);
  }

  public static String required(String value, int max, int storageMax, boolean multiline) {
    if (value == null || value.isBlank()) throw ApiException.invalid("内容不能为空");
    String result = value.strip();
    if (result.codePointCount(0, result.length()) > storageMax
        || result
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r')))
        || GRAPHEME.matcher(result).results().count() > max)
      throw ApiException.invalid("内容格式或长度不符合要求");
    return result;
  }

  public static String passcode(String value) {
    if (value == null || !value.matches("[A-Za-z0-9]{6,20}"))
      throw ApiException.invalid("密令须为6—20位字母或数字");
    return value;
  }
}
