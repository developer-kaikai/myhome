package com.myhome.table.common.util;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextRulesTest {
  @Test
  void nicknameLimitsVisibleCharacters() {
    assertThat(TextRules.required("  小厨师  ", 20)).isEqualTo("小厨师");
    assertThat(TextRules.required("👩‍🍳".repeat(20), 20)).isNotEmpty();
    assertThatThrownBy(() -> TextRules.required("厨".repeat(21), 20)).hasMessageContaining("长度");
  }

  @Test
  void rejectsBlankAndControlCharacters() {
    assertThatThrownBy(() -> TextRules.required(" ", 20)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> TextRules.required("a\nb", 20)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void passcodeIsCaseSensitiveAscii() {
    assertThat(TextRules.passcode("Abc123")).isEqualTo("Abc123");
    assertThatThrownBy(() -> TextRules.passcode("abc 123")).isInstanceOf(RuntimeException.class);
  }
}
