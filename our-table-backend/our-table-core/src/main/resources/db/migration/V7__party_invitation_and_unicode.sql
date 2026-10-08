-- 聚会名称按可见字符校验，物理列为复合 emoji 预留容量；仅 core 执行迁移。
ALTER TABLE party
    MODIFY theme VARCHAR(255) NOT NULL COMMENT '聚会主题，最多20个可见字符',
    MODIFY location_text VARCHAR(255) NOT NULL COMMENT '聚会地点说明，最多30个可见字符',
    MODIFY end_note VARCHAR(1000) NULL COMMENT '聚会结束备注，最多100个可见字符';
ALTER TABLE party_member
    MODIFY display_name_snapshot VARCHAR(255) NOT NULL COMMENT '聚会成员报名时昵称快照',
    MODIFY removed_reason VARCHAR(1000) NULL COMMENT '移除成员原因，最多100个可见字符';
ALTER TABLE party_invitation
    ADD token_ciphertext VARBINARY(160) NULL COMMENT '邀请令牌AES-256-GCM密文，历史记录可为空',
    ADD token_nonce VARBINARY(12) NULL COMMENT '邀请令牌AES-GCM随机数',
    ADD encryption_key_version VARCHAR(32) NULL COMMENT '邀请令牌加密密钥版本';
