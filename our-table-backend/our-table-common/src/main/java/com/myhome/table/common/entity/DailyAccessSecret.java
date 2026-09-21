package com.myhome.table.common.entity;

import java.time.Instant;

/** 日常准入密令配置表。只承载数据；写入操作由归属服务负责。 */
public record DailyAccessSecret(
    /** 主键ID */
    Long id,
    /** 密令BCrypt哈希 */
    String secretHash,
    /** 密令哈希算法 */
    String hashAlgorithm,
    /** 密令AES-256-GCM密文 */
    byte[] secretCiphertext,
    /** 密令AES-GCM随机数 */
    byte[] secretNonce,
    /** 密令加密密钥版本 */
    String encryptionKeyVersion,
    /** 密令版本号 */
    Long secretVersion,
    /** 授权撤销代次 */
    Long grantGeneration,
    /** 最近修改密令的用户ID */
    Long updatedByUserId,
    /** 乐观锁版本号 */
    Long version,
    /** 创建时间 */
    Instant createdAt,
    /** 更新时间 */
    Instant updatedAt) {}
