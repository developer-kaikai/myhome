package com.myhome.table.common.entity;

import java.time.Instant;

/** 微信用户表。只承载数据；写入操作由归属服务负责。 */
public record AppUser(
    /** 主键ID */
    Long id,
    /** 微信小程序OpenID */
    String openid,
    /** 微信开放平台UnionID */
    String unionid,
    /** 当前显示昵称 */
    String nickname,
    /** 当前头像地址 */
    String avatarUrl,
    /** 用户状态 */
    String status,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
