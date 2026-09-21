package com.myhome.table.common.entity;

import java.time.Instant;

/** 通知发送任务表。只承载数据；写入操作由归属服务负责。 */
public record NotificationOutbox(
    /** 主键ID */
    Long id,
    /** 通知业务事件唯一键 */
    String eventKey,
    /** 可合并通知分组键 */
    String mergeKey,
    /** 通知事件类型 */
    String eventType,
    /** 关联业务类型 */
    String businessType,
    /** 关联业务数据ID */
    Long businessId,
    /** 通知接收用户ID */
    Long recipientUserId,
    /** 订阅消息模板记录ID */
    Long notificationTemplateId,
    /** 通知模板渲染数据JSON */
    String payloadJson,
    /** 消息点击跳转页面路径 */
    String pagePath,
    /** 通知发送状态 */
    String status,
    /** 合并窗口内首次事件时间 */
    Instant firstEventAt,
    /** 最早可处理时间 */
    Instant availableAt,
    /** 已尝试发送次数 */
    Integer attemptCount,
    /** 下次重试时间 */
    Instant nextRetryAt,
    /** 微信平台请求标识 */
    String platformRequestId,
    /** 微信平台错误码 */
    String platformErrorCode,
    /** 微信平台错误摘要 */
    String platformErrorExcerpt,
    /** 成功发送时间 */
    Instant sentAt,
    /** 任务最终结束时间 */
    Instant finishedAt,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
