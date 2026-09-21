# 我们的餐桌

固定双餐厅的家庭点餐与朋友聚会小程序。当前已开始第一阶段：工程底座、登录与日常准入；菜单、餐单、评价和聚会业务继续按开发计划推进。

## 工程结构

- `our-table-backend/our-table-common`：公共实体、响应、鉴权、时间规则和幂等组件，仅生成 JAR。
- `our-table-backend/our-table-core`：核心服务，8082；登录、个人资料、密令、菜单及主厨管理。
- `our-table-backend/our-table-ordering`：点餐服务，8081；当前提供服务端日期餐次接口。
- `our-table-backend/our-table-party`：聚餐服务，8083；当前为可启动的服务骨架。
- `our-table-miniprogram`：原生 TypeScript 小程序；登录、个人昵称、密令及主厨密令管理已接入接口，其他业务页明确标记开发中。
- `需求文档/我们的餐桌-模块功能开发计划.xlsx`：后续开发进度的工作副本，完成并验证后再勾选。

## 本地运行

使用 Java 17、Maven、MySQL 8 和 Redis。`scripts/backend.sh` 在 macOS 自动选择已安装的 Java 17，并将 Maven 缓存放在 `.local/m2`，不改变机器默认 Java 或私服配置。

1. 将 `config/application-local.example.properties` 复制为 `.local/application-local.properties` 并填写连接配置。当前工作区已准备此文件。真实密码、微信凭据和密令加密密钥均不入 Git。
2. 在项目根目录构建：

   ```bash
   bash scripts/backend.sh verify
   ```

3. 分别在三个终端运行：

   ```bash
   bash scripts/run-service.sh core
   bash scripts/run-service.sh ordering
   bash scripts/run-service.sh party
   ```

4. 健康检查分别为 `http://127.0.0.1:8082/actuator/health`、8081、8083。接口文档本地可通过启动参数 `--springdoc.api-docs.enabled=true --springdoc.swagger-ui.enabled=true` 打开，正式环境默认关闭。

默认不自动迁移。空库由 core 单次加参数 `--spring.flyway.enabled=true` 初始化；既有业务库必须核验结构后才能显式 baseline，不能长期启用 `baseline-on-migrate`。本工作区已有 V1 表已比对字段、索引、约束和中文注释后接入迁移。新增结构变更只写新版本 SQL，不修改已执行迁移。

生产环境为三服务分别创建按表授权的账号；当前提供的本地开发账号不代表生产权限方案已完成。

## 小程序

```bash
cd our-table-miniprogram
npm ci
npm run typecheck
```

微信开发者工具导入 `our-table-miniprogram`。当前 AppID 为游客占位；真实微信登录、分享、订阅通知仍需正式 AppID、AppSecret 和平台联调。后端没有可在生产开启的假登录接口。

本地 `miniprogram/services/config.ts` 指向核心服务回环地址，仅用于开发者工具调试；在工具中关闭本地调试的域名校验。真机须使用可达服务地址，正式版须配置统一 HTTPS 合法域名。不要将本机回环地址发布上线。

默认进入“我的”，未获得日常资格不展示日常 Tab；主厨管理从“我的”进入小程序分包。首页、点餐、餐单、聚会尚未完成，不使用示例数据冒充真实业务。

## 测试与主厨绑定

`bash scripts/backend.sh test` 运行离线单元测试。`bash scripts/test-integration.sh` 启动独立 MySQL 8 / Redis 7，在 13306/16379 执行真实迁移、接口与并发测试，并在退出时停止自己的临时容器，不使用 3306/6379 的业务数据。该命令需要 Docker，首次运行可能下载镜像。

完成真实微信登录、得到内部用户 ID 后，由本地受控运维命令绑定主厨：

```bash
bash scripts/db-admin.sh bind-chef <餐厅ID> <用户ID>
```

命令只允许绑定空缺餐厅、要求有效用户，使用事务并写审计日志；数据库唯一键阻止同一用户绑定两家。小程序不提供主厨认领接口。

详细实现、验证和未完成边界见 `开发记录/2026-09-21-第一阶段.md`。目前不代表首版全量完成，也不代表真实微信消息已经送达。
