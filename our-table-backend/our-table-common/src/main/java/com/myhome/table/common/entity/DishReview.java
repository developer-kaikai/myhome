package com.myhome.table.common.entity;

import java.time.Instant;

/** 菜品评价表。只承载数据；写入操作由归属服务负责。 */
public record DishReview(
    /** 主键ID */
    Long id,
    /** 餐单ID */
    Long mealOrderId,
    /** 菜品ID */
    Long dishId,
    /** 评价用户ID */
    Long reviewerUserId,
    /** 评价者昵称快照 */
    String reviewerNameSnapshot,
    /** 评价者头像快照 */
    String reviewerAvatarSnapshot,
    /** 星级评分 */
    Integer rating,
    /** 评价文字内容 */
    String commentText,
    /** 评价修改次数 */
    Integer modifyCount,
    /** 首次评价提交时间 */
    Instant firstSubmittedAt,
    /** 评价修改时间 */
    Instant modifiedAt,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
