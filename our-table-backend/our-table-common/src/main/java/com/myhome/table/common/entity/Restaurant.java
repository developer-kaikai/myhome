package com.myhome.table.common.entity;

import java.time.Instant;

/** 家庭餐厅表。只承载数据；写入操作由归属服务负责。 */
public record Restaurant(
    /** 主键ID */
    Long id,
    /** 餐厅稳定编码 */
    String restaurantCode,
    /** 餐厅主厨用户ID */
    Long chefUserId,
    /** 餐厅名称 */
    String name,
    /** 封面图片资源ID */
    Long coverMediaId,
    /** 餐厅状态 */
    String status,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
