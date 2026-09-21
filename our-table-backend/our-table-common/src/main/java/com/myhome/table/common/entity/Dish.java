package com.myhome.table.common.entity;

import java.time.Instant;

/** 菜品表。只承载数据；写入操作由归属服务负责。 */
public record Dish(
    /** 主键ID */
    Long id,
    /** 餐厅ID */
    Long restaurantId,
    /** 所属品类ID */
    Long categoryId,
    /** 菜品名称 */
    String name,
    /** 菜品简介 */
    String introduction,
    /** 私房做法 */
    String recipe,
    /** 菜品图片资源ID */
    Long imageMediaId,
    /** 是否上架 */
    Integer isOnShelf,
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
