package com.myhome.table.core.infrastructure;

import com.myhome.table.common.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretCipher {
  private final String encoded;
  private final SecureRandom random = new SecureRandom();

  public SecretCipher(@Value("${app.security.encryption-key:}") String encoded) {
    this.encoded = encoded;
  }

  public record Encrypted(byte[] ciphertext, byte[] nonce) {}

  private SecretKeySpec key() {
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(encoded);
      if (bytes.length != 32) throw new IllegalArgumentException();
      return new SecretKeySpec(bytes, "AES");
    } catch (Exception e) {
      throw new ApiException(503, "ENCRYPTION_NOT_CONFIGURED", "密令加密配置尚未就绪");
    }
  }

  public Encrypted encrypt(String text) {
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    return new Encrypted(
        crypt(Cipher.ENCRYPT_MODE, text.getBytes(StandardCharsets.UTF_8), nonce), nonce);
  }

  public String decrypt(byte[] ciphertext, byte[] nonce) {
    return new String(crypt(Cipher.DECRYPT_MODE, ciphertext, nonce), StandardCharsets.UTF_8);
  }

  private byte[] crypt(int mode, byte[] input, byte[] nonce) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(mode, key(), new GCMParameterSpec(128, nonce));
      c.updateAAD("our-table:daily-access:v1".getBytes(StandardCharsets.UTF_8));
      return c.doFinal(input);
    } catch (ApiException e) {
      throw e;
    } catch (GeneralSecurityException e) {
      throw ApiException.unavailable();
    }
  }
}
