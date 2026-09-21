package com.myhome.table.common.entity;

import java.math.BigDecimal;
import java.time.Instant;

/** 聚会采购明细表。只承载数据；写入操作由归属服务负责。 */
public record PartyPurchaseItem(
    /** 主键ID */
    Long id,
    /** 聚会ID */
    Long partyId,
    /** 采购物品名称 */
    String itemName,
    /** 采购数量说明 */
    String quantityText,
    /** 创建用户ID */
    Long creatorUserId,
    /** 当前采购负责人用户ID */
    Long assigneeUserId,
    /** 采购状态 */
    String purchaseStatus,
    /** 实际垫付用户ID */
    Long payerUserId,
    /** 实际垫付金额 */
    BigDecimal amount,
    /** 零元采购说明 */
    String zeroAmountNote,
    /** 确认购买时间 */
    Instant purchasedAt,
    /** 金额或采购信息修正原因 */
    String correctionReason,
    /** 移除时间 */
    Instant removedAt,
    /** 移除操作用户ID */
    Long removedByUserId,
    /** 移除采购项原因 */
    String removeReason,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
