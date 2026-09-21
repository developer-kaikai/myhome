package com.myhome.table.common.entity;

import java.time.Instant;

/** 接口幂等记录表。只承载数据；写入操作由归属服务负责。 */
public record ApiIdempotencyRecord(
    /** 主键ID */
    Long id,
    /** 用户ID */
    Long userId,
    /** 接口路由标识 */
    String routeKey,
    /** 客户端幂等键 */
    String idempotencyKey,
    /** 请求内容SHA-256摘要 */
    String requestHash,
    /** 幂等请求处理状态 */
    String processingStatus,
    /** 缓存响应的HTTP状态码 */
    Integer httpStatus,
    /** 成功响应缓存JSON */
    String responseJson,
    /** 到期时间 */
    Instant expiresAt,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
