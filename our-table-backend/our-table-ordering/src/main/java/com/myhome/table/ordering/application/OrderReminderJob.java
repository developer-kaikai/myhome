package com.myhome.table.ordering.application;

import com.myhome.table.ordering.infrastructure.CartRepository;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableScheduling
@ConditionalOnProperty(
    name = "our-table.ordering.reminders-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrderReminderJob {
  private final CartRepository repo;
  private final OrderEffects effects;
  private final Clock clock;
  private final TransactionTemplate tx;

  public OrderReminderJob(
      CartRepository repo, OrderEffects effects, Clock clock, TransactionTemplate tx) {
    this.repo = repo;
    this.effects = effects;
    this.clock = clock;
    this.tx = tx;
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void scan() {
    var ids =
        repo.jdbc()
            .queryForList(
                "SELECT id FROM meal_order WHERE status='IN_PROGRESS' AND cutoff_at<=? AND reminder_created_at IS NULL ORDER BY cutoff_at LIMIT 100",
                Long.class,
                Timestamp.from(clock.instant()));
    for (long id : ids)
      tx.executeWithoutResult(
          s -> {
            var initial = repo.order(id, false);
            repo.restaurant(initial.restaurantId(), true);
            var o = repo.order(id, true);
            if (!"IN_PROGRESS".equals(o.status()) || clock.instant().isBefore(o.cutoff())) return;
            var rows =
                repo.jdbc()
                    .queryForList(
                        "SELECT id FROM meal_order WHERE id=? AND reminder_created_at IS NULL FOR UPDATE",
                        Long.class,
                        id);
            if (rows.isEmpty()) return;
            repo.jdbc()
                .update(
                    "UPDATE meal_order SET reminder_created_at=? WHERE id=?",
                    Timestamp.from(clock.instant()),
                    id);
            Long chef = effects.chef(o.restaurantId());
            if (chef != null)
              effects.event(
                  id,
                  chef,
                  "MEAL_CONFIRM_DUE",
                  "order:" + id + ":due:" + chef,
                  null,
                  clock.instant());
          });
  }
}
