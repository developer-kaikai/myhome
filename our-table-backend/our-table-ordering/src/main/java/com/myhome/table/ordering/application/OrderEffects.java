package com.myhome.table.ordering.application;

import com.myhome.table.ordering.infrastructure.CartRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 与餐单共用事务，仅落业务事件；微信送达必须由后续平台适配器确认。 */
@Component
public class OrderEffects {
  private final CartRepository repo;
  private final Clock clock;

  public OrderEffects(CartRepository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  public void participant(
      long order,
      long user,
      boolean initiator,
      boolean orderer,
      boolean collaborator,
      boolean chef) {
    repo.jdbc()
        .update(
            "INSERT INTO meal_order_participant(meal_order_id,user_id,display_name_snapshot,is_initiator,is_orderer,is_collaborator,is_chef,last_acted_at) SELECT ?,id,nickname,?,?,?,?,? FROM app_user WHERE id=? ON DUPLICATE KEY UPDATE is_initiator=GREATEST(is_initiator,?),is_orderer=GREATEST(is_orderer,?),is_collaborator=GREATEST(is_collaborator,?),is_chef=GREATEST(is_chef,?),last_acted_at=?",
            order,
            initiator,
            orderer,
            collaborator,
            chef,
            Timestamp.from(clock.instant()),
            user,
            initiator,
            orderer,
            collaborator,
            chef,
            Timestamp.from(clock.instant()));
  }

  public Long chef(long restaurant) {
    return repo.jdbc()
        .queryForObject("SELECT chef_user_id FROM restaurant WHERE id=?", Long.class, restaurant);
  }

  public void changed(long order, String type) {
    Long restaurant =
        repo.jdbc()
            .queryForObject("SELECT restaurant_id FROM meal_order WHERE id=?", Long.class, order);
    Long chef = chef(restaurant);
    if (chef == null) return;
    String merge = "order:" + order + ":chef:" + chef + ":changes";
    var pending =
        repo.jdbc()
            .queryForList(
                "SELECT id FROM notification_outbox WHERE merge_key=? AND status='PENDING' AND available_at>? ORDER BY id LIMIT 1 FOR UPDATE",
                Long.class,
                merge,
                Timestamp.from(clock.instant()));
    if (!pending.isEmpty()) {
      repo.jdbc()
          .update(
              "UPDATE notification_outbox SET payload_json=JSON_OBJECT('orderId',?,'latestVersion', (SELECT version FROM meal_order WHERE id=?)) WHERE id=?",
              order,
              order,
              pending.get(0));
      return;
    }
    long version = repo.order(order, true).version();
    event(
        order,
        chef,
        type,
        "order:" + order + ":change:" + version + ":" + chef,
        merge,
        clock.instant().plusSeconds(30));
  }

  public void event(
      long order, long recipient, String type, String key, String merge, Instant available) {
    repo.jdbc()
        .update(
            "INSERT INTO notification_outbox(event_key,merge_key,event_type,business_type,business_id,recipient_user_id,payload_json,page_path,first_event_at,available_at) VALUES(?,?,?,'MEAL_ORDER',?,?,JSON_OBJECT('orderId',?,'deliveryCapability','OUTBOX'),?,?,?) ON DUPLICATE KEY UPDATE id=id",
            key,
            merge,
            type,
            order,
            recipient,
            order,
            ("REVIEW_INVITED".equals(type)
                    ? "subpackages/reviews/sheet?id="
                    : "pages/orders/detail?id=")
                + order,
            Timestamp.from(clock.instant()),
            Timestamp.from(available));
  }

  public String notificationState(long order, long user) {
    var states =
        repo.jdbc()
            .queryForList(
                "SELECT status,platform_error_code FROM notification_outbox WHERE business_id=? AND business_type='MEAL_ORDER' AND recipient_user_id=? ORDER BY id DESC LIMIT 1",
                order,
                user);
    if (states.isEmpty()) return "NOT_REQUESTED";
    var row = states.get(0);
    return "DELIVERY_UNKNOWN".equals(row.get("platform_error_code"))
        ? "UNKNOWN"
        : row.get("status").toString();
  }

  public void cancelOld(long order) {
    repo.jdbc()
        .update(
            "UPDATE notification_outbox SET status='CANCELLED',finished_at=? WHERE business_type='MEAL_ORDER' AND business_id=? AND status IN ('PENDING','FAILED_RETRYABLE') AND event_type IN ('ORDER_SUBMITTED','ORDER_APPENDED','ORDER_CHANGED','MEAL_CONFIRM_DUE')",
            Timestamp.from(clock.instant()),
            order);
  }
}
