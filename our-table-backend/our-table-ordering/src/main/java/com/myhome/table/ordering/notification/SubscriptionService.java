package com.myhome.table.ordering.notification;

import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.IdempotencyService;
import com.myhome.table.common.security.UserContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 微信弹窗回调仅是本地额度记账依据，实际是否可发仍以微信发送回执为准。 */
@Service
public class SubscriptionService implements ApplicationRunner {
  public record Consent(Long userId, String event, String templateId, String response) {}

  public record Subscription(
      long userId,
      String event,
      String templateId,
      boolean enabled,
      String response,
      int available) {}

  private final JdbcTemplate jdbc;
  private final IdempotencyService idem;
  private final NotificationSettings settings;
  private final Clock clock;

  public SubscriptionService(
      JdbcTemplate jdbc, IdempotencyService idem, NotificationSettings settings, Clock clock) {
    this.jdbc = jdbc;
    this.idem = idem;
    this.settings = settings;
    this.clock = clock;
  }

  @org.springframework.transaction.annotation.Transactional
  @Override
  public void run(ApplicationArguments args) {
    // 模板 ID 变更不能把旧模板授权沿用到新模板。先停用，再清空未预占额度。
    for (String event : List.of("ORDER_SUBMITTED", "REVIEW_INVITED")) {
      String id = settings.template(event);
      if (id.isEmpty()) continue;
      var old =
          jdbc.queryForMap(
              "SELECT id,wechat_template_id FROM notification_template WHERE business_event=? FOR UPDATE",
              event);
      if (old.get("wechat_template_id") != null && !id.equals(old.get("wechat_template_id"))) {
        int inFlight =
            jdbc.queryForObject(
                "SELECT COALESCE(SUM(reserved_count),0) FROM wx_subscription_grant WHERE notification_template_id=?",
                Integer.class,
                old.get("id"));
        if (inFlight > 0)
          throw new IllegalStateException(
              "Notification template change requires no in-flight sends");
        jdbc.update(
            "UPDATE wx_subscription_grant SET latest_response='UNKNOWN',accepted_count=consumed_count,version=version+1 WHERE notification_template_id=?",
            old.get("id"));
      }
      jdbc.update(
          "UPDATE notification_template SET wechat_template_id=?,enabled=?,version=version+1 WHERE business_event=?",
          id,
          settings.available(event),
          event);
    }
  }

  public List<Subscription> list(UserContext user) {
    user.requireDaily(clock.instant());
    return List.of(
        status(user.userId(), "ORDER_SUBMITTED", user.chefRestaurantId() != null),
        status(user.userId(), "REVIEW_INVITED", true));
  }

  private Subscription status(long user, String event, boolean permitted) {
    var rows =
        jdbc.queryForList(
            "SELECT g.latest_response,g.accepted_count-g.reserved_count-g.consumed_count AS available FROM wx_subscription_grant g JOIN notification_template t ON t.id=g.notification_template_id WHERE g.user_id=? AND t.business_event=?",
            user,
            event);
    boolean available = settings.available(event) && permitted;
    return new Subscription(
        user,
        event,
        available ? settings.template(event) : "",
        available,
        rows.isEmpty() ? "UNKNOWN" : rows.get(0).get("latest_response").toString(),
        rows.isEmpty() ? 0 : ((Number) rows.get(0).get("available")).intValue());
  }

  public Subscription consent(UserContext user, Consent input, String key) {
    user.requireDaily(clock.instant());
    if (input == null
        || !Objects.equals(input.userId(), user.userId())
        || !List.of("ORDER_SUBMITTED", "REVIEW_INVITED").contains(input.event()))
      throw ApiException.invalid("不支持的订阅类型");
    if ("ORDER_SUBMITTED".equals(input.event())) user.requireChef();
    if (!settings.available(input.event())
        || !settings.template(input.event()).equals(input.templateId()))
      throw new ApiException(409, "TEMPLATE_UNAVAILABLE", "提醒配置已更新，请刷新后重试");
    if (!Set.of("ACCEPT", "REJECT", "BAN", "UNKNOWN")
        .contains(Objects.toString(input.response(), ""))) throw ApiException.invalid("无效的授权结果");
    return idem.execute(
        user.userId(),
        "notification-consent",
        key,
        input.toString(),
        Subscription.class,
        () -> {
          var template =
              jdbc.queryForMap(
                  "SELECT id,wechat_template_id FROM notification_template WHERE business_event=? FOR UPDATE",
                  input.event());
          if (!input.templateId().equals(template.get("wechat_template_id")))
            throw new ApiException(409, "TEMPLATE_UNAVAILABLE", "提醒配置已更新，请刷新后重试");
          boolean accepted = "ACCEPT".equals(input.response());
          jdbc.update(
              "INSERT INTO wx_subscription_grant(user_id,notification_template_id,latest_response,accepted_count,last_consented_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE latest_response=VALUES(latest_response),accepted_count=accepted_count+VALUES(accepted_count),last_consented_at=VALUES(last_consented_at),version=version+1",
              user.userId(),
              template.get("id"),
              input.response(),
              accepted ? 1 : 0,
              Timestamp.from(clock.instant()));
          return status(user.userId(), input.event(), true);
        });
  }
}
