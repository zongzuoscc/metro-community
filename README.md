# Metro Community

一个面向内容创作与互动的社区后端服务，提供文章、评论、点赞、收藏、关注、通知、全文检索和实时聊天能力。前端位于仓库的 `frontend` 分支。

## 技术栈

- Java 21、Spring Boot 3、Spring Security、MyBatis-Plus
- MySQL 8、Redis、RabbitMQ、Elasticsearch 8 + IK 中文分词
- WebSocket、阿里云 OSS、Spring AI（默认关闭，需显式配置）

## 已实现能力

- JWT 无状态认证：支持标准 `Authorization: Bearer <token>`，同时兼容现有前端的 `token` 请求头。
- WebSocket 一次性凭证：长期 JWT 仅用于申请 30 秒 ticket，不再出现在 WebSocket URI 中。
- 角色保护：文章、用户和举报管理接口需要管理员角色。
- 内容社区闭环：文章状态流转、评论、点赞、收藏、关注、系统通知与私信。
- Elasticsearch 全文检索、相似文章，以及 RabbitMQ 驱动的异步索引同步。
- Redis 缓存与接口限流。

## 本地启动

### 1. 准备依赖

需要 JDK 21、Docker（或已有的 MySQL 8、Redis、RabbitMQ、Elasticsearch 8）。Elasticsearch 必须安装仓库中 `elasticsearch/` Dockerfile 使用的 IK 插件。

项目 Docker Compose 使用独立默认端口：MySQL `13306`、Redis `16379`、RabbitMQ AMQP `15673` / 管理台 `15674`、Elasticsearch `19200`、Kibana `15601`，避免占用常见的本机开发端口。可以通过 `.env` 中的 `*_HOST_PORT` 覆盖它们。

```bash
docker compose up -d
```

### 2. 初始化数据库与环境变量

创建 `metro_community` 数据库并执行根目录的 [script.sql](script.sql)。随后复制环境变量示例并替换占位符；不要将真实密钥提交到 Git。通过 IDE 的运行环境、Shell 或部署平台将这些变量注入 Spring Boot 进程。已有数据库在启动本版本前必须先备份，并执行 [推荐训练迁移](docs/database/migrations/2026-08-09-recommendation-training.sql)；`script.sql` 仅用于新库初始化。

```bash
cp .env.example .env
```

至少需要设置：`MYSQL_ROOT_PASSWORD`、`DB_PASSWORD`、`REDIS_PASSWORD`、`RABBITMQ_PASSWORD`、`JWT_SECRET`、OSS 相关变量。`.env.example` 已按上述独立端口配置好后端连接地址。`JWT_SECRET` 必须至少 32 个字符。

在 Shell 中本地启动时，可显式加载该文件：

```bash
set -a && source .env && set +a
```

默认 CORS 仅允许 `http://localhost:5173`；生产环境请通过 `CORS_ALLOWED_ORIGINS` 设置前端正式域名。

WebSocket 连接前，已登录客户端需要用 JWT 调用 `POST /api/ws/ticket`，然后仅用返回的 ticket 建立 `/im/{ticket}` 连接。ticket 默认 30 秒过期且只能消费一次；断线重连必须重新申请，不能复用。可通过 `WEBSOCKET_TICKET_TTL` 调整过期时间，建议保持秒级。Redis 不可用时签发返回 HTTP 503，握手验证也会 fail-closed，不降级为 JWT URI 或匿名连接。

```bash
curl -X POST http://localhost:18080/api/ws/ticket \
  -H 'Authorization: Bearer <login-jwt>'
```

推荐排序 Serving 默认关闭（`RECOMMENDATION_ENABLED=false`），但训练任务仍按 Asia/Shanghai 每日 02:15 运行，且不受 Serving 开关影响。模型目录应是应用进程可写的持久化绝对路径。Serving 关闭时返回 chronology `FALLBACK`；启用 Serving 但尚无可用模型时，符合条件的用户收到 chronology `COLD_START`。完整可调参数见 `.env.example`。

RabbitMQ 工作队列现在配置了 3 次有限重试与死信队列（队列名后缀为 `.dlq`）。如果本地 RabbitMQ 已存在由旧版本创建的同名队列，需要先在管理界面删除这些**项目队列**后再启动，以便声明死信交换机参数；不要删除其他项目的队列。

## 推荐流的产品与模型边界

首页“推荐”仅供已认证用户使用，统一调用稳定的 `GET /api/recommendations/feed`；“最新”始终使用按发布时间排序的时间线，不受推荐开关、画像或模型状态影响。该接口是稳定的模型边界：后续可以更换更强的排序模型，而无需改变客户端合约。

推荐 feed 的首屏、游标翻页和降级请求统一按用户执行 Redis 固定窗口限流，默认每 60 秒最多 20 次；计数和 TTL 由同一段 Lua 原子完成，超限返回 HTTP 429。游标请求也纳入限流，避免通过伪造或轮换 cursor 绕过曝光写入边界。限流 Redis 故障采用 fail-open，不改变原有的时间线降级能力。

个性化排序必须同时满足两道固定门槛：当前用户最近 30 天至少 20 条去重有效行为，且全站最近 90 天至少 500 条去重有效行为。`RECOMMENDATION_ENABLED=false` 是默认值，它只关闭个性化 Serving，不停止行为采集或每日训练。

`PERSONALIZED` 表示已通过双门槛并使用有效模型精排；`COLD_START` 表示 Serving 可用，但门槛未满足、可用模型缺失/无效/过期或个性化候选不足，因此返回时间线；`FALLBACK` 表示 Serving 已关闭，或者 Redis 会话/画像、模型 I/O/推理、游标等请求侧边界不可用，同样安全降级为时间线。

训练数据只来自真实的 `recommendation_exposure` 曝光和去重后的 `user_article_event` 事实。曝光持久化五个连续值和四个来源 one-hot，共九个投递时特征；离线 Logistic Regression 使用 7 天归因标签，并且只在验证集模型 AUC 严格优于同一批曝光记录的规则基线 AUC 时发布。这些事实和曝光是训练与可追溯输入，不以 Redis 运维计数代替。

训练 cohort 默认全局最多 50000 条，并且单用户最多贡献 500 条，防止少数高频用户主导样本。归因事实扫描默认最多 200000 条，并额外读取 1 条只用于判断是否触顶；一旦触顶，整批返回 `FACT_SCAN_LIMIT_EXCEEDED` 并跳过发布，不使用不完整标签训练，也不会替换已有 active model。

画像重建只读取最近 30 天中固定上限的最新事实，并对标签关联数、最终 tag 数和 author 数分别设限；14 天半衰期、MySQL 用户级锁和 Redis 双 ZSET 原子替换保持不变。每条事实与 `recommendation_profile_checkpoint.requested_event_id` 在同一 MySQL 事务内落库，消除进程在两次写入之间退出造成的遗漏；画像重建成功后再推进 `rebuilt_event_id`。如果 Redis 重建持续失败直至消息进入 DLQ，定时补偿仍会批量发现 `requested_event_id > rebuilt_event_id` 的用户并从 MySQL 事实恢复画像，不依赖下一次用户行为或人工重放 DLQ；失败补偿采用有界退避，checkpoint 更新使用单调递增语义避免并发完成覆盖新请求。

## 推荐可观测性

推荐投递与新增行为事实会写入 Redis 每日计数器，仅作为 best-effort 运维遥测，不是审计真值；任何指标 Redis 故障都不得使 feed、曝光、事件事实或画像重建失败。计数器保留 40 天，投递来源固定为 `FOLLOW、TAG、SIMILAR、EXPLORE、CHRONOLOGICAL`，事件类型固定为 `VIEW、LIKE、COLLECT、COMMENT、FOLLOW_AUTHOR`。`FALLBACK` 不统计投递；重放真实页面会再记一次投递尝试，但曝光 ID 仍独立幂等；事件只在首次插入事实时计数，日期按行为发生时间转换为 Asia/Shanghai，不使用延迟消费时间。

每日 Asia/Shanghai `00:05` 记录一行固定顺序的结构化摘要。缺失 key 按0 读取；非法数值或 Redis 读取故障记为 `status=unavailable`，不伪造全 0 成功摘要。当前不提供公开指标 API 或 Dashboard，也不在运行时扫描 Redis keyspace。

### 3. 启动后端

```bash
./mvnw spring-boot:run
```

服务默认端口为 `8080`，可通过 `SERVER_PORT` 修改。

## AI 模块状态

当前没有可用的 AI Provider Key 时，聊天、向量、图片和音频模型均保持关闭，应用仍可正常启动。后续启用聊天模型需要设置 `DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL` 与 `AI_CHAT_ENABLED=true`。在重新接入前，请先完成 Provider 配置与集成测试，不应把 API Key 写入配置文件。

## 测试

```bash
./mvnw test
```

集成测试使用 Testcontainers 启动隔离的 MySQL、Redis、RabbitMQ 与带 IK 插件的 Elasticsearch，所有端口均由 Docker 动态分配，不会占用本机已有容器端口。
