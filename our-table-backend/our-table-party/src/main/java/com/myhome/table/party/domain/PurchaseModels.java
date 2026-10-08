package com.myhome.table.party.domain;

import java.util.List;

public final class PurchaseModels {
  private PurchaseModels() {}

  public record Create(String name, String quantity, Long assigneeId) {}

  public record Change(
      Long version,
      String action,
      String name,
      String quantity,
      Long assigneeId,
      Long payerId,
      String amount,
      String zeroNote,
      String reason,
      Boolean confirmed) {}

  public record Item(
      long id,
      String name,
      String quantity,
      long creatorId,
      Long assigneeId,
      String assigneeName,
      String status,
      Long payerId,
      String payerName,
      String amount,
      String zeroNote,
      long version,
      boolean canEdit,
      boolean canClaim,
      boolean canRelease,
      boolean canPurchase) {}

  public record Advance(long userId, String name, String status, String amount) {}

  public record Summary(
      String total,
      String perPerson,
      int memberCount,
      int itemCount,
      int purchasedCount,
      List<Advance> advances) {}

  public record Listing(
      List<Item> items,
      int page,
      boolean hasMore,
      Summary summary,
      boolean canAdd,
      boolean creator,
      long userId,
      List<PartyModels.Member> candidates) {}
}
