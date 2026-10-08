package com.myhome.table.common.entity;

import java.time.Instant;

/** 单餐单受限评价资格，不授予日常点餐或完整餐单访问权。 */
public record ReviewAccessGrant(
    /** 餐单ID */
    Long mealOrderId,
    /** 用户ID */
    Long userId,
    /** 首次来源分享ID，正常餐单或原点单者邀请为空 */
    Long sourceShareId,
    /** 首次取得资格时间 */
    Instant grantedAt) {}
