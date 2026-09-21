-- 微信身份标识区分大小写；昵称限制由应用按可见字符校验，物理长度须容纳组合 emoji。
ALTER TABLE app_user
    MODIFY openid VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '微信小程序OpenID，区分大小写',
    MODIFY unionid VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '微信开放平台UnionID，区分大小写',
    MODIFY nickname VARCHAR(255) NOT NULL DEFAULT '微信用户' COMMENT '当前显示昵称，业务上限20个可见字符';
