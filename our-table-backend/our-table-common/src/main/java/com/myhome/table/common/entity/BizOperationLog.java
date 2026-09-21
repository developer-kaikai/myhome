package com.myhome.table.common.entity;

import java.time.Instant;

/** 业务操作审计表。只承载数据；写入操作由归属服务负责。 */
public record BizOperationLog(
    /** 主键ID */
    Long id,
    /** 关联业务类型 */
    String businessType,
    /** 关联业务数据ID */
    Long businessId,
    /** 操作类型 */
    String operationType,
    /** 操作用户ID */
    Long actorUserId,
    /** 请求追踪ID */
    String requestId,
    /** 操作前的数据快照JSON */
    String beforeJson,
    /** 操作后的数据快照JSON */
    String afterJson,
    /** 操作摘要JSON */
    String summaryJson,
    /** 创建时间 */
    Instant createdAt) {}
