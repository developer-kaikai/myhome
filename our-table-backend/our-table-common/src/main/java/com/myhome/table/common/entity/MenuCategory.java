package com.myhome.table.common.entity;

import java.time.Instant;

/** 菜单品类表。只承载数据；写入操作由归属服务负责。 */
public record MenuCategory(
    /** 主键ID */
    Long id,
    /** 餐厅ID */
    Long restaurantId,
    /** 品类类型 */
    String categoryType,
    /** 品类名称 */
    String name,
    /** 显示排序值 */
    Integer sortOrder,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt,
    /** 软删除时间 */
    Instant deletedAt,
    /** 数据库派生字段 */
    String activeName) {}
