package com.myhome.table.common.entity;

import java.time.Instant;

/** 实际做菜明细表。只承载数据；写入操作由归属服务负责。 */
public record MealActualItem(
    /** 主键ID */
    Long id,
    /** 餐单ID */
    Long mealOrderId,
    /** 菜品ID */
    Long dishId,
    /** 实际菜品来源：原点餐或主厨补充 */
    String sourceType,
    /** 对应原点餐行ID */
    Long sourceOrderItemId,
    /** 确认实际菜品的主厨用户ID */
    Long confirmedByUserId,
    /** 菜品名称快照 */
    String dishNameSnapshot,
    /** 图片对象键快照 */
    String imageObjectKeySnapshot,
    /** 规范化规格JSON的SHA-256摘要 */
    String specKey,
    /** 规格选择快照JSON */
    String specSnapshot,
    /** 菜品份数 */
    Integer quantity,
    /** 创建时间 */
    Instant createdAt) {}
