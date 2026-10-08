package com.myhome.table.party.domain;

import java.time.Instant;
import java.util.List;

public final class PartyModels {
  private PartyModels() {}

  public record Create(
      String theme,
      String location,
      Instant startAt,
      Instant plannedEndAt,
      Boolean longDurationConfirmed,
      String coverPreset) {
    public Create(
        String theme,
        String location,
        Instant startAt,
        Instant plannedEndAt,
        Boolean longDurationConfirmed) {
      this(theme, location, startAt, plannedEndAt, longDurationConfirmed, null);
    }

    // 保持升级前未选封面的请求指纹，让旧客户端未决请求仍可同键核对。
    @Override
    public String toString() {
      return "Create[theme="
          + theme
          + ", location="
          + location
          + ", startAt="
          + startAt
          + ", plannedEndAt="
          + plannedEndAt
          + ", longDurationConfirmed="
          + longDurationConfirmed
          + (coverPreset == null ? "]" : ", coverPreset=" + coverPreset + "]");
    }
  }

  public record Change(Long version, String action) {}

  public record Edit(
      Long version,
      String theme,
      String location,
      Instant startAt,
      Instant plannedEndAt,
      Boolean longDurationConfirmed,
      String coverPreset) {
    public Edit(
        Long version,
        String theme,
        String location,
        Instant startAt,
        Instant plannedEndAt,
        Boolean longDurationConfirmed) {
      this(version, theme, location, startAt, plannedEndAt, longDurationConfirmed, null);
    }

    @Override
    public String toString() {
      return "Edit[version="
          + version
          + ", theme="
          + theme
          + ", location="
          + location
          + ", startAt="
          + startAt
          + ", plannedEndAt="
          + plannedEndAt
          + ", longDurationConfirmed="
          + longDurationConfirmed
          + (coverPreset == null ? "]" : ", coverPreset=" + coverPreset + "]");
    }
  }

  public record RemoveMember(Long version, String reason) {}

  public record Member(long userId, String name, String status, boolean canRemove) {
    public Member(long userId, String name, String status) {
      this(userId, name, status, false);
    }
  }

  public record Detail(
      long id,
      String theme,
      String location,
      Instant startAt,
      Instant plannedEndAt,
      String status,
      boolean reopened,
      Instant firstEndedAt,
      Instant lastEndedAt,
      long version,
      Integer memberCount,
      boolean creator,
      String membership,
      boolean canJoin,
      boolean canShare,
      boolean dailyAllowed,
      List<Member> members,
      long userId,
      boolean canEdit,
      Instant serverTime,
      String coverPreset) {}

  public record Item(
      long id,
      String theme,
      String location,
      Instant startAt,
      String status,
      boolean reopened,
      int memberCount,
      String membership,
      String coverPreset) {}

  public record Listing(List<Item> parties, int page, boolean hasMore) {}

  public record Share(String token) {}
}
