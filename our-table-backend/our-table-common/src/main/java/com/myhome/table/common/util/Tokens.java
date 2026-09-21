package com.myhome.table.common.util;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class Tokens {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Tokens() {}

  public static String random() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
