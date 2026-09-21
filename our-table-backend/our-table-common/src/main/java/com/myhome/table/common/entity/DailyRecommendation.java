package com.myhome.table.common.entity;

import java.time.Instant;
import java.time.LocalDate;

/** 每日时令推荐表。只承载数据；写入操作由归属服务负责。 */
public record DailyRecommendation(
    /** 主键ID */
    Long id,
    /** 推荐业务日期 */
    LocalDate recommendationDate,
    /** 推荐范围标识：全家或指定餐厅 */
    String scopeKey,
    /** 餐厅ID */
    Long restaurantId,
    /** 菜品ID */
    Long dishId,
    /** 推荐失效时间 */
    Instant invalidatedAt,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
