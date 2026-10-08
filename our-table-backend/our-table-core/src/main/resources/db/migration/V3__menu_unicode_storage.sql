-- 业务长度仍按可见字符校验；扩展存储空间以容纳组合 emoji，不修改 V1 基线。
ALTER TABLE restaurant MODIFY name VARCHAR(255) NOT NULL COMMENT '餐厅名称，业务最多20个可见字符';
ALTER TABLE menu_category
    MODIFY name VARCHAR(255) NOT NULL COMMENT '品类名称，业务最多10个可见字符',
    MODIFY active_name VARCHAR(255) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN name ELSE NULL END) STORED COMMENT '未删除记录参与唯一约束的名称';
ALTER TABLE dish
    MODIFY name VARCHAR(255) NOT NULL COMMENT '菜品名称，业务最多20个可见字符',
    MODIFY introduction VARCHAR(1024) NULL COMMENT '菜品简介，业务最多50个可见字符',
    MODIFY recipe VARCHAR(8192) NULL COMMENT '私房做法，业务最多500个可见字符',
    MODIFY active_name VARCHAR(255) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN name ELSE NULL END) STORED COMMENT '未删除记录参与唯一约束的名称';
ALTER TABLE dish_spec_dimension
    MODIFY name VARCHAR(255) NOT NULL COMMENT '规格维度名称，业务最多10个可见字符',
    MODIFY active_name VARCHAR(255) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN name ELSE NULL END) STORED COMMENT '未删除记录参与唯一约束的名称';
ALTER TABLE dish_spec_option
    MODIFY name VARCHAR(255) NOT NULL COMMENT '规格选项名称，业务最多10个可见字符',
    MODIFY active_name VARCHAR(255) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN name ELSE NULL END) STORED COMMENT '未删除记录参与唯一约束的名称';
