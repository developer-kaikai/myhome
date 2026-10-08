-- 评价昵称及组合字符按业务可见字符校验，物理容量兼容 emoji。
ALTER TABLE dish_review
    MODIFY reviewer_name_snapshot VARCHAR(255) NOT NULL COMMENT '评价者首次提交时昵称快照，业务上限20个可见字符',
    MODIFY comment_text VARCHAR(4096) NULL COMMENT '评价文字，业务上限200个可见字符';

CREATE TABLE review_access_grant (
    meal_order_id BIGINT UNSIGNED NOT NULL COMMENT '已取得评价资格的餐单ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '获得单餐单评价资格的用户ID',
    source_share_id BIGINT UNSIGNED NULL COMMENT '首次取得资格的分享ID，正常餐单入口为空',
    granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次取得评价资格时间',
    PRIMARY KEY (meal_order_id,user_id),
    KEY idx_review_access_user (user_id,granted_at),
    CONSTRAINT fk_review_access_order FOREIGN KEY (meal_order_id) REFERENCES meal_order(id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_access_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_access_share FOREIGN KEY (source_share_id) REFERENCES review_share(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单餐单受限评价资格表，不授予日常点餐或完整餐单权限';

-- 已完成餐单的原点单者拥有受限站内评价邀请，密令到期不撤销该单评价资格。
INSERT INTO review_access_grant(meal_order_id,user_id,granted_at)
SELECT o.id,p.user_id,o.completed_at FROM meal_order o JOIN meal_order_participant p ON p.meal_order_id=o.id
WHERE o.status='COMPLETED' AND p.is_orderer=1;

UPDATE notification_outbox SET page_path=CONCAT('subpackages/reviews/sheet?id=',business_id)
WHERE event_type='REVIEW_INVITED' AND status IN ('PENDING','FAILED_RETRYABLE');
