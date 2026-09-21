package com.myhome.table.common.entity;

import java.time.Instant;

/** 聚会邀请链接表。只承载数据；写入操作由归属服务负责。 */
public record PartyInvitation(
    /** 主键ID */
    Long id,
    /** 聚会ID */
    Long partyId,
    /** 聚会邀请令牌SHA-256摘要 */
    String tokenHash,
    /** 聚会邀请状态 */
    String status,
    /** 邀请链接版本号 */
    Long invitationVersion,
    /** 创建用户ID */
    Long createdByUserId,
    /** 停用时间 */
    Instant disabledAt,
    /** 创建时间 */
    Instant createdAt,
    /** 数据库派生字段 */
    Integer activeMarker) {}
