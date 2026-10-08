package com.myhome.table.ordering.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(
    name = "app.notifications.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class NotificationJob {
  private final NotificationDelivery delivery;

  public NotificationJob(NotificationDelivery delivery) {
    this.delivery = delivery;
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 10000)
  public void scan() {
    delivery.scan();
  }
}
