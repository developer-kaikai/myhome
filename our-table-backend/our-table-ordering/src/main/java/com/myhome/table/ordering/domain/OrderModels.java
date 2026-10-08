package com.myhome.table.ordering.domain;

import com.myhome.table.common.util.BusinessTime.Meal;
import com.myhome.table.ordering.domain.CartModels.*;
import java.time.*;
import java.util.List;

public final class OrderModels {
  private OrderModels() {}

  public record Submit(Long version, Integer dinerCount, String remark, String menuFingerprint) {}

  public record Cancel(Long version, String reason) {}

  public record Participant(
      long userId,
      String name,
      boolean initiator,
      boolean orderer,
      boolean collaborator,
      boolean chef) {}

  public record InvalidItem(long itemId, String reason) {}

  public record Detail(
      long id,
      String orderNo,
      long restaurantId,
      String restaurantName,
      LocalDate date,
      Meal meal,
      String status,
      long version,
      int dinerCount,
      String remark,
      String cancelReason,
      Instant cutoff,
      Instant serverTime,
      Instant submittedAt,
      Long initiatorId,
      List<Item> items,
      List<Item> pendingItems,
      List<ActualItem> actualItems,
      Instant completedAt,
      Instant reviewDeadline,
      boolean canConfirm,
      List<Participant> participants,
      boolean canModify,
      boolean canMeta,
      boolean canCancel,
      boolean canReopen,
      boolean ownChef,
      boolean confirmationDue,
      String notificationState) {}

  public record Preview(
      Detail order, List<InvalidItem> invalidItems, boolean canSubmit, String menuFingerprint) {}

  public record ActualInput(
      Long sourceOrderItemId,
      Long dishId,
      Long dishVersion,
      List<Long> optionIds,
      Integer quantity) {}

  public record Complete(Long version, List<ActualInput> items) {}

  public record ActualItem(
      long id,
      long dishId,
      String dishName,
      String imageObjectKey,
      List<Selection> selections,
      int quantity,
      String sourceType) {}

  public record Candidate(long id, String name, long version, List<Dimension> dimensions) {}

  public record Dimension(long id, String name, List<Option> options) {}

  public record Option(long id, String name, boolean isDefault) {}

  public record Confirmation(Detail order, List<Candidate> candidates) {}

  public record Page(List<Detail> orders, int page, int size, boolean hasMore) {}
}
