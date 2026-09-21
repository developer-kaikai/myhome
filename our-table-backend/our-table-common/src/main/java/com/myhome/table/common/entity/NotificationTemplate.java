package com.myhome.table.common.entity;

import java.time.Instant;

/** 微信订阅消息模板表。只承载数据；写入操作由归属服务负责。 */
public record NotificationTemplate(
    /** 主键ID */
    Long id,
    /** 订阅消息对应的业务事件 */
    String businessEvent,
    /** 微信订阅消息模板ID */
    String wechatTemplateId,
    /** 消息跳转页面路径模板 */
    String pagePathTemplate,
    /** 是否启用 */
    Integer enabled,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
