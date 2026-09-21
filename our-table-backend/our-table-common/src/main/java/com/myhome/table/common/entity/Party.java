package com.myhome.table.common.entity;

import java.time.Instant;

/** 聚会活动表。只承载数据；写入操作由归属服务负责。 */
public record Party(
    /** 主键ID */
    Long id,
    /** 聚会编号 */
    String partyNo,
    /** 创建用户ID */
    Long creatorUserId,
    /** 聚会主题 */
    String theme,
    /** 聚会地点说明 */
    String locationText,
    /** 聚会开始时间 */
    Instant startAt,
    /** 计划结束时间 */
    Instant plannedEndAt,
    /** 封面图片资源ID */
    Long coverMediaId,
    /** 聚会状态 */
    String status,
    /** 是否曾被重开 */
    Integer isReopened,
    /** 首次结束时间 */
    Instant firstEndedAt,
    /** 最近一次结束时间 */
    Instant lastEndedAt,
    /** 最近一次重开时间 */
    Instant reopenedAt,
    /** 聚会结束备注 */
    String endNote,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
