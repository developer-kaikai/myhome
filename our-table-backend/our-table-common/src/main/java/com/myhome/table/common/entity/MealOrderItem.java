package com.myhome.table.common.entity;

import java.time.Instant;

/** 原始点餐明细表。只承载数据；写入操作由归属服务负责。 */
public record MealOrderItem(
    /** 主键ID */
    Long id,
    /** 餐单ID */
    Long mealOrderId,
    /** 菜品ID */
    Long dishId,
    /** 原添加人用户ID */
    Long contributorUserId,
    /** 原添加人昵称快照 */
    String contributorNameSnapshot,
    /** 菜品名称快照 */
    String dishNameSnapshot,
    /** 图片对象键快照 */
    String imageObjectKeySnapshot,
    /** 规范化规格JSON的SHA-256摘要 */
    String specKey,
    /** 规格选择快照JSON */
    String specSnapshot,
    /** 点餐行阶段：待提交或已提交 */
    String itemStage,
    /** 菜品份数 */
    Integer quantity,
    /** 点餐行并入正式餐单的时间 */
    Instant submittedAt,
    /** 移除时间 */
    Instant removedAt,
    /** 移除操作用户ID */
    Long removedByUserId,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt,
    /** 数据库派生字段 */
    Integer activeMarker) {}
