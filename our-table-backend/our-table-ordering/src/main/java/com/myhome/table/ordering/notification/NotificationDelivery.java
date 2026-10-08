package com.myhome.table.ordering.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 业务事务只落事件；短事务预占后才调用微信。网络结果不明时不盲目重发。 */
@Service
public class NotificationDelivery {
  record Attempt(long id, String token, WechatMessageGateway.Message message) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final NotificationSettings settings;
  private final NotificationRenderer renderer;
  private final WechatMessageGateway gateway;
  private final ObjectMapper json;

  public NotificationDelivery(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      Clock clock,
      NotificationSettings settings,
      NotificationRenderer renderer,
      WechatMessageGateway gateway,
      ObjectMapper json) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
    this.settings = settings;
    this.renderer = renderer;
    this.gateway = gateway;
    this.json = json;
  }

  public void scan() {
    if (!settings.enabled()) return;
    var stale =
        jdbc.queryForList(
            "SELECT id,attempt_token FROM notification_outbox WHERE status='SENDING' AND sending_started_at<? LIMIT 50",
            Timestamp.from(clock.instant().minusSeconds(300)));
    for (var row : stale)
      finish(
          ((Number) row.get("id")).longValue(),
          (String) row.get("attempt_token"),
          WechatMessageGateway.Result.unknown());
    var due =
        jdbc.queryForList(
            "SELECT id FROM notification_outbox WHERE (status='PENDING' AND available_at<=?) OR (status='FAILED_RETRYABLE' AND next_retry_at<=?) ORDER BY id LIMIT 50",
            Long.class,
            now(),
            now());
    for (long id : due) {
      Attempt attempt = tx.execute(s -> claim(id));
      if (attempt == null) continue;
      WechatMessageGateway.Result result;
      try {
        result = gateway.send(attempt.message());
        if (result == null) result = WechatMessageGateway.Result.unknown();
      } catch (Exception e) {
        result = WechatMessageGateway.Result.unknown();
      }
      finish(id, attempt.token(), result);
    }
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private void skip(long id, String code) {
    jdbc.update(
        "UPDATE notification_outbox SET status='SKIPPED',platform_error_code=?,platform_error_excerpt=?,finished_at=? WHERE id=?",
        code,
        description(code),
        now(),
        id);
  }

  private Attempt claim(long id) {
    var first =
        jdbc.queryForList("SELECT business_id FROM notification_outbox WHERE id=?", Long.class, id);
    if (first.isEmpty()) return null;
    var restaurant =
        jdbc.queryForList(
            "SELECT restaurant_id FROM meal_order WHERE id=?", Long.class, first.get(0));
    if (restaurant.isEmpty()) return null;
    // 与提交、取消和确认统一采用餐厅 → 餐单 → 通知 → 模板 → 授权的锁序。
    jdbc.queryForList("SELECT id FROM restaurant WHERE id=? FOR UPDATE", restaurant.get(0));
    var order = jdbc.queryForMap("SELECT * FROM meal_order WHERE id=? FOR UPDATE", first.get(0));
    var row = jdbc.queryForMap("SELECT * FROM notification_outbox WHERE id=? FOR UPDATE", id);
    String state = row.get("status").toString();
    if (!Set.of("PENDING", "FAILED_RETRYABLE").contains(state)) return null;
    Instant due = at("PENDING".equals(state) ? row.get("available_at") : row.get("next_retry_at"));
    if (due == null || clock.instant().isBefore(due)) return null;
    String event = row.get("event_type").toString();
    long recipient = ((Number) row.get("recipient_user_id")).longValue();
    if (at(row.get("first_event_at")).isBefore(settings.activation())) {
      skip(id, "BEFORE_ACTIVATION");
      return null;
    }
    if (!settings.available(event)) {
      skip(id, "NO_APPROVED_TEMPLATE");
      return null;
    }
    var kitchen =
        jdbc.queryForMap(
            "SELECT r.chef_user_id,u.nickname FROM restaurant r LEFT JOIN app_user u ON u.id=r.chef_user_id WHERE r.id=?",
            restaurant.get(0));
    boolean valid;
    if ("ORDER_SUBMITTED".equals(event))
      valid =
          "IN_PROGRESS".equals(order.get("status"))
              && kitchen.get("chef_user_id") != null
              && ((Number) kitchen.get("chef_user_id")).longValue() == recipient;
    else
      valid =
          "COMPLETED".equals(order.get("status"))
              && at(order.get("completed_at")) != null
              && clock.instant().isBefore(at(order.get("completed_at")).plus(Duration.ofDays(7)))
              && jdbc.queryForObject(
                      "SELECT COUNT(*) FROM meal_order_participant WHERE meal_order_id=? AND user_id=? AND is_orderer=1",
                      Integer.class,
                      first.get(0),
                      recipient)
                  > 0;
    if (!valid) {
      skip(id, "BUSINESS_EXPIRED");
      return null;
    }
    var template =
        jdbc.queryForMap(
            "SELECT * FROM notification_template WHERE business_event=? FOR UPDATE", event);
    if (!settings.template(event).equals(template.get("wechat_template_id"))
        || !Boolean.TRUE.equals(template.get("enabled"))
            && (!(template.get("enabled") instanceof Number enabled) || enabled.intValue() != 1)) {
      skip(id, "TEMPLATE_CHANGED");
      return null;
    }
    var grants =
        jdbc.queryForList(
            "SELECT * FROM wx_subscription_grant WHERE user_id=? AND notification_template_id=? FOR UPDATE",
            recipient,
            template.get("id"));
    if (grants.isEmpty()) {
      skip(id, "NO_AUTHORIZATION");
      return null;
    }
    var grant = grants.get(0);
    int available =
        ((Number) grant.get("accepted_count")).intValue()
            - ((Number) grant.get("reserved_count")).intValue()
            - ((Number) grant.get("consumed_count")).intValue();
    if (!"ACCEPT".equals(grant.get("latest_response")) || available < 1) {
      skip(id, "NO_AUTHORIZATION");
      return null;
    }
    String openid =
        jdbc.queryForObject("SELECT openid FROM app_user WHERE id=?", String.class, recipient);
    if (openid == null || openid.isBlank()) {
      skip(id, "RECIPIENT_UNAVAILABLE");
      return null;
    }
    var dishes =
        jdbc.queryForList(
            "SELECT DISTINCT dish_name_snapshot FROM meal_order_item WHERE meal_order_id=? AND item_stage='SUBMITTED' AND removed_at IS NULL ORDER BY dish_name_snapshot",
            String.class,
            first.get(0));
    var facts =
        new NotificationRenderer.Facts(
            (String) order.get("restaurant_name_snapshot"),
            (String) kitchen.get("nickname"),
            (String) order.get("meal_period"),
            date(order.get("meal_date")),
            at(order.get("submitted_at")),
            at(order.get("cutoff_at")),
            ((Number) order.get("diner_count")).intValue(),
            dishes);
    var data = renderer.render(event, facts);
    String payload;
    try {
      payload = json.writeValueAsString(data);
    } catch (Exception e) {
      throw new IllegalStateException("Notification rendering failed");
    }
    String token = UUID.randomUUID().toString();
    jdbc.update(
        "UPDATE wx_subscription_grant SET reserved_count=reserved_count+1 WHERE id=?",
        grant.get("id"));
    jdbc.update(
        "UPDATE notification_outbox SET status='SENDING',notification_template_id=?,payload_json=?,attempt_token=?,sending_started_at=?,subscription_version=?,attempt_count=attempt_count+1,next_retry_at=NULL WHERE id=?",
        template.get("id"),
        payload,
        token,
        now(),
        grant.get("version"),
        id);
    return new Attempt(
        id,
        token,
        new WechatMessageGateway.Message(
            openid,
            settings.template(event),
            row.get("page_path").toString(),
            settings.state(),
            data));
  }

  void finish(long id, String token, WechatMessageGateway.Result result) {
    tx.executeWithoutResult(
        s -> {
          var rows =
              jdbc.queryForList(
                  "SELECT * FROM notification_outbox WHERE id=? AND status='SENDING' AND attempt_token=? FOR UPDATE",
                  id,
                  token);
          if (rows.isEmpty()) return;
          var row = rows.get(0);
          var grant =
              jdbc.queryForMap(
                  "SELECT * FROM wx_subscription_grant WHERE user_id=? AND notification_template_id=? FOR UPDATE",
                  row.get("recipient_user_id"),
                  row.get("notification_template_id"));
          boolean used = result.accepted() || result.uncertain();
          jdbc.update(
              "UPDATE wx_subscription_grant SET reserved_count=reserved_count-1,consumed_count=consumed_count+?,last_consumed_at=IF(?,?,last_consumed_at) WHERE id=?",
              used ? 1 : 0,
              used,
              now(),
              grant.get("id"));
          if ("43101".equals(result.code())
              && Objects.equals(
                  ((Number) grant.get("version")).longValue(),
                  ((Number) row.get("subscription_version")).longValue()))
            jdbc.update(
                "UPDATE wx_subscription_grant SET latest_response='UNKNOWN',accepted_count=reserved_count+consumed_count,version=version+1 WHERE id=?",
                grant.get("id"));
          boolean retry =
              result.retryable()
                  && !result.uncertain()
                  && ((Number) row.get("attempt_count")).intValue() < 3;
          String status = result.accepted() ? "SENT" : retry ? "FAILED_RETRYABLE" : "FAILED_FINAL";
          jdbc.update(
              "UPDATE notification_outbox SET status=?,platform_error_code=?,platform_error_excerpt=?,sent_at=?,finished_at=?,next_retry_at=?,attempt_token=NULL,sending_started_at=NULL WHERE id=?",
              status,
              result.code(),
              description(result.code()),
              result.accepted() ? now() : null,
              retry ? null : now(),
              retry
                  ? Timestamp.from(
                      clock
                          .instant()
                          .plusSeconds(((Number) row.get("attempt_count")).intValue() * 30L))
                  : null,
              id);
        });
  }

  static Instant at(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDateTime v) return v.toInstant(ZoneOffset.UTC);
    return ((Timestamp) value).toInstant();
  }

  static LocalDate date(Object value) {
    return value instanceof LocalDate v ? v : ((java.sql.Date) value).toLocalDate();
  }

  private String description(String code) {
    return switch (code) {
      case "0" -> "微信已受理发送，未确认送达或已读";
      case "DELIVERY_UNKNOWN" -> "发送结果不明，停止自动重发";
      case "43101" -> "微信无可用授权，需要重新订阅";
      case "BEFORE_ACTIVATION" -> "接入前的历史事件不补发";
      case "NO_AUTHORIZATION" -> "用户未授权或本地可用次数不足";
      case "NO_APPROVED_TEMPLATE" -> "尚无匹配且启用的获批模板";
      case "BUSINESS_EXPIRED" -> "餐单状态、接收资格或评价期限已变化";
      case "TEMPLATE_CHANGED" -> "模板配置已变化";
      case "RECIPIENT_UNAVAILABLE" -> "接收用户暂不可用";
      default -> "发送未成功，仅保留错误码";
    };
  }
}
