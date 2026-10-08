-- 小程序内置聚会封面，文件名带版本，历史引用不可覆盖或清理。
-- 媒体元数据由 core 迁移维护，party 只写活动封面引用；无所属用户，不计孤儿上传。
INSERT INTO media_asset(owner_user_id, usage_type, object_key, mime_type, byte_size, width_px, height_px, sha256, status) VALUES
(NULL, 'PARTY_PRESET', 'builtin/party/table-v1.png', 'image/png', 59932, 1000, 800, 'a956619ffeb220c659b99edc9cc6a3f2754d5ec4d5e68165047812082d8d60c8', 'ACTIVE'),
(NULL, 'PARTY_PRESET', 'builtin/party/hot-pot-v1.png', 'image/png', 58147, 1000, 800, '11b111fadc8fe113adbed2cd72640f2792fd60135ee75bcced26d88170f3aadf', 'ACTIVE'),
(NULL, 'PARTY_PRESET', 'builtin/party/tea-v1.png', 'image/png', 57559, 1000, 800, 'e03aa53649ed35d9a6def691a02309de14d8aed3ee2ba91c3fd4225d440df296', 'ACTIVE');
