package com.myhome.table.ordering.domain;

import com.myhome.table.common.util.BusinessTime.Meal;
import java.time.*;
import java.util.List;

public final class ReviewModels {
  private ReviewModels() {}

  public record Write(Long dishId, Integer rating, String comment) {}

  public record Modify(Long version, Integer rating, String comment) {}

  public record Review(
      long id,
      long orderId,
      long dishId,
      String dishName,
      long reviewerId,
      String reviewerName,
      String reviewerAvatar,
      int rating,
      String comment,
      int modifyCount,
      long version,
      Instant firstSubmittedAt,
      Instant modifiedAt,
      boolean canModify) {}

  public record Food(
      long dishId,
      String dishName,
      String imageObjectKey,
      int quantity,
      List<String> specifications,
      Double average,
      long reviewCount,
      Review mine) {}

  public record Sheet(
      long orderId,
      String restaurantName,
      LocalDate date,
      Meal meal,
      Instant serverTime,
      Instant deadline,
      boolean canSubmit,
      int reviewedCount,
      List<Food> foods) {}

  public record Share(String token, Instant expiresAt) {}

  public record DishReviews(
      List<Review> reviews, int page, boolean hasMore, Double average, long reviewCount) {}

  public record Reviews(List<Review> reviews, int page, boolean hasMore) {}

  public record Invitation(
      long orderId,
      String restaurantName,
      LocalDate date,
      Meal meal,
      Instant deadline,
      int total,
      int reviewed) {}

  public record Invitations(List<Invitation> invitations, int page, boolean hasMore) {}
}
