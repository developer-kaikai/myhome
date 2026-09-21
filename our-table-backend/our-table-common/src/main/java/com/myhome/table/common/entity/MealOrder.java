package com.myhome.table.common.entity;

import java.time.Instant;
import java.time.LocalDate;

/** 共享清单与正式餐单表。只承载数据；写入操作由归属服务负责。 */
public record MealOrder(
    /** 主键ID */
    Long id,
    /** 正式餐单编号 */
    String orderNo,
    /** 餐厅ID */
    Long restaurantId,
    /** 北京时间用餐日期 */
    LocalDate mealDate,
    /** 餐次类型 */
    String mealPeriod,
    /** 用餐日期餐次组合键：早餐1午餐2晚餐3宵夜4 */
    Long mealSlot,
    /** 餐单状态 */
    String status,
    /** 餐单发起人用户ID */
    Long initiatorUserId,
    /** 取消后重建来源餐单ID */
    Long sourceCancelledId,
    /** 用餐人数 */
    Integer dinerCount,
    /** 整单备注 */
    String remark,
    /** 餐厅名称快照 */
    String restaurantNameSnapshot,
    /** 停止普通点餐及触发确认提醒的时间 */
    Instant cutoffAt,
    /** 餐单首次提交时间 */
    Instant submittedAt,
    /** 确认完成时间 */
    Instant completedAt,
    /** 确认实际菜品的主厨用户ID */
    Long confirmedByUserId,
    /** 取消时间 */
    Instant cancelledAt,
    /** 取消操作用户ID */
    Long cancelledByUserId,
    /** 取消原因 */
    String cancelReason,
    /** 到点确认提醒创建时间 */
    Instant reminderCreatedAt,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt,
    /** 数据库派生字段 */
    Integer occupyingMarker) {}
