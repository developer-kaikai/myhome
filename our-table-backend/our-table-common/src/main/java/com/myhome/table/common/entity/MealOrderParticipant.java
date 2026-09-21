package com.myhome.table.common.entity;

import java.time.Instant;

/** 餐单参与者表。只承载数据；写入操作由归属服务负责。 */
public record MealOrderParticipant(
    /** 主键ID */
    Long id,
    /** 餐单ID */
    Long mealOrderId,
    /** 用户ID */
    Long userId,
    /** 餐单参与者昵称快照 */
    String displayNameSnapshot,
    /** 是否为餐单发起人 */
    Integer isInitiator,
    /** 是否为原点单者 */
    Integer isOrderer,
    /** 是否参与过餐单协作 */
    Integer isCollaborator,
    /** 是否为本餐厅主厨 */
    Integer isChef,
    /** 首次参与餐单时间 */
    Instant firstJoinedAt,
    /** 最近操作餐单时间 */
    Instant lastActedAt) {}
