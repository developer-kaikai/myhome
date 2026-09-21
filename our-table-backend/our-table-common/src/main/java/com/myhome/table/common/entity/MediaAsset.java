package com.myhome.table.common.entity;

import java.time.Instant;

/** 图片资源表。只承载数据；写入操作由归属服务负责。 */
public record MediaAsset(
    /** 主键ID */
    Long id,
    /** 图片所属用户ID */
    Long ownerUserId,
    /** 图片业务用途 */
    String usageType,
    /** 对象存储键 */
    String objectKey,
    /** 文件MIME类型 */
    String mimeType,
    /** 文件字节数 */
    Long byteSize,
    /** 图片宽度像素 */
    Integer widthPx,
    /** 图片高度像素 */
    Integer heightPx,
    /** 文件内容SHA-256摘要 */
    String sha256,
    /** 图片资源状态 */
    String status,
    /** 创建时间 */
    Instant createdAt,
    /** 最近被业务引用时间 */
    Instant referencedAt,
    /** 软删除时间 */
    Instant deletedAt) {}
