-- “我们的餐桌”首版 MySQL 8.0 逻辑基线
-- 所有 DATETIME(3) 按 UTC 写入；meal_date 按 Asia/Shanghai 业务日期写入。
-- 生产执行前须纳入 Flyway、在目标 MySQL 小版本验证，并完成备份。
-- 三个后端服务共用本 Schema，但按表归属限制写入：
-- core-service 写用户、准入、餐厅菜单、图片、推荐和主题配置；
-- ordering-service 写餐单、实际菜品、评价、订阅授权和通知任务；party-service 写聚会相关表。
-- api_idempotency_record 与 biz_operation_log 由三个服务通过公共组件按服务名和业务类型隔离写入。
-- our-table-common 只提供公共实体与工具类，不是部署服务，也不拥有数据库表。

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE app_user (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    openid              VARCHAR(64) NOT NULL COMMENT '微信小程序OpenID',
    unionid             VARCHAR(64) NULL COMMENT '微信开放平台UnionID',
    nickname            VARCHAR(20) NOT NULL DEFAULT '微信用户' COMMENT '当前显示昵称',
    avatar_url          VARCHAR(500) NULL COMMENT '当前头像地址',
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '用户状态',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_openid (openid),
    KEY idx_app_user_unionid (unionid),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信用户表';

CREATE TABLE media_asset (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    owner_user_id       BIGINT UNSIGNED NULL COMMENT '图片所属用户ID',
    usage_type          VARCHAR(32) NOT NULL COMMENT '图片业务用途',
    object_key          VARCHAR(500) NOT NULL COMMENT '对象存储键',
    mime_type           VARCHAR(64) NOT NULL COMMENT '文件MIME类型',
    byte_size           BIGINT UNSIGNED NOT NULL COMMENT '文件字节数',
    width_px            INT UNSIGNED NOT NULL COMMENT '图片宽度像素',
    height_px           INT UNSIGNED NOT NULL COMMENT '图片高度像素',
    sha256              CHAR(64) NOT NULL COMMENT '文件内容SHA-256摘要',
    status              VARCHAR(20) NOT NULL DEFAULT 'TEMPORARY' COMMENT '图片资源状态',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    referenced_at       DATETIME(3) NULL COMMENT '最近被业务引用时间',
    deleted_at          DATETIME(3) NULL COMMENT '软删除时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_object_key (object_key),
    KEY idx_media_owner (owner_user_id, created_at),
    KEY idx_media_cleanup (status, created_at),
    CONSTRAINT fk_media_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_media_size CHECK (byte_size <= 5242880),
    CONSTRAINT ck_media_status CHECK (status IN ('TEMPORARY', 'ACTIVE', 'QUARANTINED', 'DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='图片资源表';

CREATE TABLE restaurant (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    restaurant_code     VARCHAR(32) NOT NULL COMMENT '餐厅稳定编码',
    chef_user_id        BIGINT UNSIGNED NULL COMMENT '餐厅主厨用户ID',
    name                VARCHAR(20) NOT NULL COMMENT '餐厅名称',
    cover_media_id      BIGINT UNSIGNED NULL COMMENT '封面图片资源ID',
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '餐厅状态',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_restaurant_code (restaurant_code),
    UNIQUE KEY uk_restaurant_chef (chef_user_id),
    CONSTRAINT fk_restaurant_chef FOREIGN KEY (chef_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_restaurant_cover FOREIGN KEY (cover_media_id) REFERENCES media_asset (id) ON DELETE SET NULL,
    CONSTRAINT ck_restaurant_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='家庭餐厅表';

CREATE TABLE daily_access_secret (
    id                  BIGINT UNSIGNED NOT NULL COMMENT '主键ID',
    secret_hash         VARCHAR(100) NOT NULL COMMENT '密令BCrypt哈希',
    hash_algorithm      VARCHAR(20) NOT NULL DEFAULT 'BCRYPT' COMMENT '密令哈希算法',
    secret_ciphertext   VARBINARY(512) NOT NULL COMMENT '密令AES-256-GCM密文',
    secret_nonce        VARBINARY(12) NOT NULL COMMENT '密令AES-GCM随机数',
    encryption_key_version VARCHAR(32) NOT NULL COMMENT '密令加密密钥版本',
    secret_version      BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '密令版本号',
    grant_generation    BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '授权撤销代次',
    updated_by_user_id  BIGINT UNSIGNED NULL COMMENT '最近修改密令的用户ID',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_access_secret_updater FOREIGN KEY (updated_by_user_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_access_secret_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日常准入密令配置表';

CREATE TABLE daily_access_grant (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id             BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    secret_version      BIGINT UNSIGNED NOT NULL COMMENT '密令版本号',
    grant_generation    BIGINT UNSIGNED NOT NULL COMMENT '授权撤销代次',
    issued_at           DATETIME(3) NOT NULL COMMENT '授权签发时间',
    expires_at          DATETIME(3) NOT NULL COMMENT '到期时间',
    revoked_at          DATETIME(3) NULL COMMENT '授权撤销时间',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_access_grant_user (user_id, expires_at, revoked_at),
    KEY idx_access_grant_cleanup (expires_at),
    CONSTRAINT fk_access_grant_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_access_grant_time CHECK (expires_at > issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日常准入授权记录表';

CREATE TABLE menu_category (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    restaurant_id       BIGINT UNSIGNED NOT NULL COMMENT '餐厅ID',
    category_type       VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT '品类类型',
    name                VARCHAR(10) NOT NULL COMMENT '品类名称',
    sort_order          INT NOT NULL DEFAULT 0 COMMENT '显示排序值',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    deleted_at          DATETIME(3) NULL COMMENT '软删除时间',
    active_name         VARCHAR(10) GENERATED ALWAYS AS (
                            CASE WHEN deleted_at IS NULL THEN name ELSE NULL END
                        ) STORED COMMENT '未删除记录参与唯一约束的名称',
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_active_name (restaurant_id, active_name),
    KEY idx_category_list (restaurant_id, deleted_at, sort_order),
    CONSTRAINT fk_category_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE RESTRICT,
    CONSTRAINT ck_category_type CHECK (category_type IN ('NORMAL', 'SEASONAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单品类表';

CREATE TABLE dish (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    restaurant_id       BIGINT UNSIGNED NOT NULL COMMENT '餐厅ID',
    category_id         BIGINT UNSIGNED NOT NULL COMMENT '所属品类ID',
    name                VARCHAR(20) NOT NULL COMMENT '菜品名称',
    introduction        VARCHAR(50) NULL COMMENT '菜品简介',
    recipe              VARCHAR(500) NULL COMMENT '私房做法',
    image_media_id      BIGINT UNSIGNED NULL COMMENT '菜品图片资源ID',
    is_on_shelf         TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否上架',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    deleted_at          DATETIME(3) NULL COMMENT '软删除时间',
    active_name         VARCHAR(20) GENERATED ALWAYS AS (
                            CASE WHEN deleted_at IS NULL THEN name ELSE NULL END
                        ) STORED COMMENT '未删除记录参与唯一约束的名称',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dish_active_name (restaurant_id, active_name),
    KEY idx_dish_menu (restaurant_id, category_id, is_on_shelf, deleted_at),
    CONSTRAINT fk_dish_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dish_category FOREIGN KEY (category_id) REFERENCES menu_category (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dish_image FOREIGN KEY (image_media_id) REFERENCES media_asset (id) ON DELETE SET NULL,
    CONSTRAINT ck_dish_shelf CHECK (is_on_shelf IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜品表';

CREATE TABLE dish_supply_month (
    dish_id             BIGINT UNSIGNED NOT NULL COMMENT '菜品ID',
    supply_month        TINYINT UNSIGNED NOT NULL COMMENT '供应月份',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (dish_id, supply_month),
    CONSTRAINT fk_supply_month_dish FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE CASCADE,
    CONSTRAINT ck_supply_month CHECK (supply_month BETWEEN 1 AND 12)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='季节菜供应月份表';

CREATE TABLE dish_spec_dimension (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    dish_id             BIGINT UNSIGNED NOT NULL COMMENT '菜品ID',
    name                VARCHAR(10) NOT NULL COMMENT '规格维度名称',
    sort_order          INT NOT NULL DEFAULT 0 COMMENT '显示排序值',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    deleted_at          DATETIME(3) NULL COMMENT '软删除时间',
    active_name         VARCHAR(10) GENERATED ALWAYS AS (
                            CASE WHEN deleted_at IS NULL THEN name ELSE NULL END
                        ) STORED COMMENT '未删除记录参与唯一约束的名称',
    PRIMARY KEY (id),
    UNIQUE KEY uk_spec_dimension_active_name (dish_id, active_name),
    KEY idx_spec_dimension_list (dish_id, deleted_at, sort_order),
    CONSTRAINT fk_spec_dimension_dish FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜品规格维度表';

CREATE TABLE dish_spec_option (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    dimension_id        BIGINT UNSIGNED NOT NULL COMMENT '所属规格维度ID',
    name                VARCHAR(10) NOT NULL COMMENT '规格选项名称',
    is_default          TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为默认规格选项',
    sort_order          INT NOT NULL DEFAULT 0 COMMENT '显示排序值',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    deleted_at          DATETIME(3) NULL COMMENT '软删除时间',
    active_name         VARCHAR(10) GENERATED ALWAYS AS (
                            CASE WHEN deleted_at IS NULL THEN name ELSE NULL END
                        ) STORED COMMENT '未删除记录参与唯一约束的名称',
    default_marker      TINYINT GENERATED ALWAYS AS (
                            CASE WHEN deleted_at IS NULL AND is_default = 1 THEN 1 ELSE NULL END
                        ) STORED COMMENT '默认规格选项唯一约束标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_spec_option_active_name (dimension_id, active_name),
    UNIQUE KEY uk_spec_option_default (dimension_id, default_marker),
    KEY idx_spec_option_list (dimension_id, deleted_at, sort_order),
    CONSTRAINT fk_spec_option_dimension FOREIGN KEY (dimension_id) REFERENCES dish_spec_dimension (id) ON DELETE CASCADE,
    CONSTRAINT ck_spec_option_default CHECK (is_default IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜品规格选项表';

CREATE TABLE meal_order (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    order_no                VARCHAR(32) NULL COMMENT '正式餐单编号',
    restaurant_id           BIGINT UNSIGNED NOT NULL COMMENT '餐厅ID',
    meal_date               DATE NOT NULL COMMENT '北京时间用餐日期',
    meal_period             VARCHAR(16) NOT NULL COMMENT '餐次类型',
    meal_slot               BIGINT UNSIGNED NOT NULL COMMENT '用餐日期餐次组合键：早餐1午餐2晚餐3宵夜4',
    status                  VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '餐单状态',
    initiator_user_id       BIGINT UNSIGNED NULL COMMENT '餐单发起人用户ID',
    source_cancelled_id     BIGINT UNSIGNED NULL COMMENT '取消后重建来源餐单ID',
    diner_count             TINYINT UNSIGNED NOT NULL DEFAULT 2 COMMENT '用餐人数',
    remark                  VARCHAR(100) NULL COMMENT '整单备注',
    restaurant_name_snapshot VARCHAR(20) NULL COMMENT '餐厅名称快照',
    cutoff_at               DATETIME(3) NOT NULL COMMENT '停止普通点餐及触发确认提醒的时间',
    submitted_at            DATETIME(3) NULL COMMENT '餐单首次提交时间',
    completed_at            DATETIME(3) NULL COMMENT '确认完成时间',
    confirmed_by_user_id    BIGINT UNSIGNED NULL COMMENT '确认实际菜品的主厨用户ID',
    cancelled_at            DATETIME(3) NULL COMMENT '取消时间',
    cancelled_by_user_id    BIGINT UNSIGNED NULL COMMENT '取消操作用户ID',
    cancel_reason           VARCHAR(100) NULL COMMENT '取消原因',
    reminder_created_at     DATETIME(3) NULL COMMENT '到点确认提醒创建时间',
    version                 BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    occupying_marker        TINYINT GENERATED ALWAYS AS (
                                CASE WHEN status <> 'CANCELLED' THEN 1 ELSE NULL END
                            ) STORED COMMENT '非取消餐单的唯一占用标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_meal_order_no (order_no),
    UNIQUE KEY uk_meal_slot_occupying (restaurant_id, meal_slot, occupying_marker),
    KEY idx_meal_workbench (restaurant_id, status, cutoff_at),
    KEY idx_meal_date (status, meal_date, restaurant_id),
    KEY idx_meal_initiator (initiator_user_id, created_at),
    CONSTRAINT fk_meal_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_meal_initiator FOREIGN KEY (initiator_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_meal_source_cancelled FOREIGN KEY (source_cancelled_id) REFERENCES meal_order (id) ON DELETE SET NULL,
    CONSTRAINT fk_meal_confirmer FOREIGN KEY (confirmed_by_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_meal_canceller FOREIGN KEY (cancelled_by_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_meal_period CHECK (meal_period IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SUPPER')),
    CONSTRAINT ck_meal_status CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_meal_diner_count CHECK (diner_count BETWEEN 1 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='共享清单与正式餐单表';

CREATE TABLE meal_order_participant (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    meal_order_id       BIGINT UNSIGNED NOT NULL COMMENT '餐单ID',
    user_id             BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    display_name_snapshot VARCHAR(20) NOT NULL COMMENT '餐单参与者昵称快照',
    is_initiator        TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为餐单发起人',
    is_orderer          TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为原点单者',
    is_collaborator     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否参与过餐单协作',
    is_chef             TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为本餐厅主厨',
    first_joined_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次参与餐单时间',
    last_acted_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近操作餐单时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_meal_participant (meal_order_id, user_id),
    KEY idx_meal_participant_user (user_id, meal_order_id),
    CONSTRAINT fk_meal_participant_order FOREIGN KEY (meal_order_id) REFERENCES meal_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_meal_participant_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_meal_participant_flags CHECK (
        is_initiator IN (0,1) AND is_orderer IN (0,1) AND is_collaborator IN (0,1) AND is_chef IN (0,1)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='餐单参与者表';

CREATE TABLE meal_order_item (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    meal_order_id       BIGINT UNSIGNED NOT NULL COMMENT '餐单ID',
    dish_id             BIGINT UNSIGNED NOT NULL COMMENT '菜品ID',
    contributor_user_id BIGINT UNSIGNED NOT NULL COMMENT '原添加人用户ID',
    contributor_name_snapshot VARCHAR(20) NOT NULL COMMENT '原添加人昵称快照',
    dish_name_snapshot  VARCHAR(20) NOT NULL COMMENT '菜品名称快照',
    image_object_key_snapshot VARCHAR(500) NULL COMMENT '图片对象键快照',
    spec_key            CHAR(64) NOT NULL COMMENT '规范化规格JSON的SHA-256摘要',
    spec_snapshot       JSON NOT NULL COMMENT '规格选择快照JSON',
    item_stage          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '点餐行阶段：待提交或已提交',
    quantity            TINYINT UNSIGNED NOT NULL COMMENT '菜品份数',
    submitted_at        DATETIME(3) NULL COMMENT '点餐行并入正式餐单的时间',
    removed_at          DATETIME(3) NULL COMMENT '移除时间',
    removed_by_user_id  BIGINT UNSIGNED NULL COMMENT '移除操作用户ID',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    active_marker       TINYINT GENERATED ALWAYS AS (
                            CASE WHEN removed_at IS NULL THEN 1 ELSE NULL END
                        ) STORED COMMENT '当前有效记录的唯一约束标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_meal_item_active (meal_order_id, dish_id, contributor_user_id, spec_key, item_stage, active_marker),
    KEY idx_meal_item_order (meal_order_id, item_stage, removed_at),
    KEY idx_meal_item_contributor (contributor_user_id, meal_order_id),
    CONSTRAINT fk_meal_item_order FOREIGN KEY (meal_order_id) REFERENCES meal_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_meal_item_dish FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE RESTRICT,
    CONSTRAINT fk_meal_item_contributor FOREIGN KEY (contributor_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_meal_item_remover FOREIGN KEY (removed_by_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_meal_item_stage CHECK (item_stage IN ('PENDING', 'SUBMITTED')),
    CONSTRAINT ck_meal_item_submission CHECK (
        (item_stage = 'PENDING' AND submitted_at IS NULL)
        OR (item_stage = 'SUBMITTED' AND submitted_at IS NOT NULL)
    ),
    CONSTRAINT ck_meal_item_quantity CHECK (quantity BETWEEN 1 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='原始点餐明细表';

CREATE TABLE meal_actual_item (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    meal_order_id       BIGINT UNSIGNED NOT NULL COMMENT '餐单ID',
    dish_id             BIGINT UNSIGNED NOT NULL COMMENT '菜品ID',
    source_type         VARCHAR(20) NOT NULL COMMENT '实际菜品来源：原点餐或主厨补充',
    source_order_item_id BIGINT UNSIGNED NULL COMMENT '对应原点餐行ID',
    confirmed_by_user_id BIGINT UNSIGNED NOT NULL COMMENT '确认实际菜品的主厨用户ID',
    dish_name_snapshot  VARCHAR(20) NOT NULL COMMENT '菜品名称快照',
    image_object_key_snapshot VARCHAR(500) NULL COMMENT '图片对象键快照',
    spec_key            CHAR(64) NOT NULL COMMENT '规范化规格JSON的SHA-256摘要',
    spec_snapshot       JSON NOT NULL COMMENT '规格选择快照JSON',
    quantity            TINYINT UNSIGNED NOT NULL COMMENT '菜品份数',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_actual_item (meal_order_id, dish_id, spec_key),
    KEY idx_actual_dish_order (dish_id, meal_order_id),
    CONSTRAINT fk_actual_order FOREIGN KEY (meal_order_id) REFERENCES meal_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_actual_dish FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE RESTRICT,
    CONSTRAINT fk_actual_source_item FOREIGN KEY (source_order_item_id) REFERENCES meal_order_item (id) ON DELETE SET NULL,
    CONSTRAINT fk_actual_confirmer FOREIGN KEY (confirmed_by_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_actual_source CHECK (source_type IN ('ORDERED', 'CONFIRM_ADDED')),
    CONSTRAINT ck_actual_quantity CHECK (quantity BETWEEN 1 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实际做菜明细表';

CREATE TABLE dish_review (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    meal_order_id       BIGINT UNSIGNED NOT NULL COMMENT '餐单ID',
    dish_id             BIGINT UNSIGNED NOT NULL COMMENT '菜品ID',
    reviewer_user_id    BIGINT UNSIGNED NOT NULL COMMENT '评价用户ID',
    reviewer_name_snapshot VARCHAR(20) NOT NULL COMMENT '评价者昵称快照',
    reviewer_avatar_snapshot VARCHAR(500) NULL COMMENT '评价者头像快照',
    rating              TINYINT UNSIGNED NOT NULL COMMENT '星级评分',
    comment_text        VARCHAR(200) NULL COMMENT '评价文字内容',
    modify_count        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '评价修改次数',
    first_submitted_at  DATETIME(3) NOT NULL COMMENT '首次评价提交时间',
    modified_at         DATETIME(3) NULL COMMENT '评价修改时间',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_order_dish_user (meal_order_id, dish_id, reviewer_user_id),
    KEY idx_review_dish_time (dish_id, first_submitted_at),
    KEY idx_review_user_time (reviewer_user_id, first_submitted_at),
    CONSTRAINT fk_review_order FOREIGN KEY (meal_order_id) REFERENCES meal_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_dish FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_user FOREIGN KEY (reviewer_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_review_modify_count CHECK (modify_count BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜品评价表';

CREATE TABLE review_share (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    meal_order_id       BIGINT UNSIGNED NOT NULL COMMENT '餐单ID',
    token_hash          CHAR(64) NOT NULL COMMENT '评价分享令牌SHA-256摘要',
    created_by_user_id  BIGINT UNSIGNED NOT NULL COMMENT '创建用户ID',
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '评价分享状态',
    expires_at          DATETIME(3) NOT NULL COMMENT '到期时间',
    disabled_at         DATETIME(3) NULL COMMENT '停用时间',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_share_token (token_hash),
    KEY idx_review_share_order (meal_order_id, status, expires_at),
    CONSTRAINT fk_review_share_order FOREIGN KEY (meal_order_id) REFERENCES meal_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_share_creator FOREIGN KEY (created_by_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_share_status CHECK (status IN ('ACTIVE', 'DISABLED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评价分享链接表';

CREATE TABLE daily_recommendation (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    recommendation_date DATE NOT NULL COMMENT '推荐业务日期',
    scope_key           VARCHAR(40) NOT NULL COMMENT '推荐范围标识：全家或指定餐厅',
    restaurant_id       BIGINT UNSIGNED NOT NULL COMMENT '餐厅ID',
    dish_id             BIGINT UNSIGNED NOT NULL COMMENT '菜品ID',
    invalidated_at      DATETIME(3) NULL COMMENT '推荐失效时间',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_recommendation (recommendation_date, scope_key),
    KEY idx_daily_recommendation_dish (dish_id, recommendation_date),
    CONSTRAINT fk_recommendation_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id) ON DELETE RESTRICT,
    CONSTRAINT fk_recommendation_dish FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日时令推荐表';

CREATE TABLE party (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    party_no            VARCHAR(32) NOT NULL COMMENT '聚会编号',
    creator_user_id     BIGINT UNSIGNED NOT NULL COMMENT '创建用户ID',
    theme               VARCHAR(20) NOT NULL COMMENT '聚会主题',
    location_text       VARCHAR(30) NOT NULL COMMENT '聚会地点说明',
    start_at            DATETIME(3) NOT NULL COMMENT '聚会开始时间',
    planned_end_at      DATETIME(3) NOT NULL COMMENT '计划结束时间',
    cover_media_id      BIGINT UNSIGNED NULL COMMENT '封面图片资源ID',
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '聚会状态',
    is_reopened         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否曾被重开',
    first_ended_at      DATETIME(3) NULL COMMENT '首次结束时间',
    last_ended_at       DATETIME(3) NULL COMMENT '最近一次结束时间',
    reopened_at         DATETIME(3) NULL COMMENT '最近一次重开时间',
    end_note            VARCHAR(100) NULL COMMENT '聚会结束备注',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_party_no (party_no),
    KEY idx_party_creator_time (creator_user_id, start_at),
    KEY idx_party_status_time (status, start_at),
    CONSTRAINT fk_party_creator FOREIGN KEY (creator_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_party_cover FOREIGN KEY (cover_media_id) REFERENCES media_asset (id) ON DELETE SET NULL,
    CONSTRAINT ck_party_status CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_party_reopened CHECK (is_reopened IN (0, 1)),
    CONSTRAINT ck_party_time CHECK (planned_end_at > start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聚会活动表';

CREATE TABLE party_invitation (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    party_id            BIGINT UNSIGNED NOT NULL COMMENT '聚会ID',
    token_hash          CHAR(64) NOT NULL COMMENT '聚会邀请令牌SHA-256摘要',
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '聚会邀请状态',
    invitation_version  BIGINT UNSIGNED NOT NULL COMMENT '邀请链接版本号',
    created_by_user_id  BIGINT UNSIGNED NOT NULL COMMENT '创建用户ID',
    disabled_at         DATETIME(3) NULL COMMENT '停用时间',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    active_marker       TINYINT GENERATED ALWAYS AS (
                            CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END
                        ) STORED COMMENT '当前有效记录的唯一约束标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_party_invitation_token (token_hash),
    UNIQUE KEY uk_party_invitation_version (party_id, invitation_version),
    UNIQUE KEY uk_party_invitation_one_active (party_id, active_marker),
    KEY idx_party_invitation_active (party_id, status, created_at),
    CONSTRAINT fk_party_invitation_party FOREIGN KEY (party_id) REFERENCES party (id) ON DELETE CASCADE,
    CONSTRAINT fk_party_invitation_creator FOREIGN KEY (created_by_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_party_invitation_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聚会邀请链接表';

CREATE TABLE party_member (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    party_id            BIGINT UNSIGNED NOT NULL COMMENT '聚会ID',
    user_id             BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    display_name_snapshot VARCHAR(20) NOT NULL COMMENT '聚会成员昵称快照',
    member_status       VARCHAR(16) NOT NULL DEFAULT 'JOINED' COMMENT '成员状态',
    joined_at           DATETIME(3) NOT NULL COMMENT '报名时间',
    left_at             DATETIME(3) NULL COMMENT '退出或移除时间',
    removed_by_user_id  BIGINT UNSIGNED NULL COMMENT '移除操作用户ID',
    removed_reason      VARCHAR(100) NULL COMMENT '移除成员原因',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_party_member_user (party_id, user_id),
    KEY idx_party_member_user (user_id, party_id),
    KEY idx_party_member_status (party_id, member_status),
    CONSTRAINT fk_party_member_party FOREIGN KEY (party_id) REFERENCES party (id) ON DELETE CASCADE,
    CONSTRAINT fk_party_member_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_party_member_remover FOREIGN KEY (removed_by_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_party_member_status CHECK (member_status IN ('JOINED', 'EXITED', 'REMOVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聚会成员表';

CREATE TABLE party_purchase_item (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    party_id            BIGINT UNSIGNED NOT NULL COMMENT '聚会ID',
    item_name           VARCHAR(20) NOT NULL COMMENT '采购物品名称',
    quantity_text       VARCHAR(10) NULL COMMENT '采购数量说明',
    creator_user_id     BIGINT UNSIGNED NOT NULL COMMENT '创建用户ID',
    assignee_user_id    BIGINT UNSIGNED NULL COMMENT '当前采购负责人用户ID',
    purchase_status     VARCHAR(16) NOT NULL DEFAULT 'TODO' COMMENT '采购状态',
    payer_user_id       BIGINT UNSIGNED NULL COMMENT '实际垫付用户ID',
    amount              DECIMAL(10,2) NULL COMMENT '实际垫付金额',
    zero_amount_note    VARCHAR(100) NULL COMMENT '零元采购说明',
    purchased_at        DATETIME(3) NULL COMMENT '确认购买时间',
    correction_reason   VARCHAR(100) NULL COMMENT '金额或采购信息修正原因',
    removed_at          DATETIME(3) NULL COMMENT '移除时间',
    removed_by_user_id  BIGINT UNSIGNED NULL COMMENT '移除操作用户ID',
    remove_reason       VARCHAR(100) NULL COMMENT '移除采购项原因',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_party_item_list (party_id, removed_at, purchase_status, created_at),
    KEY idx_party_item_assignee (assignee_user_id, purchase_status),
    KEY idx_party_item_payer (payer_user_id, party_id),
    CONSTRAINT fk_party_item_party FOREIGN KEY (party_id) REFERENCES party (id) ON DELETE CASCADE,
    CONSTRAINT fk_party_item_creator FOREIGN KEY (creator_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_party_item_assignee FOREIGN KEY (assignee_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_party_item_payer FOREIGN KEY (payer_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_party_item_remover FOREIGN KEY (removed_by_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_party_purchase_status CHECK (purchase_status IN ('TODO', 'PURCHASED')),
    CONSTRAINT ck_party_amount CHECK (amount IS NULL OR amount BETWEEN 0 AND 99999.99),
    CONSTRAINT ck_party_purchase_fields CHECK (
        (purchase_status = 'TODO')
        OR (purchase_status = 'PURCHASED' AND payer_user_id IS NOT NULL AND amount IS NOT NULL)
    ),
    CONSTRAINT ck_party_zero_amount_note CHECK (amount IS NULL OR amount <> 0 OR zero_amount_note IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聚会采购明细表';

CREATE TABLE notification_template (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    business_event      VARCHAR(40) NOT NULL COMMENT '订阅消息对应的业务事件',
    wechat_template_id  VARCHAR(100) NULL COMMENT '微信订阅消息模板ID',
    page_path_template  VARCHAR(255) NOT NULL COMMENT '消息跳转页面路径模板',
    enabled             TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_business_event (business_event),
    CONSTRAINT ck_notification_template_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信订阅消息模板表';

CREATE TABLE wx_subscription_grant (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id             BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    notification_template_id BIGINT UNSIGNED NOT NULL COMMENT '订阅消息模板记录ID',
    latest_response     VARCHAR(16) NOT NULL COMMENT '最近一次订阅授权结果',
    accepted_count      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计同意的订阅次数',
    reserved_count      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已预占待发送的订阅次数',
    consumed_count      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已消费的订阅次数',
    last_consented_at   DATETIME(3) NULL COMMENT '最近一次订阅授权时间',
    last_consumed_at    DATETIME(3) NULL COMMENT '最近一次订阅消费时间',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_user_template (user_id, notification_template_id),
    CONSTRAINT fk_subscription_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_template FOREIGN KEY (notification_template_id) REFERENCES notification_template (id) ON DELETE RESTRICT,
    CONSTRAINT ck_subscription_response CHECK (latest_response IN ('ACCEPT', 'REJECT', 'BAN', 'UNKNOWN')),
    CONSTRAINT ck_subscription_count CHECK (accepted_count >= reserved_count + consumed_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信订阅授权记账表';

CREATE TABLE notification_outbox (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    event_key           VARCHAR(160) NOT NULL COMMENT '通知业务事件唯一键',
    merge_key           VARCHAR(160) NULL COMMENT '可合并通知分组键',
    event_type          VARCHAR(40) NOT NULL COMMENT '通知事件类型',
    business_type       VARCHAR(32) NOT NULL COMMENT '关联业务类型',
    business_id         BIGINT UNSIGNED NOT NULL COMMENT '关联业务数据ID',
    recipient_user_id   BIGINT UNSIGNED NOT NULL COMMENT '通知接收用户ID',
    notification_template_id BIGINT UNSIGNED NULL COMMENT '订阅消息模板记录ID',
    payload_json        JSON NOT NULL COMMENT '通知模板渲染数据JSON',
    page_path           VARCHAR(255) NOT NULL COMMENT '消息点击跳转页面路径',
    status              VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT '通知发送状态',
    first_event_at      DATETIME(3) NOT NULL COMMENT '合并窗口内首次事件时间',
    available_at        DATETIME(3) NOT NULL COMMENT '最早可处理时间',
    attempt_count       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已尝试发送次数',
    next_retry_at       DATETIME(3) NULL COMMENT '下次重试时间',
    platform_request_id VARCHAR(100) NULL COMMENT '微信平台请求标识',
    platform_error_code VARCHAR(64) NULL COMMENT '微信平台错误码',
    platform_error_excerpt VARCHAR(255) NULL COMMENT '微信平台错误摘要',
    sent_at             DATETIME(3) NULL COMMENT '成功发送时间',
    finished_at         DATETIME(3) NULL COMMENT '任务最终结束时间',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_event_key (event_key),
    KEY idx_notification_due (status, available_at),
    KEY idx_notification_retry (status, next_retry_at),
    KEY idx_notification_merge (merge_key, status, first_event_at),
    KEY idx_notification_recipient (recipient_user_id, created_at),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_template FOREIGN KEY (notification_template_id) REFERENCES notification_template (id) ON DELETE SET NULL,
    CONSTRAINT ck_notification_status CHECK (
        status IN ('PENDING', 'SENDING', 'SENT', 'FAILED_RETRYABLE', 'FAILED_FINAL', 'CANCELLED', 'SKIPPED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知发送任务表';

CREATE TABLE festival_theme_config (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    theme_key           VARCHAR(32) NOT NULL COMMENT '节日主题标识',
    year_number         SMALLINT UNSIGNED NOT NULL COMMENT '配置所属年份',
    festival_date       DATE NOT NULL COMMENT '当年节日日期',
    effective_start_at  DATETIME(3) NOT NULL COMMENT '主题生效开始时间',
    effective_end_at    DATETIME(3) NOT NULL COMMENT '主题生效结束时间',
    priority_order      INT NOT NULL COMMENT '主题匹配优先级',
    asset_config_json   JSON NOT NULL COMMENT '主题素材配置JSON',
    enabled             TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_theme_year_key (year_number, theme_key),
    KEY idx_theme_effective (enabled, effective_start_at, effective_end_at),
    CONSTRAINT ck_theme_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_theme_time CHECK (effective_end_at > effective_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='年度节日主题配置表';

CREATE TABLE api_idempotency_record (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id             BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    route_key           VARCHAR(100) NOT NULL COMMENT '接口路由标识',
    idempotency_key     VARCHAR(64) NOT NULL COMMENT '客户端幂等键',
    request_hash        CHAR(64) NOT NULL COMMENT '请求内容SHA-256摘要',
    processing_status   VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' COMMENT '幂等请求处理状态',
    http_status         SMALLINT UNSIGNED NULL COMMENT '缓存响应的HTTP状态码',
    response_json       JSON NULL COMMENT '成功响应缓存JSON',
    expires_at          DATETIME(3) NOT NULL COMMENT '到期时间',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_request (user_id, route_key, idempotency_key),
    KEY idx_idempotency_cleanup (expires_at),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_idempotency_status CHECK (processing_status IN ('PROCESSING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口幂等记录表';

CREATE TABLE biz_operation_log (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    business_type       VARCHAR(32) NOT NULL COMMENT '关联业务类型',
    business_id         BIGINT UNSIGNED NOT NULL COMMENT '关联业务数据ID',
    operation_type      VARCHAR(40) NOT NULL COMMENT '操作类型',
    actor_user_id       BIGINT UNSIGNED NULL COMMENT '操作用户ID',
    request_id          VARCHAR(64) NULL COMMENT '请求追踪ID',
    before_json         JSON NULL COMMENT '操作前的数据快照JSON',
    after_json          JSON NULL COMMENT '操作后的数据快照JSON',
    summary_json        JSON NULL COMMENT '操作摘要JSON',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_operation_business (business_type, business_id, created_at),
    KEY idx_operation_actor (actor_user_id, created_at),
    KEY idx_operation_request (request_id),
    CONSTRAINT fk_operation_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务操作审计表';

-- 基础数据示例：餐厅先建空主厨记录，实际用户登录后由受控脚本绑定 chef_user_id。
INSERT INTO restaurant (restaurant_code, name)
VALUES ('RESTAURANT_A', '我的厨房'),
       ('RESTAURANT_B', '她的餐厅');

INSERT INTO menu_category (restaurant_id, category_type, name, sort_order)
SELECT id, 'SEASONAL', '季节限定', 999
FROM restaurant;

-- 微信模板 ID 必须在真实小程序账号联调后填写并启用。
INSERT INTO notification_template (business_event, wechat_template_id, page_path_template, enabled)
VALUES
    ('ORDER_SUBMITTED', NULL, '/pages/orders/detail?id={businessId}', 0),
    ('ORDER_CHANGED', NULL, '/pages/orders/detail?id={businessId}', 0),
    ('ORDER_CANCELLED', NULL, '/pages/orders/detail?id={businessId}', 0),
    ('MEAL_CONFIRM_DUE', NULL, '/subpackages/chef/confirm?id={businessId}', 0),
    ('REVIEW_INVITATION', NULL, '/subpackages/review/sheet?id={businessId}', 0);
