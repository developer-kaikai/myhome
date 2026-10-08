package com.myhome.table.party.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.IdempotencyService;
import com.myhome.table.common.security.UserContext;
import com.myhome.table.common.util.TextRules;
import com.myhome.table.party.domain.PartyModels.Member;
import com.myhome.table.party.domain.PurchaseModels.*;
import java.math.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PurchaseService {
  private final JdbcTemplate jdbc;
  private final PartyService parties;
  private final IdempotencyService idem;
  private final ObjectMapper json;
  private final Clock clock;

  public PurchaseService(
      JdbcTemplate jdbc,
      PartyService parties,
      IdempotencyService idem,
      ObjectMapper json,
      Clock clock) {
    this.jdbc = jdbc;
    this.parties = parties;
    this.idem = idem;
    this.json = json;
    this.clock = clock;
  }

  private record Fact(
      long id,
      String name,
      String quantity,
      long creator,
      Long assignee,
      String assigneeName,
      String status,
      Long payer,
      String payerName,
      BigDecimal amount,
      String zeroNote,
      long version,
      boolean removed) {}

  private ApiException unavailable() {
    return new ApiException(404, "PURCHASE_UNAVAILABLE", "采购记录不可访问");
  }

  // 所有读写先锁聚会行，与结束、退出、报名共用锁顺序和 READ_COMMITTED。
  private PartyService.Party scope(UserContext u, long id, boolean write) {
    var p = parties.lock(id);
    String membership = parties.member(id, u.userId());
    if (membership == null) throw unavailable();
    if (write) {
      if (!"JOINED".equals(membership))
        throw new ApiException(403, "PARTY_MEMBER_ONLY", "仅当前报名成员可以修改采购");
      parties.active(p);
    }
    return p;
  }

  private List<Fact> facts(long party, Long item) {
    return jdbc.query(
        "SELECT i.*,a.display_name_snapshot AS assignee_name,b.display_name_snapshot AS payer_name FROM party_purchase_item i LEFT JOIN party_member a ON a.party_id=i.party_id AND a.user_id=i.assignee_user_id LEFT JOIN party_member b ON b.party_id=i.party_id AND b.user_id=i.payer_user_id WHERE i.party_id=?"
            + (item == null ? " AND i.removed_at IS NULL ORDER BY i.id DESC" : " AND i.id=?"),
        (r, n) ->
            new Fact(
                r.getLong("id"),
                r.getString("item_name"),
                r.getString("quantity_text"),
                r.getLong("creator_user_id"),
                r.getObject("assignee_user_id", Long.class),
                r.getString("assignee_name"),
                r.getString("purchase_status"),
                r.getObject("payer_user_id", Long.class),
                r.getString("payer_name"),
                r.getBigDecimal("amount"),
                r.getString("zero_amount_note"),
                r.getLong("version"),
                r.getTimestamp("removed_at") != null),
        item == null ? new Object[] {party} : new Object[] {party, item});
  }

  private Fact fact(long party, long id) {
    var rows = facts(party, id);
    if (rows.isEmpty() || rows.get(0).removed) throw unavailable();
    return rows.get(0);
  }

  private boolean edits(UserContext u, PartyService.Party p, Fact f) {
    return p.creator() == u.userId()
        || Objects.equals(f.assignee, u.userId())
        || (f.creator == u.userId()
            && (f.assignee == null || Objects.equals(f.assignee, u.userId())));
  }

  private void editable(UserContext u, PartyService.Party p, Fact f) {
    if (!edits(u, p, f))
      throw new ApiException(403, "PURCHASE_FORBIDDEN", "只能编辑本人新建且未被他人认领或自己负责的项目");
  }

  private void creator(UserContext u, PartyService.Party p) {
    if (p.creator() != u.userId()) throw new ApiException(403, "PARTY_CREATOR_ONLY", "仅聚会创建者可以操作");
  }

  private void candidate(long party, Long id) {
    if (id != null && !"JOINED".equals(parties.member(party, id)))
      throw ApiException.invalid("请选择当前已报名成员");
  }

  private String money(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private String optional(String value, int max, int storage) {
    return value == null || value.isBlank() ? null : TextRules.required(value, max, storage, false);
  }

  private Item dto(UserContext u, PartyService.Party p, Fact f) {
    boolean write =
        "ACTIVE".equals(p.status()) && "JOINED".equals(parties.member(p.id(), u.userId()));
    boolean todo = "TODO".equals(f.status);
    return new Item(
        f.id,
        f.name,
        f.quantity,
        f.creator,
        f.assignee,
        f.assigneeName,
        f.status,
        todo ? null : f.payer,
        todo ? null : f.payerName,
        todo ? null : money(f.amount),
        todo ? null : f.zeroNote,
        f.version,
        write && edits(u, p, f),
        write && todo && f.assignee == null,
        write
            && todo
            && (p.creator() == u.userId() || Objects.equals(f.assignee, u.userId()))
            && f.assignee != null,
        write && (p.creator() == u.userId() || Objects.equals(f.assignee, u.userId())));
  }

  private Summary summary(long party, List<Fact> items) {
    var members =
        jdbc.query(
            "SELECT user_id,display_name_snapshot,member_status FROM party_member WHERE party_id=? ORDER BY joined_at,id",
            (r, n) -> new Member(r.getLong(1), r.getString(2), r.getString(3)),
            party);
    var advances = new HashMap<Long, BigDecimal>();
    BigDecimal total = BigDecimal.ZERO;
    int purchased = 0;
    for (var f : items)
      if ("PURCHASED".equals(f.status)) {
        total = total.add(f.amount);
        purchased++;
        advances.merge(f.payer, f.amount, BigDecimal::add);
      }
    int count = (int) members.stream().filter(m -> "JOINED".equals(m.status())).count();
    return new Summary(
        money(total),
        count == 0
            ? null
            : total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP).toPlainString(),
        count,
        items.size(),
        purchased,
        members.stream()
            .map(
                m ->
                    new Advance(
                        m.userId(),
                        m.name(),
                        m.status(),
                        money(advances.getOrDefault(m.userId(), BigDecimal.ZERO))))
            .toList());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Listing list(UserContext u, long party, int page) {
    if (page < 1 || page > 25) throw ApiException.invalid("页码无效");
    var p = scope(u, party, false);
    var items = facts(party, null);
    return new Listing(
        items.stream().skip((page - 1L) * 20).limit(20).map(f -> dto(u, p, f)).toList(),
        page,
        items.size() > page * 20,
        summary(party, items),
        "ACTIVE".equals(p.status()) && "JOINED".equals(parties.member(party, u.userId())),
        p.creator() == u.userId(),
        u.userId(),
        jdbc.query(
            "SELECT user_id,display_name_snapshot,member_status FROM party_member WHERE party_id=? AND member_status='JOINED' ORDER BY joined_at,id",
            (r, n) -> new Member(r.getLong(1), r.getString(2), r.getString(3)),
            party));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Item get(UserContext u, long party, long id) {
    var p = scope(u, party, false);
    return dto(u, p, fact(party, id));
  }

  private void audit(
      UserContext u, long party, String action, Fact before, Fact after, String reason) {
    try {
      jdbc.update(
          "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id,request_id,before_json,after_json,summary_json) VALUES('PARTY',?,?,?,?,?,?,?)",
          party,
          "PURCHASE_" + action,
          u.userId(),
          MDC.get("requestId"),
          before == null ? null : json.writeValueAsString(before),
          json.writeValueAsString(after),
          json.writeValueAsString(
              Map.of("itemId", after.id, "reason", reason == null ? "" : reason)));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Purchase audit serialization failed", e);
    }
    jdbc.update("UPDATE party SET version=version+1 WHERE id=?", party);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Item create(UserContext u, long party, Create f, String key) {
    if (f == null) throw ApiException.invalid("请填写物品信息");
    String name = TextRules.required(f.name(), 20), quantity = optional(f.quantity(), 10, 255);
    return idem.execute(
        u.userId(),
        "purchase.create:" + party,
        key,
        f.toString(),
        Item.class,
        () -> {
          var p = scope(u, party, true);
          candidate(party, f.assigneeId());
          if (f.assigneeId() != null && !Objects.equals(f.assigneeId(), u.userId())) creator(u, p);
          if (facts(party, null).size() >= 500)
            throw new ApiException(409, "PURCHASE_FULL", "当前采购清单已达500项，请先移除不需要的项目");
          var holder = new GeneratedKeyHolder();
          jdbc.update(
              c -> {
                var s =
                    c.prepareStatement(
                        "INSERT INTO party_purchase_item(party_id,item_name,quantity_text,creator_user_id,assignee_user_id) VALUES(?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                s.setLong(1, party);
                s.setString(2, name);
                s.setString(3, quantity);
                s.setLong(4, u.userId());
                s.setObject(5, f.assigneeId());
                return s;
              },
              holder);
          var after = fact(party, holder.getKey().longValue());
          audit(u, party, "CREATE", null, after, null);
          return dto(u, p, after);
        });
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Item change(UserContext u, long party, long id, Change f, String key) {
    if (f == null
        || f.version() == null
        || f.version() < 0
        || f.action() == null
        || !Set.of("EDIT", "CLAIM", "RELEASE", "ASSIGN", "PURCHASE", "REVERT", "REMOVE")
            .contains(f.action())) throw ApiException.invalid("采购操作无效");
    return idem.execute(
        u.userId(),
        "purchase.change:" + party + ":" + id,
        key,
        f.toString(),
        Item.class,
        () -> {
          var p = scope(u, party, true);
          var before = fact(party, id);
          if (before.version != f.version()) throw ApiException.conflict();
          String reason = optional(f.reason(), 100, 1000);
          switch (f.action()) {
            case "EDIT" -> {
              editable(u, p, before);
              jdbc.update(
                  "UPDATE party_purchase_item SET item_name=?,quantity_text=? WHERE id=?",
                  TextRules.required(f.name(), 20),
                  optional(f.quantity(), 10, 255),
                  id);
            }
            case "CLAIM" -> {
              if (!"TODO".equals(before.status) || before.assignee != null)
                throw ApiException.conflict();
              if (jdbc.update(
                      "UPDATE party_purchase_item SET assignee_user_id=? WHERE id=? AND purchase_status='TODO' AND assignee_user_id IS NULL",
                      u.userId(),
                      id)
                  != 1) throw ApiException.conflict();
            }
            case "RELEASE" -> {
              if (!"TODO".equals(before.status) || before.assignee == null)
                throw ApiException.conflict();
              if (!Objects.equals(before.assignee, u.userId())) creator(u, p);
              jdbc.update("UPDATE party_purchase_item SET assignee_user_id=NULL WHERE id=?", id);
            }
            case "ASSIGN" -> {
              creator(u, p);
              candidate(party, f.assigneeId());
              jdbc.update(
                  "UPDATE party_purchase_item SET assignee_user_id=? WHERE id=?",
                  f.assigneeId(),
                  id);
            }
            case "PURCHASE" -> {
              if (p.creator() != u.userId() && !Objects.equals(before.assignee, u.userId()))
                throw new ApiException(403, "PURCHASE_FORBIDDEN", "请先认领；负责人或创建者才可填写购买金额");
              if (f.amount() == null
                  || !f.amount().matches("(?:0|[1-9][0-9]{0,4})(?:\\.[0-9]{1,2})?"))
                throw ApiException.invalid("金额须为0—99999.99元，最多两位小数");
              BigDecimal amount = new BigDecimal(f.amount());
              String zero =
                  amount.signum() == 0
                      ? TextRules.required(f.zeroNote(), 100, 1000, false)
                      : optional(f.zeroNote(), 100, 1000);
              if (f.payerId() == null) throw ApiException.invalid("请选择实际垫付人");
              boolean bought = "PURCHASED".equals(before.status);
              if (!bought || !Objects.equals(before.payer, f.payerId()))
                candidate(party, f.payerId());
              if (bought) {
                if (reason == null) throw ApiException.invalid("修改已购金额须填写原因");
                if (!Objects.equals(before.payer, f.payerId())) creator(u, p);
              }
              jdbc.update(
                  "UPDATE party_purchase_item SET purchase_status='PURCHASED',payer_user_id=?,amount=?,zero_amount_note=?,purchased_at=?,correction_reason=? WHERE id=?",
                  f.payerId(),
                  amount,
                  zero,
                  Timestamp.from(clock.instant()),
                  reason,
                  id);
            }
            case "REVERT" -> {
              editable(u, p, before);
              if (!"PURCHASED".equals(before.status)) throw ApiException.conflict();
              if (reason == null || !Boolean.TRUE.equals(f.confirmed()))
                throw ApiException.invalid("撤回已购须填写原因并再次确认");
              // 原付款人、金额和购买时间保留在行及审计中；汇总只计算 PURCHASED。
              jdbc.update(
                  "UPDATE party_purchase_item SET purchase_status='TODO',correction_reason=? WHERE id=?",
                  reason,
                  id);
              if (before.assignee != null
                  && !"JOINED".equals(parties.member(party, before.assignee)))
                jdbc.update("UPDATE party_purchase_item SET assignee_user_id=NULL WHERE id=?", id);
            }
            case "REMOVE" -> {
              editable(u, p, before);
              if ("PURCHASED".equals(before.status)
                  && (reason == null || !Boolean.TRUE.equals(f.confirmed())))
                throw ApiException.invalid("移除已购物品须填写原因并再次确认");
              jdbc.update(
                  "UPDATE party_purchase_item SET removed_at=?,removed_by_user_id=?,remove_reason=? WHERE id=?",
                  Timestamp.from(clock.instant()),
                  u.userId(),
                  reason,
                  id);
            }
            default -> throw ApiException.invalid("操作无效");
          }
          jdbc.update("UPDATE party_purchase_item SET version=version+1 WHERE id=?", id);
          var after = facts(party, id).get(0);
          audit(u, party, f.action(), before, after, reason);
          return dto(u, p, after);
        });
  }
}
