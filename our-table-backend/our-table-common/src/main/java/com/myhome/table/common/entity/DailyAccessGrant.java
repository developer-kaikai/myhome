package com.myhome.table.common.entity;

import java.time.Instant;

/** 日常准入授权记录表。只承载数据；写入操作由归属服务负责。 */
public record DailyAccessGrant(
    /** 主键ID */
    Long id,
    /** 用户ID */
    Long userId,
    /** 密令版本号 */
    Long secretVersion,
    /** 授权撤销代次 */
    Long grantGeneration,
    /** 授权签发时间 */
    Instant issuedAt,
    /** 到期时间 */
    Instant expiresAt,
    /** 授权撤销时间 */
    Instant revokedAt,
    /** 创建时间 */
    Instant createdAt) {}
