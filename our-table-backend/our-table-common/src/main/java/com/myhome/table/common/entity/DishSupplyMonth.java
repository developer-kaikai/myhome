package com.myhome.table.common.entity;

import java.time.Instant;

/** 季节菜供应月份表。只承载数据；写入操作由归属服务负责。 */
public record DishSupplyMonth(
    /** 菜品ID */
    Long dishId,
    /** 供应月份 */
    Integer supplyMonth,
    /** 创建时间 */
    Instant createdAt) {}
