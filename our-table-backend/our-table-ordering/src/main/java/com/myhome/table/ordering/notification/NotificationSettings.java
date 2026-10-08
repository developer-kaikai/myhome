package com.myhome.table.ordering.notification;

import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record NotificationSettings(
    @Value("${app.notifications.enabled:false}") boolean enabled,
    @Value("${app.notifications.activated-at:}") String activatedAt,
    @Value("${app.notifications.order-template-id:}") String orderTemplate,
    @Value("${app.notifications.review-template-id:}") String reviewTemplate,
    @Value("${app.notifications.order-time-policy:UNCONFIRMED}") String orderTimePolicy,
    @Value("${app.notifications.miniprogram-state:formal}") String state) {
  public NotificationSettings {
    if (!Set.of("formal", "trial", "developer").contains(state))
      throw new IllegalArgumentException("Invalid notification version");
    if (!Set.of("UNCONFIRMED", "OMIT", "CUTOFF").contains(orderTimePolicy))
      throw new IllegalArgumentException("Invalid notification time policy");
    if (enabled) Instant.parse(activatedAt);
    for (String id : new String[] {orderTemplate, reviewTemplate})
      if (!id.isEmpty() && !id.matches("[A-Za-z0-9_-]{20,100}"))
        throw new IllegalArgumentException("Invalid notification template ID");
  }

  public String template(String event) {
    return switch (event) {
      case "ORDER_SUBMITTED" -> orderTemplate;
      case "REVIEW_INVITED" -> reviewTemplate;
      default -> "";
    };
  }

  public boolean available(String event) {
    return enabled
        && !template(event).isEmpty()
        && (!"ORDER_SUBMITTED".equals(event) || !"UNCONFIRMED".equals(orderTimePolicy));
  }

  public Instant activation() {
    return Instant.parse(activatedAt);
  }
}
