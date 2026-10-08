package com.myhome.table.ordering.notification;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationRendererTest {
  NotificationSettings settings(String policy) {
    return new NotificationSettings(
        true,
        "2026-10-01T00:00:00Z",
        "test_order_template_3988",
        "test_review_template_6633",
        policy,
        "developer");
  }

  NotificationRenderer.Facts facts(String chef) {
    return new NotificationRenderer.Facts(
        "王嘉尔餐厅",
        chef,
        "SUPPER",
        LocalDate.parse("2026-10-05"),
        Instant.parse("2026-10-05T14:00:00Z"),
        Instant.parse("2026-10-05T18:00:00Z"),
        2,
        List.of("牛腩", "青菜"));
  }

  @Test
  void usesBeijingTimeAndExplainsCutoffInsteadOfInventingMealTime() {
    var data = new NotificationRenderer(settings("CUTOFF")).render("ORDER_SUBMITTED", facts("Kai"));
    assertThat(data.get("time9").get("value")).isEqualTo("2026-10-05 22:00");
    assertThat(data.get("time57").get("value")).isEqualTo("2026-10-06 02:00");
    assertThat(data.get("thing3").get("value")).contains("点餐截止");
    assertThat(data.get("thing41").get("value")).isEqualTo("牛腩、青菜");
  }

  @Test
  void canOmitAppointmentOnlyWhenMatchingApprovedTemplate() {
    assertThat(new NotificationRenderer(settings("OMIT")).render("ORDER_SUBMITTED", facts("Kai")))
        .doesNotContainKey("time57");
    assertThat(settings("UNCONFIRMED").available("ORDER_SUBMITTED")).isFalse();
    assertThat(settings("UNCONFIRMED").available("REVIEW_INVITED")).isTrue();
  }

  @Test
  void sanitizesNamesAndDoesNotRemoveOrdinaryLetters() {
    var r = new NotificationRenderer(settings("CUTOFF"));
    assertThat(r.render("REVIEW_INVITED", facts("Kai")).get("name4").get("value")).isEqualTo("Kai");
    assertThat(r.render("REVIEW_INVITED", facts("朋友👨‍👩‍👧‍👦")).get("name4").get("value"))
        .isEqualTo("本店主厨");
    assertThat(NotificationRenderer.shortText("print\nctrl", 20)).isEqualTo("print ctrl");
  }

  @Test
  void truncationDoesNotSplitSurrogatePairs() {
    String result = NotificationRenderer.shortText("🍊".repeat(30), 20);
    assertThat(result.codePointCount(0, result.length())).isEqualTo(20);
    assertThat(result).endsWith("…");
    assertThat(Character.isLowSurrogate(result.charAt(result.length() - 2))).isTrue();
  }

  @Test
  void validatesActivationAndStateBeforeEnabling() {
    assertThatThrownBy(() -> new NotificationSettings(true, "", "", "", "OMIT", "formal"))
        .isInstanceOf(java.time.format.DateTimeParseException.class);
    assertThatThrownBy(() -> new NotificationSettings(false, "", "", "", "OMIT", "wrong"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
