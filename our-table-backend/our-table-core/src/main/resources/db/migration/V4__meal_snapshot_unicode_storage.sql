-- 与菜单/账号可见字符校验兼容；只扩展快照物理容量，不改变业务字数上限。
ALTER TABLE meal_order
    MODIFY restaurant_name_snapshot VARCHAR(255) NULL COMMENT '餐厅名称快照';
ALTER TABLE meal_order_item
    MODIFY contributor_name_snapshot VARCHAR(255) NOT NULL COMMENT '原添加人昵称快照',
    MODIFY dish_name_snapshot VARCHAR(255) NOT NULL COMMENT '菜品名称快照';
ALTER TABLE meal_order_participant
    MODIFY display_name_snapshot VARCHAR(255) NOT NULL COMMENT '餐单参与者昵称快照';
ALTER TABLE meal_actual_item
    MODIFY dish_name_snapshot VARCHAR(255) NOT NULL COMMENT '菜品名称快照';
