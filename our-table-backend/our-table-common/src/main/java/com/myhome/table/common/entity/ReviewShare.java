package com.myhome.table.common.entity;

import java.time.Instant;

/** 评价分享链接表。只承载数据；写入操作由归属服务负责。 */
public record ReviewShare(
    /** 主键ID */
    Long id,
    /** 餐单ID */
    Long mealOrderId,
    /** 评价分享令牌SHA-256摘要 */
    String tokenHash,
    /** 创建用户ID */
    Long createdByUserId,
    /** 评价分享状态 */
    String status,
    /** 到期时间 */
    Instant expiresAt,
    /** 停用时间 */
    Instant disabledAt,
    /** 创建时间 */
    Instant createdAt) {}
