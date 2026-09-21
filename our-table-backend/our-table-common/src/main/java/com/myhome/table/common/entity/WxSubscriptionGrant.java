package com.myhome.table.common.entity;

import java.time.Instant;

/** 微信订阅授权记账表。只承载数据；写入操作由归属服务负责。 */
public record WxSubscriptionGrant(
    /** 主键ID */
    Long id,
    /** 用户ID */
    Long userId,
    /** 订阅消息模板记录ID */
    Long notificationTemplateId,
    /** 最近一次订阅授权结果 */
    String latestResponse,
    /** 累计同意的订阅次数 */
    Integer acceptedCount,
    /** 已预占待发送的订阅次数 */
    Integer reservedCount,
    /** 已消费的订阅次数 */
    Integer consumedCount,
    /** 最近一次订阅授权时间 */
    Instant lastConsentedAt,
    /** 最近一次订阅消费时间 */
    Instant lastConsumedAt,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
