package com.myhome.table.ordering.notification;

import com.myhome.table.common.util.BusinessTime;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class NotificationRenderer {
  public record Facts(
      String restaurant,
      String chef,
      String meal,
      LocalDate date,
      Instant submitted,
      Instant cutoff,
      int diners,
      List<String> dishes) {}

  private final NotificationSettings settings;

  public NotificationRenderer(NotificationSettings settings) {
    this.settings = settings;
  }

  public static String shortText(String text, int max) {
    String safe = Objects.toString(text, "").replaceAll("[\\p{Cntrl}]", " ").strip();
    if (safe.isEmpty()) safe = "我们的餐桌";
    return safe.codePointCount(0, safe.length()) > max
        ? safe.substring(0, safe.offsetByCodePoints(0, max - 1)) + "…"
        : safe;
  }

  private String time(Instant at) {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(BusinessTime.ZONE).format(at);
  }

  public Map<String, Map<String, String>> render(String event, Facts f) {
    String meal =
        switch (f.meal()) {
          case "BREAKFAST" -> "早餐";
          case "LUNCH" -> "午餐";
          case "DINNER" -> "晚餐";
          default -> "宵夜";
        };
    var data = new LinkedHashMap<String, Map<String, String>>();
    if ("ORDER_SUBMITTED".equals(event)) {
      put(data, "thing12", shortText(f.restaurant(), 20));
      put(data, "thing41", shortText(String.join("、", f.dishes()), 20));
      put(data, "time9", time(f.submitted()));
      if ("CUTOFF".equals(settings.orderTimePolicy())) put(data, "time57", time(f.cutoff()));
      else if (!"OMIT".equals(settings.orderTimePolicy()))
        throw new IllegalStateException("Order time field unresolved");
      put(
          data,
          "thing3",
          shortText(
              "CUTOFF".equals(settings.orderTimePolicy())
                  ? meal + "·预约时间为点餐截止"
                  : f.date() + "·" + meal + "·" + f.diners() + "人",
              20));
    } else if ("REVIEW_INVITED".equals(event)) {
      put(data, "thing6", shortText(f.restaurant() + "·" + meal, 20));
      String name = Objects.toString(f.chef(), "").strip();
      if (!name.matches("[\\p{IsHan}A-Za-z· ]{1,10}")) name = "本店主厨";
      put(data, "name4", name);
      put(data, "date2", f.date().toString());
      put(data, "thing9", "完成后7天内，欢迎评价这顿饭");
    } else throw new IllegalArgumentException("Unsupported event");
    return data;
  }

  private void put(Map<String, Map<String, String>> data, String key, String value) {
    data.put(key, Map.of("value", value));
  }
}
