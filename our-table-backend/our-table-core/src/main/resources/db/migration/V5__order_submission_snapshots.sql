-- 正式提交保存私房做法；普通餐单DTO不包含此字段。
ALTER TABLE meal_order_item ADD COLUMN recipe_snapshot TEXT NULL COMMENT '提交时私房做法快照，仅本店主厨专用接口可读' AFTER spec_snapshot;
-- 100个可见字符可能包含复合表情，扩展物理容量，业务仍限定100字。
ALTER TABLE meal_order MODIFY COLUMN remark VARCHAR(2048) NULL COMMENT '整单备注，业务上限100个可见字符',
    MODIFY COLUMN cancel_reason VARCHAR(2048) NULL COMMENT '取消原因，业务上限100个可见字符';
