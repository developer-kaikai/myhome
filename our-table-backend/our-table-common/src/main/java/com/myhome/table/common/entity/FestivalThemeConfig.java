package com.myhome.table.common.entity;

import java.time.Instant;
import java.time.LocalDate;

/** 年度节日主题配置表。只承载数据；写入操作由归属服务负责。 */
public record FestivalThemeConfig(
    /** 主键ID */
    Long id,
    /** 节日主题标识 */
    String themeKey,
    /** 配置所属年份 */
    Integer yearNumber,
    /** 当年节日日期 */
    LocalDate festivalDate,
    /** 主题生效开始时间 */
    Instant effectiveStartAt,
    /** 主题生效结束时间 */
    Instant effectiveEndAt,
    /** 主题匹配优先级 */
    Integer priorityOrder,
    /** 主题素材配置JSON */
    String assetConfigJson,
    /** 是否启用 */
    Integer enabled,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
