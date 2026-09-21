package com.myhome.table.core.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.myhome.table.common.util.Tokens;
import org.junit.jupiter.api.Test;

class SecretCipherTest {
  @Test
  void randomNonceAndAuthenticatedCiphertext() {
    var cipher = new SecretCipher(Tokens.random());
    var a = cipher.encrypt("Abc123");
    var b = cipher.encrypt("Abc123");
    assertThat(a.nonce()).isNotEqualTo(b.nonce());
    assertThat(cipher.decrypt(a.ciphertext(), a.nonce())).isEqualTo("Abc123");
    a.ciphertext()[0] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(a.ciphertext(), a.nonce()))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void refusesMissingKey() {
    assertThatThrownBy(() -> new SecretCipher("").encrypt("Abc123")).hasMessageContaining("加密配置");
  }
}
