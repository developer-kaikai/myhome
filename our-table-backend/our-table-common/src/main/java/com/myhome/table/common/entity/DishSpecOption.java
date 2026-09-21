package com.myhome.table.common.entity;

import java.time.Instant;

/** 菜品规格选项表。只承载数据；写入操作由归属服务负责。 */
public record DishSpecOption(
    /** 主键ID */
    Long id,
    /** 所属规格维度ID */
    Long dimensionId,
    /** 规格选项名称 */
    String name,
    /** 是否为默认规格选项 */
    Integer isDefault,
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
    String activeName,
    /** 数据库派生字段 */
    Integer defaultMarker) {}
