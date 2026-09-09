# Metro Community

一个面向内容创作与互动的社区后端服务，提供文章、评论、点赞、收藏、关注、通知、全文检索和实时聊天能力。前端位于仓库的 `frontend` 分支。

## 技术栈

- Java 21、Spring Boot 3.5.16、Spring AI 1.1.8、Spring AI Alibaba Graph 1.1.2.0、MyBatis-Plus 3.5.17
- Spring Security、Spring MVC、Resilience4j、Micrometer
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

### Agent 上下文窗口升级（2026-09）

已有数据库在启动新版本前先备份并执行
[上下文边界迁移](docs/database/migrations/2026-09-08-agent-context-window.sql)及
[回答前压缩迁移](docs/database/migrations/2026-09-09-agent-context-compaction.sql)，新数据库直接用 `script.sql`。
迁移不删除历史；旧数据无法区分此前的手动清空和自动滚段，所以首次迁移以当前活动段作为
安全读取起点。手动清空在事务中推进边界，旧消息仍可在界面回看。

主对话以 turn ID 为排他游标，每页读取 24 轮成功问答；**24 是每页大小，不是总窗口上限**。
持久 turn 使用 Alibaba Graph，在回答前按模型输入预算检查旧原文；接近 70% 水位时，
模型同时生成内部摘要和带用户原文证据的记忆。MySQL 保存待生效批次与记忆版本，Milvus
确认向量可见后才启用摘要。保留最近至少三轮原文，原始历史不修改；失败明确报错并支持批次恢复。
不再使用句首规则提取或长期记忆词法兜底。临时会话完全绕开此持久流程。
部署步骤、跨库一致性和已知边界见[记忆与压缩说明](docs/agent-memory-compaction.md)。

检索采用 ReAct 动作—观察循环：模型读取真实工具结果，动态改写查询，也可换关键词再次调用同一工具；
后端负责用户隔离、联网开关、重复/无进展停止和运行租约检查。默认最多 6 次决策、8 次工具尝试，
提前结束不会用满预算。多次检索的文章与网页依据会合并、去重，网页引用统一编号。
具体机制、模型调用成本和部署兼容项见 [ReAct 说明](docs/agent-react.md)。

平台模型容量仍须部署者确认，默认配置为 32K；未知用户模型按 8K 回退。默认**业务安全上限**
现为 256K，与所选模型容量取较小值，不再把已配置的 128K 模型压回 32K。
预留最多 4096 输出 Token、1024 安全余量后计算输入空间；不会为了填满空间重复加入资料。
参数见 `.env.example` 的 `METRO_AI_CONTEXT_*`。`HISTORY_PAGE_TURNS` 与 `HISTORY_LOAD_TIMEOUT`
仍用于不创建持久 turn 的只读预览旧路径；持久 Graph 分页大小为 24，并受整轮 deadline 约束。
已有环境若显式设了 `WORKING_WINDOW_TOKENS=32768`，需自行提高。
后端可配置 `metro.ai.context.model-windows[模型名]` 覆盖具体容量，例如已确认某模型支持 128K 时
设置 `model-windows: {"my-confirmed-model": 131072}`；不要根据名称猜测容量，也不要将 API Key 写入这里。

估算采用 CL100K 加 15% 余量，并非所有供应商的精确 tokenizer；最终 usage 以供应商返回为准。
资料过多时先裁减检索补充，再减少较老原文；持久路径的最新三轮、有效摘要和原始问题不静默截断。
引用校验和保存的来源仅包含最终发给模型的资料。短追问使用近期用户话题辅助**站内**检索，
不会自动将私有历史拼入联网搜索；这是轻量消歧，不是额外模型生成的完整查询改写。
持久主对话现已改为回答前按 Token 压力触发 Graph 压缩，替代原有异步 episode 摘要任务；
只读预览仍可读取既有摘要，但不会创建压缩批次。修改配置不需要删除聊天记录；模型配置在
本轮开始时冻结，并沿用于决策、HyDE 与最终回答，避免检索期间切换模型造成错配。

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

推荐排序 Serving 默认关闭（`RECOMMENDATION_ENABLED=false`），但训练任务仍按 Asia/Shanghai 每日 02:15 运行，且不受 Serving 开关影响。模型目录应是应用进程可写的持久化绝对路径。Serving 关闭时返回 chronology `FALLBACK`；启用 Serving 但尚无可用模型时，符合条件的用户收到 chronology `COLD_START`。主要可调参数见 `.env.example`。

RabbitMQ 工作队列现在配置了 3 次有限重试与死信队列（队列名后缀为 `.dlq`）。如果本地 RabbitMQ 已存在由旧版本创建的同名队列，需要先在管理界面删除这些**项目队列**后再启动，以便声明死信交换机参数；不要删除其他项目的队列。

## 推荐流的产品与模型边界

首页“推荐”仅供已认证用户使用，统一调用稳定的 `GET /api/recommendations/feed`；“最新”始终使用按发布时间排序的时间线，不受推荐开关、画像或模型状态影响。该接口是稳定的模型边界：后续可以更换更强的排序模型，而无需改变客户端合约。

推荐 feed 的首屏、游标翻页和降级请求统一按用户执行 Redis 固定窗口限流，默认每 60 秒最多 20 次；计数和 TTL 由同一段 Lua 原子完成，超限返回 HTTP 429。游标请求也纳入限流，避免通过伪造或轮换 cursor 绕过曝光写入边界。限流 Redis 故障采用 fail-open，不改变原有的时间线降级能力。

个性化排序必须同时满足两道固定门槛：当前用户最近 30 天至少 20 条去重有效行为，且全站最近 90 天至少 500 条去重有效行为。`RECOMMENDATION_ENABLED=false` 是默认值，它只关闭个性化 Serving，不停止行为采集或每日训练。

`PERSONALIZED` 表示已通过双门槛并使用有效模型精排；`COLD_START` 表示 Serving 可用，但门槛未满足、可用模型缺失/无效/过期或个性化候选不足，因此返回时间线；`FALLBACK` 表示 Serving 已关闭，或者 Redis 会话/画像、模型 I/O/推理、游标等请求侧边界不可用，同样安全降级为时间线。

训练数据只来自真实的 `recommendation_exposure` 曝光和去重后的 `user_article_event` 事实。曝光持久化九个投递时特征（五个连续值和四个来源 one-hot）以及 `article_author_id` 快照；`FOLLOW_AUTHOR` 最后触点直接按快照索引归因，不会因文章作者记录后续改变而重写历史标签。离线 Logistic Regression 使用 7 天归因标签，并且只在验证集模型 AUC 严格优于同一批曝光记录的规则基线 AUC 时发布。这些事实和曝光是训练与可追溯输入，不以 Redis 运维计数代替。

训练先按 `(exposed_at, id)` 索引读取最新曝光，默认硬上限 200000 条加 1 条 sentinel，再在 Java 内应用全局 50000 条和单用户 500 条配额，避免 MySQL 先对 90 天全量曝光做窗口排序。曝光扫描触顶返回 `EXPOSURE_SCAN_LIMIT_EXCEEDED`。归因事实扫描另有独立的 200000 条加 1 条 sentinel 上限，触顶返回 `FACT_SCAN_LIMIT_EXCEEDED`。任一扫描不完整都会跳过发布，不使用截断数据训练，也不会替换已有 active model。

画像重建只读取最近 30 天中固定上限的最新事实，并对标签关联数、最终 tag 数和 author 数分别设限；14 天半衰期、MySQL 用户级锁和 Redis 双 ZSET 原子替换保持不变。每条事实与 `recommendation_profile_checkpoint.requested_event_id` 在同一 MySQL 事务内落库，消除进程在两次写入之间退出造成的遗漏；画像重建成功后再推进 `rebuilt_event_id`。补偿任务通过 `(needs_rebuild, next_attempt_at, user_id)` 索引只选择显式 pending 的 checkpoint，追平后即清除 pending，不再反复扫描已完成历史。如果 Redis 重建持续失败直至消息进入 DLQ，定时补偿仍会从 MySQL 事实恢复画像，不依赖下一次用户行为或人工重放 DLQ；失败补偿采用有界退避，checkpoint 更新使用单调递增与条件语义避免并发完成覆盖新请求。

## 推荐可观测性

推荐投递与新增行为事实会写入 Redis 每日计数器，仅作为 best-effort 运维遥测，不是审计真值；任何指标 Redis 故障都不得使 feed、曝光、事件事实或画像重建失败。计数器保留 40 天，投递来源固定为 `FOLLOW、TAG、SIMILAR、EXPLORE、CHRONOLOGICAL`，事件类型固定为 `VIEW、LIKE、COLLECT、COMMENT、FOLLOW_AUTHOR`。`FALLBACK` 不统计投递；重放真实页面会再记一次投递尝试，但曝光 ID 仍独立幂等；事件只在首次插入事实时计数，日期按行为发生时间转换为 Asia/Shanghai，不使用延迟消费时间。

每日 Asia/Shanghai `00:05` 记录一行固定顺序的结构化摘要。缺失 key 按0 读取；非法数值或 Redis 读取故障记为 `status=unavailable`，不伪造全 0 成功摘要。当前不提供公开指标 API 或 Dashboard，也不在运行时扫描 Redis keyspace。

### 3. 启动后端

```bash
./mvnw spring-boot:run
```

服务默认端口为 `8080`，可通过 `SERVER_PORT` 修改。

## Stage B 文章修订与切换运维

Stage B 已提供不可变文章修订、人工双对象 CAS、事务 Outbox、搜索/通知投影，以及持久化的
rollout checkpoint。升级已有数据库时，先按
[schema expand runbook](docs/database/operations/2026-08-10-stage-b-schema-expand-runbook.md)
执行迁移；生产晋级只遵循
[Stage B cutover runbook](docs/database/operations/2026-08-10-stage-b-cutover-runbook.md)。旧的进程内
mode 文档已经废弃，不能用 ConfigMap 热改或混合版本副本代替 checkpoint/operator CAS。

应用启动必须注入不可变构建身份；digest 是 OCI `sha256:` 后面的 64 位小写十六进制值，generation
必须是非负整数。缺失、格式错误、低于 checkpoint 或与授权构建不一致都会 fail closed：

```text
METRO_ARTICLE_ROLLOUT_BUILD_DIGEST=<64 lowercase hex>
METRO_ARTICLE_ROLLOUT_BINARY_GENERATION=<non-negative integer>
METRO_ARTICLE_ROLLOUT_SCHEMA_GENERATION=<non-negative integer>
METRO_ARTICLE_REVISION_MODE=LEGACY
```

`METRO_ARTICLE_REVISION_MODE` 是 pod 的目标模式，只允许按
`LEGACY -> SHADOW -> VERIFY_FENCE -> POINTER_READ -> CUTOVER` 晋级。普通 pod 的
`METRO_STAGE_B_MIGRATION_ACTION=NONE`；受审计的一次性 operator 才能设为 `BACKFILL` 或
`VERIFY`，并必须提供 `METRO_STAGE_B_OPERATOR_IDENTITY`。VERIFY 还必须把
`METRO_STAGE_B_VERIFICATION_REPORT_PATH` 设为受控归档目录下绝对且尚不存在的文件路径；命令以
owner-only `CREATE_NEW` 创建完整报告。镜像 digest admission allowlist、旧 pod
及旧 DB/RabbitMQ credential 的排空与撤销属于切换硬门槛，详见权威 runbook。

持久化 checkpoint 的初始化、晋级、sentinel、forward-fix 和 emergency fence 只能由
one-shot operator 执行。唯一环境变量入口是 `METRO_STAGE_B_ROLLOUT_ACTION`，取值为
`BOOTSTRAP_LEGACY`、`ADVANCE`、`BEGIN_SENTINEL`、`RECORD_SENTINEL`、
`AUTHORIZE_BUILD` 或 `EMERGENCY_FENCE`；普通 pod 必须保持 `NONE`。`ADVANCE`、sentinel
文件路径和新构建身份的 action-specific 参数及可复制命令只以权威 cutover
runbook 为准；任一必需参数缺失都会非零退出。

破坏性事件留存调度默认关闭，只读 backlog metrics 默认开启。删除任务启用前必须检查搜索水位和
DEAD operator-resolution 事实；其批次、每次最大批数和 UTC cron 均显式可配：

```text
METRO_DOMAIN_EVENT_RETENTION_SCHEDULING_ENABLED=false
METRO_DOMAIN_EVENT_RETENTION_CRON="0 30 4 * * *"
METRO_DOMAIN_EVENT_RETENTION_BATCH_SIZE=200
METRO_DOMAIN_EVENT_RETENTION_MAX_BATCHES=20
METRO_DOMAIN_EVENT_RETENTION_METRICS_ENABLED=true
METRO_DOMAIN_EVENT_RETENTION_METRICS_DELAY=PT5M
METRO_DOMAIN_EVENT_RETENTION_METRICS_INITIAL_DELAY=PT30S
```

## AI 安全底座（Stage A）

Stage A 只交付默认关闭的 AI 安全底座：Provider 中立的网关契约、平台 OpenAI 兼容适配器与 Ollama 向量适配器、按能力隔离的输入上限/配额/截止时间/有界线程池/重试/熔断/低基数指标，以及始终存在的人工审核降级路径。内容审核在该阶段不调用模型，文章保持人工待审，不会自动通过或拒绝。

下列六个业务开关的默认值均为 `false`：

```text
METRO_AI_ENABLED=false
METRO_AI_AGENT_ENABLED=false
METRO_AI_MEMORY_ENABLED=false
METRO_AI_WRITING_ENABLED=false
METRO_AI_MODERATION_ENABLED=false
METRO_AI_EMBEDDING_ENABLED=false
```

它们依次映射 `metro.ai.enabled`、`metro.ai.agent.enabled`、`metro.ai.memory.enabled`、`metro.ai.writing.enabled`、`metro.ai.moderation.enabled` 和 `metro.ai.embedding.enabled`。

因此未配置 Key 时应用仍可启动，不创建平台 Chat 或 Ollama Embedding 调用对象，Provider 服务默认不会被访问。普通社区、私信、搜索、推荐和人工审核不依赖 AI Key。

上述 Stage A 描述是初期安全底座的交付边界，不代表当前功能状态。后续已加入文章分块、
混合检索与 HyDE、Agent 对话与 SSE 事件、写作建议和长期记忆；当前 ReAct/压缩机制见本页
前文及对应说明。代码存在不等于依赖与真实模型已部署，启用前仍需完成配置和部署验证。

Spring AI/Ollama 依赖只是客户端与运行时基础，不表示本机已运行 Ollama 或已下载 `bge-m3`。`qwen-plus` 也只是可配置的平台默认模型名，在显式验收前不代表模型可用性或质量结论。Prometheus registry 的依赖存在也不代表已公开 scrape endpoint 或交付 Dashboard；当前 Actuator 只暴露 health。

### 隔离环境的 Provider 验证

仅在非生产、隔离的验收环境中，才可把所需的 `METRO_AI_*_ENABLED` 开关显式改为 `true`，并注入 `METRO_AI_PLATFORM_PROVIDER`、`METRO_AI_PLATFORM_BASE_URL`、`METRO_AI_PLATFORM_API_KEY`、`METRO_AI_PLATFORM_MODEL`、`OLLAMA_BASE_URL` 和 `OLLAMA_EMBEDDING_MODEL`。本地开发可以把这些值写入已被 Git 忽略的 `.env`；生产环境必须使用部署平台的 Secret。密钥不得写入 YAML、Git 或测试报告。平台配置不会返回浏览器，用户自带 API 则走独立的加密数据库记录。

真实 Provider smoke 不属于常规 CI。如果没有专用 Key，结果必须记为 `NOT RUN`，不能记为 PASS；无论是否执行，都不记录凭据、prompt、模型原始输出或 Provider 错误体。

## 测试

```bash
./mvnw test
```

集成测试使用 Testcontainers 启动隔离的 MySQL、Redis、RabbitMQ 与带 IK 插件的 Elasticsearch，所有端口均由 Docker 动态分配，不会占用本机已有容器端口。
