package com.myhome.table.common.entity;

import java.time.Instant;

/** 聚会成员表。只承载数据；写入操作由归属服务负责。 */
public record PartyMember(
    /** 主键ID */
    Long id,
    /** 聚会ID */
    Long partyId,
    /** 用户ID */
    Long userId,
    /** 聚会成员昵称快照 */
    String displayNameSnapshot,
    /** 成员状态 */
    String memberStatus,
    /** 报名时间 */
    Instant joinedAt,
    /** 退出或移除时间 */
    Instant leftAt,
    /** 移除操作用户ID */
    Long removedByUserId,
    /** 移除成员原因 */
    String removedReason,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
