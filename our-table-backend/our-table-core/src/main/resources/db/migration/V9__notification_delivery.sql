-- 统一评价邀请事件名和实际页面路径，历史迁移保持不变。
UPDATE notification_template SET business_event='REVIEW_INVITED', page_path_template='subpackages/reviews/sheet?id={businessId}' WHERE business_event='REVIEW_INVITATION';
UPDATE notification_template SET page_path_template='pages/orders/detail?id={businessId}' WHERE business_event='ORDER_SUBMITTED';

ALTER TABLE notification_outbox
    ADD COLUMN attempt_token VARCHAR(36) NULL COMMENT '当前发送尝试标识，用于并发及迟到回执校验',
    ADD COLUMN sending_started_at DATETIME(3) NULL COMMENT '当前发送预占开始时间',
    ADD COLUMN subscription_version BIGINT UNSIGNED NULL COMMENT '发送预占时的授权版本，避免旧失败覆盖新授权',
    ADD KEY idx_notification_sending (status, sending_started_at);
