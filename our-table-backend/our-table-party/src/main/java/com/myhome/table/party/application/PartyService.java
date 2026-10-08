package com.myhome.table.party.application;

import com.myhome.table.common.exception.ApiException;
import com.myhome.table.common.persistence.IdempotencyService;
import com.myhome.table.common.security.UserContext;
import com.myhome.table.common.util.*;
import com.myhome.table.party.domain.PartyModels.*;
import com.myhome.table.party.infrastructure.InvitationCipher;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartyService {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final IdempotencyService idem;
  private final InvitationCipher cipher;

  public PartyService(
      JdbcTemplate jdbc, Clock clock, IdempotencyService idem, InvitationCipher cipher) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.idem = idem;
    this.cipher = cipher;
  }

  record Party(
      long id,
      long creator,
      String theme,
      String location,
      Instant start,
      Instant end,
      String status,
      boolean reopened,
      Instant first,
      Instant last,
      long version,
      Long cover) {}

  private Instant instant(ResultSet r, String column) throws SQLException {
    var t = r.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private Party row(ResultSet r) throws SQLException {
    return new Party(
        r.getLong("id"),
        r.getLong("creator_user_id"),
        r.getString("theme"),
        r.getString("location_text"),
        instant(r, "start_at"),
        instant(r, "planned_end_at"),
        r.getString("status"),
        r.getBoolean("is_reopened"),
        instant(r, "first_ended_at"),
        instant(r, "last_ended_at"),
        r.getLong("version"),
        r.getObject("cover_media_id", Long.class));
  }

  Party lock(long id) {
    var rows = jdbc.query("SELECT * FROM party WHERE id=? FOR UPDATE", (r, n) -> row(r), id);
    if (rows.isEmpty()) throw forbidden();
    return rows.get(0);
  }

  private ApiException forbidden() {
    return new ApiException(404, "PARTY_UNAVAILABLE", "聚会不可访问，请从有效邀请或本人聚会记录进入");
  }

  String member(long id, long user) {
    var rows =
        jdbc.queryForList(
            "SELECT member_status FROM party_member WHERE party_id=? AND user_id=?",
            String.class,
            id,
            user);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private String name(long user) {
    return jdbc.queryForObject("SELECT nickname FROM app_user WHERE id=?", String.class, user);
  }

  private int count(long id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM party_member WHERE party_id=? AND member_status='JOINED'",
        Integer.class,
        id);
  }

  private boolean invitation(long id) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM party_invitation WHERE party_id=? AND status='ACTIVE' AND token_ciphertext IS NOT NULL",
            Integer.class,
            id)
        > 0;
  }

  private Detail detail(UserContext u, Party p, boolean invited) {
    String membership = member(p.id, u.userId());
    boolean creator = p.creator == u.userId(), related = creator || membership != null;
    if (!related && !invited) throw forbidden();
    boolean joined = "JOINED".equals(membership);
    return new Detail(
        p.id,
        p.theme,
        p.location,
        p.start,
        p.end,
        p.status,
        p.reopened,
        p.first,
        p.last,
        p.version,
        related ? count(p.id) : null,
        creator,
        membership,
        invited && !joined && "ACTIVE".equals(p.status),
        joined && "ACTIVE".equals(p.status) && invitation(p.id),
        u.dailyAllowed(clock.instant()),
        related
            ? jdbc.query(
                "SELECT user_id,display_name_snapshot,member_status FROM party_member WHERE party_id=? ORDER BY joined_at,id",
                (r, n) ->
                    new Member(
                        r.getLong(1),
                        r.getString(2),
                        r.getString(3),
                        creator
                            && joined
                            && "ACTIVE".equals(p.status)
                            && r.getLong(1) != p.creator
                            && "JOINED".equals(r.getString(3))),
                p.id)
            : List.of(),
        u.userId(),
        creator && joined && "ACTIVE".equals(p.status),
        clock.instant(),
        coverCode(p.cover));
  }

  private String coverCode(Long id) {
    if (id == null) return "DEFAULT";
    var keys =
        jdbc.queryForList(
            "SELECT object_key FROM media_asset WHERE id=? AND usage_type='PARTY_PRESET' AND owner_user_id IS NULL AND status='ACTIVE' AND deleted_at IS NULL",
            String.class,
            id);
    return keys.isEmpty() ? "DEFAULT" : PartyCoverPreset.codeFor(keys.get(0));
  }

  private Long coverMedia(String code) {
    String objectKey = PartyCoverPreset.objectKeyFor(code);
    if (objectKey == null) return null;
    var ids =
        jdbc.queryForList(
            "SELECT id FROM media_asset WHERE object_key=? AND usage_type='PARTY_PRESET' AND owner_user_id IS NULL AND status='ACTIVE' AND deleted_at IS NULL FOR SHARE",
            Long.class,
            objectKey);
    if (ids.isEmpty()) throw new ApiException(409, "PRESET_UNAVAILABLE", "封面暂不可用，请改用默认封面");
    return ids.get(0);
  }

  void active(Party p) {
    if (!"ACTIVE".equals(p.status))
      throw new ApiException(409, "PARTY_ENDED", "聚会已结束，创建者重开后才能继续报名或修改");
  }

  private void audit(UserContext u, long id, String operation) {
    jdbc.update(
        "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id,request_id,summary_json) VALUES('PARTY',?,?,?,?,JSON_OBJECT('version',(SELECT version FROM party WHERE id=?)))",
        id,
        operation,
        u.userId(),
        MDC.get("requestId"),
        id);
  }

  private void newInvitation(UserContext u, long id) {
    jdbc.update(
        "UPDATE party_invitation SET status='DISABLED',disabled_at=? WHERE party_id=? AND status='ACTIVE'",
        Timestamp.from(clock.instant()),
        id);
    String token = Tokens.random();
    var encrypted = cipher.encrypt(token);
    jdbc.update(
        "INSERT INTO party_invitation(party_id,token_hash,invitation_version,created_by_user_id,token_ciphertext,token_nonce,encryption_key_version) SELECT ?,?,COALESCE(MAX(invitation_version),0)+1,?,?,?,'v1' FROM party_invitation WHERE party_id=?",
        id,
        Tokens.sha256(token),
        u.userId(),
        encrypted.ciphertext(),
        encrypted.nonce(),
        id);
  }

  private void validate(Create f) {
    if (f == null || f.startAt() == null || f.plannedEndAt() == null)
      throw ApiException.invalid("请填写开始与结束时间");
    if (f.startAt().isBefore(clock.instant())
        || !f.plannedEndAt().isAfter(f.startAt())
        || f.plannedEndAt().isAfter(Instant.parse("9999-12-31T23:59:59Z")))
      throw ApiException.invalid("开始不得早于现在，结束必须晚于开始");
    if (Duration.between(f.startAt(), f.plannedEndAt()).compareTo(Duration.ofDays(7)) > 0
        && !Boolean.TRUE.equals(f.longDurationConfirmed()))
      throw new ApiException(409, "LONG_DURATION_CONFIRMATION_REQUIRED", "聚会持续超过7天，请再次确认");
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Detail create(UserContext u, Create f, String key) {
    u.requireDaily(clock.instant());
    if (f == null) throw ApiException.invalid("请填写聚会信息");
    String theme = TextRules.required(f.theme(), 20),
        location = TextRules.required(f.location(), 30);
    return idem.execute(
        u.userId(),
        "party.create",
        key,
        f.toString(),
        Detail.class,
        () -> {
          validate(f);
          Long cover = coverMedia(f.coverPreset());
          var holder = new GeneratedKeyHolder();
          jdbc.update(
              c -> {
                var s =
                    c.prepareStatement(
                        "INSERT INTO party(party_no,creator_user_id,theme,location_text,start_at,planned_end_at,cover_media_id) VALUES(?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                s.setString(1, UUID.randomUUID().toString().replace("-", ""));
                s.setLong(2, u.userId());
                s.setString(3, theme);
                s.setString(4, location);
                s.setTimestamp(5, Timestamp.from(f.startAt()));
                s.setTimestamp(6, Timestamp.from(f.plannedEndAt()));
                s.setObject(7, cover);
                return s;
              },
              holder);
          long id = holder.getKey().longValue();
          jdbc.update(
              "INSERT INTO party_member(party_id,user_id,display_name_snapshot,joined_at) VALUES(?,?,?,?)",
              id,
              u.userId(),
              name(u.userId()),
              Timestamp.from(clock.instant()));
          newInvitation(u, id);
          audit(u, id, "CREATE");
          return detail(u, lock(id), false);
        });
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Detail get(UserContext u, long id) {
    return detail(u, lock(id), false);
  }

  private record InvitationScope(Party party, boolean valid) {}

  private InvitationScope invited(UserContext u, String token, boolean allowExistingMember) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw forbidden();
    String hash = Tokens.sha256(token);
    var ids =
        jdbc.queryForList(
            "SELECT party_id FROM party_invitation WHERE token_hash=?", Long.class, hash);
    if (ids.isEmpty()) throw forbidden();
    Party p = lock(ids.get(0));
    var valid =
        jdbc.queryForList(
            "SELECT id FROM party_invitation WHERE party_id=? AND token_hash=? AND status='ACTIVE' FOR UPDATE",
            Long.class,
            p.id,
            hash);
    if (valid.isEmpty() && !(allowExistingMember && member(p.id, u.userId()) != null))
      throw forbidden();
    return new InvitationScope(p, !valid.isEmpty());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Detail preview(UserContext u, String token) {
    var scope = invited(u, token, true);
    return detail(u, scope.party(), scope.valid());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Detail join(UserContext u, String token, String key) {
    return idem.execute(
        u.userId(),
        "party.join",
        key,
        Tokens.sha256(token),
        Detail.class,
        () -> {
          Party p = invited(u, token, false).party();
          active(p);
          if ("JOINED".equals(member(p.id, u.userId()))) return detail(u, p, true);
          if (count(p.id) >= 100) throw new ApiException(409, "PARTY_FULL", "成员已达100人，暂不能报名");
          jdbc.update(
              "INSERT INTO party_member(party_id,user_id,display_name_snapshot,joined_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE member_status='JOINED',display_name_snapshot=VALUES(display_name_snapshot),joined_at=VALUES(joined_at),left_at=NULL,removed_by_user_id=NULL,removed_reason=NULL,version=version+1",
              p.id,
              u.userId(),
              name(u.userId()),
              Timestamp.from(clock.instant()));
          jdbc.update("UPDATE party SET version=version+1 WHERE id=?", p.id);
          audit(u, p.id, "JOIN");
          return detail(u, lock(p.id), true);
        });
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Share share(UserContext u, long id) {
    Party p = lock(id);
    if (!"JOINED".equals(member(id, u.userId()))) throw forbidden();
    active(p);
    var rows =
        jdbc.queryForList(
            "SELECT token_ciphertext,token_nonce FROM party_invitation WHERE party_id=? AND status='ACTIVE'",
            id);
    if (rows.isEmpty() || rows.get(0).get("token_ciphertext") == null)
      throw new ApiException(409, "INVITATION_DISABLED", "邀请已停用，请创建者重新生成");
    return new Share(
        cipher.decrypt(
            (byte[]) rows.get(0).get("token_ciphertext"), (byte[]) rows.get(0).get("token_nonce")));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Detail change(UserContext u, long id, Change f, String key) {
    if (f == null
        || f.version() == null
        || f.version() < 0
        || f.action() == null
        || !Set.of("END", "REOPEN", "EXIT", "DISABLE_INVITATION", "REGENERATE_INVITATION")
            .contains(f.action())) throw ApiException.invalid("操作无效");
    return idem.execute(
        u.userId(),
        "party.change:" + id,
        key,
        f.toString(),
        Detail.class,
        () -> {
          Party p = lock(id);
          String membership = member(id, u.userId());
          if (!"JOINED".equals(membership)) throw forbidden();
          if (!"EXIT".equals(f.action()) && p.creator != u.userId())
            throw new ApiException(403, "PARTY_CREATOR_ONLY", "仅聚会创建者可以操作");
          if (p.version != f.version()) throw ApiException.conflict();
          if (!"REOPEN".equals(f.action())) active(p);
          switch (f.action()) {
            case "END" -> {
              active(p);
              jdbc.update(
                  "UPDATE party SET status='ENDED',first_ended_at=COALESCE(first_ended_at,?),last_ended_at=? WHERE id=?",
                  Timestamp.from(clock.instant()),
                  Timestamp.from(clock.instant()),
                  id);
            }
            case "REOPEN" -> {
              if (!"ENDED".equals(p.status)) throw ApiException.conflict();
              jdbc.update(
                  "UPDATE party SET status='ACTIVE',is_reopened=1,reopened_at=? WHERE id=?",
                  Timestamp.from(clock.instant()),
                  id);
            }
            case "EXIT" -> {
              active(p);
              if (p.creator == u.userId())
                throw new ApiException(409, "CREATOR_CANNOT_EXIT", "创建者不能退出，可手动结束聚会");
              if (jdbc.queryForObject(
                      "SELECT COUNT(*) FROM party_purchase_item WHERE party_id=? AND assignee_user_id=? AND purchase_status='TODO' AND removed_at IS NULL",
                      Integer.class,
                      id,
                      u.userId())
                  > 0) throw new ApiException(409, "PENDING_PURCHASES", "请先解除待采购任务，再退出聚会");
              jdbc.update(
                  "UPDATE party_member SET member_status='EXITED',left_at=?,version=version+1 WHERE party_id=? AND user_id=?",
                  Timestamp.from(clock.instant()),
                  id,
                  u.userId());
            }
            case "DISABLE_INVITATION" ->
                jdbc.update(
                    "UPDATE party_invitation SET status='DISABLED',disabled_at=? WHERE party_id=? AND status='ACTIVE'",
                    Timestamp.from(clock.instant()),
                    id);
            case "REGENERATE_INVITATION" -> newInvitation(u, id);
            default -> throw ApiException.invalid("操作无效");
          }
          jdbc.update("UPDATE party SET version=version+1 WHERE id=?", id);
          audit(u, id, f.action());
          return detail(u, lock(id), false);
        });
  }

  private void owner(UserContext u, Party p, Long version) {
    if (member(p.id, u.userId()) == null) throw forbidden();
    if (p.creator != u.userId()) throw new ApiException(403, "PARTY_CREATOR_ONLY", "仅聚会创建者可以操作");
    active(p);
    if (!"JOINED".equals(member(p.id, u.userId()))) throw forbidden();
    if (p.version != version) throw ApiException.conflict();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Detail edit(UserContext u, long id, Edit f, String key) {
    if (f == null
        || f.version() == null
        || f.version() < 0
        || f.startAt() == null
        || f.plannedEndAt() == null) throw ApiException.invalid("请填写聚会信息与版本");
    String theme = TextRules.required(f.theme(), 20),
        location = TextRules.required(f.location(), 30);
    return idem.execute(
        u.userId(),
        "party.edit:" + id,
        key,
        f.toString(),
        Detail.class,
        () -> {
          Party p = lock(id);
          owner(u, p, f.version());
          Instant current = clock.instant();
          boolean startChanged = !p.start.equals(f.startAt()),
              endChanged = !p.end.equals(f.plannedEndAt());
          if (startChanged && !p.start.isAfter(current))
            throw new ApiException(409, "PARTY_ALREADY_STARTED", "聚会已开始，请保留原开始时间");
          if ((startChanged && f.startAt().isBefore(current))
              || !f.plannedEndAt().isAfter(f.startAt())
              || (endChanged && !f.plannedEndAt().isAfter(current))
              || f.plannedEndAt().isAfter(Instant.parse("9999-12-31T23:59:59Z")))
            throw ApiException.invalid("新开始时间不得早于现在，新结束时间须晚于现在及开始时间");
          if ((startChanged || endChanged)
              && Duration.between(f.startAt(), f.plannedEndAt()).compareTo(Duration.ofDays(7)) > 0
              && !Boolean.TRUE.equals(f.longDurationConfirmed()))
            throw new ApiException(409, "LONG_DURATION_CONFIRMATION_REQUIRED", "聚会持续超过7天，请再次确认");
          jdbc.update(
              "UPDATE party SET theme=?,location_text=?,start_at=?,planned_end_at=?,cover_media_id=?,version=version+1 WHERE id=?",
              theme,
              location,
              Timestamp.from(f.startAt()),
              Timestamp.from(f.plannedEndAt()),
              f.coverPreset() == null ? p.cover : coverMedia(f.coverPreset()),
              id);
          jdbc.update(
              "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id,request_id,summary_json) VALUES('PARTY',?,'EDIT_INFO',?,?,JSON_OBJECT('before',JSON_OBJECT('theme',?,'location',?,'startAt',?,'plannedEndAt',?,'version',?,'coverPreset',?), 'after',JSON_OBJECT('theme',?,'location',?,'startAt',?,'plannedEndAt',?,'version',?,'coverPreset',?)))",
              id,
              u.userId(),
              MDC.get("requestId"),
              p.theme,
              p.location,
              p.start.toString(),
              p.end.toString(),
              p.version,
              coverCode(p.cover),
              theme,
              location,
              f.startAt().toString(),
              f.plannedEndAt().toString(),
              p.version + 1,
              f.coverPreset() == null ? coverCode(p.cover) : f.coverPreset());
          return detail(u, lock(id), false);
        });
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Detail removeMember(UserContext u, long id, long userId, RemoveMember f, String key) {
    if (f == null || f.version() == null || f.version() < 0 || userId <= 0)
      throw ApiException.invalid("移除操作无效");
    String reason =
        f.reason() == null || f.reason().isBlank()
            ? null
            : TextRules.required(f.reason(), 100, 1000, false);
    return idem.execute(
        u.userId(),
        "party.removeMember:" + id + ":" + userId,
        key,
        f.toString(),
        Detail.class,
        () -> {
          Party p = lock(id);
          owner(u, p, f.version());
          if (userId == p.creator)
            throw new ApiException(409, "CREATOR_CANNOT_EXIT", "创建者不能被移除，可手动结束聚会");
          if (!"JOINED".equals(member(id, userId)))
            throw new ApiException(409, "MEMBER_NOT_JOINED", "该成员已退出或移除，请刷新成员列表");
          if (jdbc.queryForObject(
                  "SELECT COUNT(*) FROM party_purchase_item WHERE party_id=? AND assignee_user_id=? AND purchase_status='TODO' AND removed_at IS NULL",
                  Integer.class,
                  id,
                  userId)
              > 0) throw new ApiException(409, "PENDING_PURCHASES", "请先在采购清单解除该成员的待采购任务，再移除");
          jdbc.update(
              "UPDATE party_member SET member_status='REMOVED',left_at=?,removed_by_user_id=?,removed_reason=?,version=version+1 WHERE party_id=? AND user_id=?",
              Timestamp.from(clock.instant()),
              u.userId(),
              reason,
              id,
              userId);
          newInvitation(u, id);
          jdbc.update("UPDATE party SET version=version+1 WHERE id=?", id);
          jdbc.update(
              "INSERT INTO biz_operation_log(business_type,business_id,operation_type,actor_user_id,request_id,summary_json) VALUES('PARTY',?,'REMOVE_MEMBER',?,?,JSON_OBJECT('memberUserId',?,'beforeStatus','JOINED','afterStatus','REMOVED','reason',?,'invitationRotated',true,'beforeVersion',?,'version',?))",
              id,
              u.userId(),
              MDC.get("requestId"),
              userId,
              reason,
              p.version,
              p.version + 1);
          return detail(u, lock(id), false);
        });
  }

  @Transactional(readOnly = true)
  public Listing list(UserContext u, String state, int page) {
    if (!Set.of("ACTIVE", "ENDED").contains(state) || page < 1 || page > 10000)
      throw ApiException.invalid("列表范围无效");
    var rows =
        jdbc.query(
            "SELECT p.*,m.member_status,ma.object_key AS cover_key,(SELECT COUNT(*) FROM party_member n WHERE n.party_id=p.id AND n.member_status='JOINED') AS member_count FROM party p JOIN party_member m ON m.party_id=p.id AND m.user_id=? LEFT JOIN media_asset ma ON ma.id=p.cover_media_id AND ma.status='ACTIVE' AND ma.deleted_at IS NULL AND ma.usage_type='PARTY_PRESET' AND ma.owner_user_id IS NULL WHERE p.status=? ORDER BY p.start_at DESC,p.id DESC LIMIT 21 OFFSET ?",
            (r, n) ->
                new Item(
                    r.getLong("id"),
                    r.getString("theme"),
                    r.getString("location_text"),
                    instant(r, "start_at"),
                    r.getString("status"),
                    r.getBoolean("is_reopened"),
                    r.getInt("member_count"),
                    r.getString("member_status"),
                    PartyCoverPreset.codeFor(r.getString("cover_key"))),
            u.userId(),
            state,
            (page - 1) * 20);
    return new Listing(rows.stream().limit(20).toList(), page, rows.size() > 20);
  }
}
