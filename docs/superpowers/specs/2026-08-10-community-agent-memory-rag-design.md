# Metro Community 单 Agent、长期记忆与混合检索设计

## 1. 目标

Metro Community 在保留社区主流程、人工审核与现有推荐系统的前提下，重建现有 AI 原型，交付一个真正能根据页面上下文选择只读工具、检索社区知识、生成可校验引用并形成可审计长期记忆的单 Agent。

产品只有一个 Agent 核心。桌宠只是 Agent 的官方形象、交互入口和人格外观，用户可以自定义名称、形象与交互人格，但不能创建第二个 Agent、第二套工具权限或第二份记忆。

必须同时满足：

- 每个用户只有一条可见 Agent 对话时间线，不管理多个会话。
- 内部用隐藏 episode 分段，支持“清空上下文”而不删除历史。
- Agent 权限固定为 1 级：只允许检索、读取、总结、解释、比较和建议。
- Agent 没有关注、点赞、收藏、评论、编辑、保存、发布、删除或任意 HTTP/SQL 工具。
- 当前文章、社区知识和个人长期记忆都能按权限进入上下文，并明确展示来源。
- 长期记忆可查看、编辑、暂停、删除、导出和整体关闭；敏感记忆必须经用户确认。
- 临时对话不读取、不提取、不持久化长期记忆。
- Tiptap 写作助手只生成选区 diff，用户确认后才由前端应用；Agent 永远不能保存或发布文章。
- 现有文章审核工作流保留，但改造成修订绑定、结构化输出、Outbox、幂等和人工兜底的安全状态机。
- AI、Milvus、Ollama 或 Provider 不可用时，普通社区、人工审核、私信、搜索、编辑和推荐仍能运行。

## 2. 已确认的产品决策

### 2.1 单一 Agent 与单一时间线

每个用户只有一个 `agent_conversation`。前端不展示“新建对话”“切换会话”或会话列表。

“清空上下文”会封存当前 episode、生成滚动摘要并新建 episode。旧消息仍属于同一时间线，可分页和搜索，但默认上下文不再携带旧 episode 的逐条消息。

每次生成只装配：

- 当前 episode 最近 12 至 20 条消息；
- 当前 episode 滚动摘要；
- 当前页面上下文；
- 与问题相关的长期记忆；
- 本轮检索得到的社区证据。

同一用户同时只允许一个活动生成。重复提交由 `clientRequestId` 幂等复用同一个 turn。

### 2.2 桌宠入口

官方桌宠采用用户确认的原创像素二次元形象：长深色头发、黑框眼镜、黑白格纹无袖上衣、宽松白裤与白鞋。生产资源最终必须转为透明背景并验证动画，不直接把当前白底概念图当成 sprite atlas。

八个核心状态：

1. idle
2. wake / wave
3. reading
4. thinking
5. retrieving
6. writing
7. success
8. recoverable error

桌宠动画只能补充状态，不能代替“正在检索”“生成失败”等文本状态。

桌面端点击桌宠打开约 420px 的锚定面板，并可无损展开为完整视图。首版小于等于 760px 只显示 64px 入口；点击后跳转到只读轻量 Agent 页面，不交付移动端写作 diff 或桌面大面板，不能留下无响应的死控件。

每个用户有一条 `agent_profile`：

```text
user_id, pet_name, appearance_type, appearance_asset_url,
personality_preset, personality_text,
created_at, updated_at, lock_version
PRIMARY KEY(user_id)
```

- 官方形象是默认值，并保留完整八状态动画。
- 首版自定义形象允许安全校验后的静态 PNG/JPEG，最大 2MB、64 至 512px；不接受 SVG、HTML、脚本或远程任意 URL。自定义静态形象仍配合文字状态，不伪造缺失的动画帧。
- `pet_name` 最长 20 个 Unicode code point。
- `personality_text` 最长 300 字，只能影响语气与称呼，作为低优先级用户偏好；不能修改系统规则、工具白名单、记忆策略、配额或权限。
- 资源上传复用受认证文件服务，并在服务端解码、重新编码和校验 MIME，不信任扩展名。

### 2.3 页面能力

- 文章详情：总结全文、解释选中段落、对比相关文章、查找争议点、核对证据。
- 发布页：润色、缩写、扩写、标题、提纲、摘要和标签建议；只处理选区或显式选择的范围。
- 普通页面：社区知识问答、找文章、搜索自己的对话记录、管理长期记忆。
- 原聊天页只保留人与人私信，删除用户 `9999` 的 AI 机器人入口和特殊 WebSocket 分支。

全局 Agent 组件挂载在 `App.vue` 的 router-view 同级并 Teleport 到 body；登录、注册、找回密码和后台管理页隐藏。发布页入口避让 76px 固定底栏，文章详情页删除旧 AI 摘要卡，不能并存两个 AI 入口。Element Plus 对话框层级保持高于 Agent 面板。

### 2.4 单时间线面板交互

桌面面板固定为一套结构，不出现会话列表：

1. Header：桌宠名称、当前文本状态、临时模式开关、记忆状态入口、展开/收起和设置。
2. Context strip：由服务端确认的“当前文章/选区/写作草稿”摘要，可显式移除；不能由前端伪造 ACL。
3. Quick actions：文章页显示总结/解释选区/对比相关文章；发布页显示润色/缩写/扩写/标题/提纲/摘要/标签；普通页显示社区问答/找文章。
4. Timeline：一条连续历史，episode 只显示轻量日期/上下文分隔，不出现可管理的会话卡片。
5. Composer：支持停止、重试和“清空上下文”，不提供附件上传、联网浏览或写操作授权。
6. Source drawer：知识引用与 memoryUses 分栏，文章引用可跳转，个人记忆只跳记忆中心。

发布页的建议可以从面板发起，但 diff 预览、应用、撤销和 stale 提示必须在 Tiptap 编辑区域完成；面板不能用一条“已润色”消息替代真实 diff。

桌宠按钮、面板、状态和 citation 均可用键盘操作，提供可读 aria-label、焦点圈和 Escape 关闭；遵守 `prefers-reduced-motion`，关闭动画时仍保留完整文本状态。面板不得遮挡保存/发布按钮、错误提示或 Element Plus modal。

## 3. 明确非目标

首版不做：

- 多 Agent、Planner/Executor 多智能体网络或 Agent 间通信；
- 写权限工具、任意 URL 抓取、任意 SQL、文件系统工具或 MCP；
- 独立 AI 微服务或 Python 服务；
- 模型微调、知识图谱、语音助手、图片生成或视频理解；
- Milvus 内置 BM25，关键词检索仍由 Elasticsearch 负责；
- LLM 参与首页推荐排序，现有 Logistic Regression 推荐链路保持独立；
- 完整移动端写作助手和移动端大面板；
- 默认自动发布或自动拒绝文章；
- 把“有引用”宣传成“事实一定正确”，或把当前实现宣传成 ChatGPT/豆包同等级记忆。

## 4. 现有实现必须先修复的问题

现有 AI 只能视为原型，以下问题属于上线前门禁：

1. `ChatUtils` 只在 AI 开启时创建 Bean，却承担普通私信落库；AI 关闭时普通消息可能静默保存失败，并且每条消息创建原生线程。
2. 文章审核只携带 `articleId`。模型读取旧正文后，作者可以修改当前正文，旧 PASS 最终会公开未审核的新正文。
3. 审核使用自由文本 `PASS/REJECT`，异常或畸形输出存在 fail-open 路径，文章正文也能进行提示词注入。
4. Provider 异常被转换为字符串并正常 ACK，Rabbit 重试与 DLQ 无法生效，文章可能永久停在审核中。
5. 发布事务提交前直接发送 Rabbit，消费者可能先读取旧状态并 ACK，导致审核任务永久丢失。
6. 文章总结只按 ID 查询，没有复用可见性 ACL，可能把他人草稿、拒绝稿或回收站正文发给外部 Provider。
7. 审核、摘要和聊天共用带搜索工具的 ChatClient；不可信正文可能诱导不需要工具的能力调用工具。
8. 聊天使用 GET 传 prompt，没有 AI 专用长度、配额、超时、舱壁、日预算和完整指标。
9. 已发布文章进入待审或被拒绝时，旧 Elasticsearch 文档可能继续可见。
10. 当前没有覆盖 AI 开启路径、审核竞态、Provider 故障、引用、记忆隔离和工具权限的真实集成测试。

## 5. 技术基线与升级策略

本轮使用：

| 组件 | 基线 |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8 |
| MyBatis-Plus | 3.5.17 Boot 3 Starter |
| DeepSeek | 专用 Spring AI DeepSeek Starter，模型名配置化 |
| Embedding | Ollama `bge-m3`，1024 维 |
| Elasticsearch | 保持 8.x；独立检查点升级至 8.18.1 |
| Milvus | `milvusdb/milvus:v2.6.20` |
| Milvus Java SDK | `io.milvus:milvus-sdk-java:2.6.22` |

不在同一轮升级到 Spring Boot 4、Spring AI 2 和 Elasticsearch 9。它们会同时引入 Jakarta EE 11、Framework 7、Jackson 3、工具调用循环变化和 ES 9 客户端迁移，必须留作独立平台升级。

从 `pom.xml` 移除：

- `spring-ai-openai-spring-boot-starter`
- Spring Milestone 仓库
- 旧 `defaultFunctions(...)` 注册方式
- 以 `spring.ai.openai.chat.enabled` 作为业务总开关的配置

增加：

- `spring-ai-starter-model-deepseek`
- `spring-ai-starter-model-ollama`
- 与 Milvus 2.6.x 匹配的官方 Java SDK
- `spring-boot-starter-webflux`，只用于 Provider Reactor 流和 SSE 支持，现有应用仍保持 Servlet/MVC
- `spring-boot-starter-actuator`
- Prometheus Micrometer Registry
- Resilience4j Spring Boot 3 Starter，用于能力级 Retry、TimeLimiter、Bulkhead 与 CircuitBreaker
- MyBatis-Plus Boot 3 Starter 3.5.17

Milvus 使用项目自己的 `ArticleVectorRepository` 和 `MemoryVectorRepository`，而不是让通用 Spring AI VectorStore 自动创建生产 Collection。Spring AI 负责 ChatModel 与 EmbeddingModel；Milvus schema、索引、partition key、alias、upsert、search 和 delete 由原生 Java SDK 显式控制。

Milvus Server、SDK 和 gRPC 传递依赖必须经过真实容器契约测试。Compose 不使用可漂移的 `latest`，实现时把 `v2.6.20` 镜像解析为 digest 并写入部署锁定文件；digest 或 SDK 任何变化都重新运行 Collection、filter、alias、删除和重启恢复测试。

## 6. 总体架构

```text
Vue / Tiptap / 全局桌宠
        |
        | JWT + POST + clientRequestId
        v
Agent API / 配额 / 单用户并发 / SSE 事件
        |
        v
Java AgentOrchestrator
        |
        +-- ConversationContextAssembler
        |     +-- MySQL 最近消息与 episode 摘要
        |     +-- Redis 运行状态与短期缓存
        |     +-- MySQL + Milvus 相关长期记忆
        |
        +-- ReadOnlyPlanner
        |     +-- currentArticle
        |     +-- communitySearch
        |     +-- openPublishedArticle
        |     +-- comparePublishedArticles
        |     +-- searchOwnConversation
        |
        +-- HybridRetrievalService
        |     +-- Elasticsearch BM25
        |     +-- Milvus Dense
        |     +-- 可选 HyDE Dense
        |     +-- Java RRF、去重与规则重排
        |     +-- MySQL 回源 ACL
        |
        +-- GroundedAnswerService
              +-- 无工具 Synthesizer
              +-- CitationValidator
              +-- Markdown / URL Sanitizer

MySQL Outbox -> RabbitMQ
        +-- 文章分块 / Embedding / ES 与 Milvus 投影
        +-- 记忆候选提取 / Embedding / 删除对账
        +-- revision 绑定的文章审核
```

保持模块化单体，不新建 AI 微服务。Java 包边界：

```text
ai.provider
ai.agent
ai.conversation
ai.memory
ai.retrieval
ai.ingestion
ai.writing
ai.moderation
ai.observability
```

模块只通过接口依赖：

- `AiChatGateway`
- `EmbeddingGateway`
- `ArticleVectorRepository`
- `MemoryVectorRepository`
- `ArticleRetrievalGateway`
- `AgentConversationRepository`
- `MemoryRepository`
- `ModerationRepository`

业务代码不得直接依赖 Spring AI 内部工具循环或 Milvus SDK 类型。

## 7. Agent 编排与只读工具

### 7.1 为什么它是 Agent 而不是固定 Workflow

每轮请求由 Planner 根据用户目标、当前路由、页面元数据和已有工具结果，动态选择需要的只读工具、参数与执行顺序。它可以在一次请求内并行检索、打开文章、比较多个来源，并在证据不足时做一次受限的检索修正。

关键安全流程仍是确定性状态机。模型不能决定权限、userId、最终引用是否合法、记忆是否激活、文章是否发布或 diff 是否应用。

### 7.2 两段式安全编排

1. Planner 只读取用户问题、当前路由、后端确认的页面 ID 和工具的结构化摘要，不读取完整不可信文章正文。
2. Java 执行只读工具并完成 ACL、检索和回源。
3. Planner 最多允许一次检索修正；整个请求最多 2 个 planning round、4 次工具调用。
4. Synthesizer 读取已经筛选的证据并生成答案，但没有任何工具。
5. CitationValidator 对来源和 quote 做确定性校验。

这样既保留动态工具规划，又避免文章正文中的间接提示词注入反向控制工具调用。

### 7.3 工具白名单

- `getCurrentPageContext`
- `searchCommunityArticles`
- `openPublishedArticle`
- `comparePublishedArticles`
- `searchOwnConversation`

长期记忆召回由 `ConversationContextAssembler` 内部完成，不暴露为可传入任意 `userId` 的模型工具。

所有工具的用户身份只取自 Spring SecurityContext。模型参数和前端请求体不能指定 userId。

当前页面上下文也不能信任前端提供的 revision、正文、作者或可见性。前端只提交 route、articleId、selection 和 editorVersion 等定位信息；服务端必须按 `(articleId, authenticatedUserId)` 或“当前公开 revision”重新解析可见内容。详情页总结只能读取当前公开 revision，作者写作只能读取本人的 mutable draft；选区正文只有在用户显式触发写作/解释动作时才发送给 Provider。

`agent_profile.personality_text` 作为低优先级样式输入，仅能改变措辞、称呼和语气。它与文章、记忆、检索结果一样放在明确的 untrusted data 区域，绝不能拼入 system/developer 指令，也不能新增工具、放宽 ACL、改变记忆开关或突破配额。

## 8. 对话、episode 与流式运行数据

### 8.1 MySQL 表

#### `agent_conversation`

```text
id, user_id, last_message_id, memory_epoch,
created_at, updated_at, lock_version
UNIQUE(user_id)
```

数据库层保证一个用户只有一条 Agent 时间线。

#### `agent_episode`

```text
id, conversation_id, episode_no, state,
opened_at, sealed_at, summary_text, summary_hash,
turn_count, token_count, created_at, updated_at
```

状态：

```text
ACTIVE -> SEALED -> SUMMARIZING -> READY | FAILED
```

通过 generated column + unique key 保证每个 conversation 最多一个 ACTIVE episode。

#### `agent_turn`

```text
id, user_id, conversation_id, episode_id,
run_id, client_request_id, request_hash,
task_type, page_context_json, grounding_mode,
state, run_fence, lease_until, error_code,
started_at, completed_at, expires_at
UNIQUE(conversation_id, client_request_id)
UNIQUE(run_id)
```

状态：

```text
RECEIVED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
```

`run_fence` 来自下面统一 run guard；worker 的 delta、canonical snapshot 和终态写入都必须使用 `runId + runFence + current state` CAS，过期 worker 不能提交晚到结果。

#### `agent_run_guard`

持久与临时 turn 共用同一条不含对话正文的 MySQL 并发门：

```text
user_id PRIMARY KEY,
active_run_id, active_run_type,
run_fence, lease_until, lock_version,
updated_at
UNIQUE(active_run_id)
```

`run_fence` 对每个用户单调递增；run 完成后清空 active_run_id/type，但绝不回退 fence。持久 turn 的 runId 与临时 Redis turn 的 runId 都是 BINARY(16) UUID。

#### `agent_message`

只保存可见 USER/ASSISTANT 消息：

```text
id, turn_id, conversation_id, episode_id,
role, state, content, content_hash,
created_at, completed_at
UNIQUE(turn_id, role)
INDEX(conversation_id, id DESC)
```

工具轨迹单独保存到 `agent_tool_call`，只持久化工具名、规范化参数、状态、结果 hash、耗时和错误码；不长期复制整份工具结果。

`agent_retrieval_hit` 保存 30 天诊断数据：

```text
turn_id, source_type, source_key,
article_id, revision_id, chunk_id, memory_id,
bm25_score, dense_score, rrf_score, rank_no,
excerpt_snapshot, metadata_json, expires_at
UNIQUE(turn_id, source_type, source_key)
```

`agent_answer_citation` 保存最终引用：

```text
assistant_message_id, ordinal,
article_id, revision_id, chunk_id,
title_snapshot, quote_snapshot, quote_hash,
state, created_at, redacted_at
UNIQUE(assistant_message_id, ordinal)
```

对话、episode、turn、message、tool、retrieval 和 citation 均冗余 userId 或通过组合键建立数据库级同用户约束。至少创建 `(id,user_id)` 组合唯一键，并使用组合外键保证 episode 属于 conversation、turn 属于 episode/conversation、message/tool/citation 属于同用户 turn。所有 Repository 查询仍必须 principal-scoped，数据库外键不是 ACL 的替代品。

现有 `chat_msg` 继续只保存人与人私信。机器人 `9999` 的旧记录不迁入 Agent 时间线。

### 8.2 临时对话

每个用户最多有一个 Redis 临时时间线。进入临时模式时创建 `temporarySessionId`，默认 24 小时 TTL：

- 不创建 conversation/episode/turn/message 行；
- 不读取长期记忆；
- 不触发记忆提取；
- 页面明确提示内容仍会在本次请求中发送给模型 Provider。

协议：

```text
POST   /api/agent/temporary-sessions
GET    /api/agent/temporary-sessions/current
DELETE /api/agent/temporary-sessions/current
```

重复创建临时 session 幂等返回当前实例，不创建第二条，也不延长 absolute expiresAt。连续消息只装配该 temporarySessionId 的 Redis 历史；退出、清空、TTL 到期或服务重启后不可恢复，并返回明确 `TEMPORARY_SESSION_EXPIRED`。临时 turn 的幂等键为 `(userId, temporarySessionId, clientRequestId)`；临时 turn 与持久 turn 使用同一个用户运行租约，不能各自并行一个生成。活动 turn 存在时退出/删除临时 session 返回 409，不隐式取消。

### 8.3 运行与 SSE

采用两步协议，避免把 prompt 放进 GET URL，并支持断线续传：

```text
POST /api/agent/turns
GET  /api/agent/turns/{turnId}
GET  /api/agent/turns/{turnId}/events?after=<eventId>
POST /api/agent/turns/{turnId}/cancel
```

`POST` 请求包含：

```json
{
  "clientRequestId": "uuid",
  "message": "用户问题",
  "temporary": false,
  "temporarySessionId": null,
  "context": {
    "route": "ARTICLE_DETAIL",
    "articleId": 123,
    "clientObservedRevisionId": 7,
    "selection": null,
    "editorVersion": null
  }
}
```

admission 顺序固定，不能由各实现自行调整：

1. 对 Redis 运行依赖做快速健康预检，但不把它当并发真相源；
2. MySQL 事务 `SELECT ... FOR UPDATE` 当前用户的 `agent_run_guard`；已有 active run 则 409，否则递增 fence 并写入新 runId/type；
3. 持久模式在同一事务写 turn、USER message 和 `AGENT_TURN_REQUESTED` Outbox；临时模式只写 guard，不写对话内容；
4. 事务提交后，以 Lua claim `{userId,runId,runFence}` Redis lease：无 key、旧 fence 或同 run 续租才成功；更高 fence 必须拒绝；
5. Redis claim 失败时，不运行模型；持久 run 由恢复 worker以同一 fence 重试，临时 run 以 guard CAS 清理并返回 503。

这样不会出现“先插持久 turn、后取 lease”时临时 run 穿透的窗口。恢复 worker 会扫描 stale RECEIVED/RUNNING 与 guard；临时 turn 使用 Redis job，服务重启后终止为 `ABORTED_BY_RESTART`。

持久 turn 的创建事务同时写 USER message；成功收尾事务同时写 ASSISTANT final message、citation、turn 终态和 `MEMORY_EXTRACTION_REQUESTED` Outbox。任何一个写入失败都不能出现“前端收到 done 但 MySQL 没有 final message”的半状态。SSE 的 `done` 只能在该收尾事务提交后发出；重放时以 MySQL final message 为权威。

同一 `clientRequestId + requestHash` 返回原 turnId；相同 ID 不同 hash 返回 `409 IDEMPOTENCY_CONFLICT`。已有其他活动 turn 时返回 `409 ACTIVE_TURN_EXISTS` 并携带 activeTurnId。活动生成期间 reset-context 返回 409。

Redis 用 Lua claim/compare-renew/compare-release 维护统一 user lease，值包含 `userId + runId + runFence`。worker 一旦续租失败必须立即取消上游订阅。持久 run 收尾事务锁定 guard 与 turn，只有二者仍同时匹配 `activeRunId + runFence + RUNNING` 才能写 final、终态并清 guard；临时 delta/snapshot/done 每次都由 Lua 校验当前 lease 的 runId/fence。新 run admission 提升 fence 后，旧持久 worker 的 MySQL CAS 和旧临时 worker 的 Redis Lua 都不能再提交。

前端明确使用 `fetch + ReadableStream + AbortController` 消费 SSE，Bearer Token 只放 Authorization Header，禁止放 query。事件和取消接口按 `(turnId, authenticatedUserId)` 校验所有权；临时 turn 在 Redis 保存 `turnId -> userId + temporarySessionId` 绑定。越权统一返回 404。CORS 允许 Authorization、Last-Event-ID、Content-Type 和 Idempotency-Key，且允许 PATCH。

事件保存到 Redis Stream `agent:turn:{turnId}:events`，delta 按 20 至 50ms 或固定字符数合并，限制最多 500 项并设置 30 分钟 TTL；canonical partial/final assistant snapshot 独立保存。每 10 至 15 秒发送心跳。

统一事件 envelope：

```json
{
  "eventId": "stream-sequence",
  "schemaVersion": 1,
  "turnId": 123,
  "type": "delta",
  "occurredAt": "2026-08-10T12:00:00Z",
  "payload": {}
}
```

`GET /api/agent/turns/{turnId}` 返回可直接恢复 UI 的 `TurnSnapshotResponse`：

```json
{
  "turnId": 123,
  "state": "RUNNING",
  "taskType": "COMMUNITY_QA",
  "temporary": false,
  "createdAt": "2026-08-10T12:00:00Z",
  "startedAt": "2026-08-10T12:00:01Z",
  "completedAt": null,
  "lastEventId": "42-0",
  "partialMessage": "已生成但尚未完成的文本",
  "finalMessage": null,
  "citations": [],
  "memoryUses": [],
  "messageId": null,
  "finishReason": null,
  "error": null
}
```

终态 snapshot 的 `finalMessage/citations/messageId` 来自同一 MySQL 收尾事务；临时 turn 则来自仍有效的 Redis session。别人的 turn 与过期临时 turn统一 404/410，不返回存在性信息。

SSE 使用 `after` 或 Last-Event-ID 续传。若客户端位置早于 Stream 首项，返回 `410 EVENT_STREAM_EXPIRED` 并让客户端通过 `GET /turns/{id}` 读取 canonical partial/final snapshot，不能拼接缺前缀的 delta。`delta` 只是临时草稿，`done.finalMessage` 才是经过引用校验的权威结果。

事件类型：

```text
accepted              { state }
planning              { phase, displayText }
retrieving            { strategy, queryCount }
retrieval_completed   { bm25Count, denseCount, authorizedCount }
generating            { phase }
delta                 { seq, textAppend }
citations             { citations[] }
done                  { finalMessage, finishReason, citations[], memoryUses[], messageId }
error                 { code, message, retryable, partialRetained }
cancelled             { partialRetained }
```

`citations` 事件只能在引用校验完成后发送；`done` 必须是最后一个成功事件，`error/cancelled` 是互斥的失败终态，终态之后同一 run fence 的任何 delta/done 都被拒绝。取消会中止上游订阅、以 run fence CAS 标记 CANCELLED、保留可见 partial 但不触发记忆提取，并拒绝晚到 done。重试创建新 clientRequestId，并可引用 previousTurnId。

`POST /conversation/reset-context` 使用 Idempotency-Key。没有活动 turn 时，它在一个事务中 seal 旧 episode 并立即创建新 ACTIVE episode；旧 episode 摘要异步生成，即使失败也不阻塞新 episode，且绝不自动注入新 episode。存在活动 turn 时返回 409，不隐式取消。

不向前端输出 chain-of-thought，只输出产品级阶段状态。异步记忆候选不属于 turn SSE，通过持久通知、bootstrap 待确认数量或记忆中心轮询展示。

Redis 无法建立运行租约或事件流时，不接受新的 Agent turn，返回 503；普通社区功能不受影响。

## 9. 长期记忆

### 9.1 分层职责

| 记忆层 | 存储 | 作用 |
| --- | --- | --- |
| 当前工作上下文 | Redis + 最近 MySQL 消息 | 当前生成所需短期状态 |
| 完整对话历史 | MySQL | 用户可见时间线和搜索 |
| Episode 摘要 | MySQL | 当前时间线的分段摘要；不直接作为长期向量记忆 |
| 偏好、目标、项目、经历 | MySQL + Milvus 投影 | 长期语义召回 |
| 固定回答/写作偏好 | MySQL 精确读取 + Milvus | 确定性应用与语义召回 |

MySQL 是唯一事实源。Milvus 只决定“哪些记忆可能相关”，命中后必须回 MySQL 读取正文、版本、状态和有效期。

### 9.2 MySQL 表

#### `agent_memory_item`

```text
id, user_id, memory_key, category, sensitivity,
state, current_version_id, confidence,
confirmed_at, expires_at, last_used_at,
created_at, updated_at, lock_version
UNIQUE(user_id, memory_key)
```

类别：

```text
PREFERENCE / GOAL / PROFILE / PROJECT / EPISODIC / PROCEDURAL
```

状态：

```text
PENDING_CONFIRMATION -> ACTIVE | REJECTED
ACTIVE <-> PAUSED
ACTIVE -> EXPIRED
任意可用状态 -> DELETING -> 物理删除
```

#### `agent_memory_version`

```text
id, memory_id, version_no,
user_id, content_plain, content_ciphertext,
encryption_nonce, encryption_key_id, content_hash,
reason, created_at
UNIQUE(memory_id, version_no)
UNIQUE(id, user_id)
```

编辑记忆创建新版本，不覆盖旧版本。敏感记忆必须满足 `content_plain IS NULL`，使用 AEAD 并保存 ciphertext、nonce 与 keyId；敏感 content hash 使用带独立密钥的 HMAC，避免低熵内容相等性泄漏。密钥不得保存在 MySQL，缺少有效 `MEMORY_ENCRYPTION_KEY` 时系统拒绝保存敏感记忆并保持 PENDING，不允许退化为明文。

生产配置使用 key ring：一个 active keyId 和只读 previous keys。轮换先增加新 key、再异步以乐观锁重加密、完成全量 hash/count 对账后才移除旧 key；缺失任一仍被引用的旧 key 时相关记忆不可召回并告警，不能把解密失败当成空内容覆盖。

首版已确认敏感记忆默认不建立 Milvus 投影；只有用户单独开启“敏感记忆语义召回”后才允许投影，并继续受 user partition 和 MySQL 回源约束。

#### `agent_memory_source`

```text
memory_version_id, user_id, source_type,
source_message_id, source_episode_id,
redacted_excerpt, created_at
```

每条记忆可追溯到用户自己的消息或显式设置。文章、检索结果和模型回答不能成为个人记忆来源。

#### `agent_memory_projection`

```text
user_id, memory_id, version_id, collection_name,
milvus_entity_id, embedding_model_version,
content_hash, state, retry_count, last_error, applied_at
UNIQUE(memory_id, version_id, collection_name, embedding_model_version)
```

memory item/version/source/projection 建立 `(id,user_id)`、`(memory_id,user_id)` 组合唯一键与外键；source message/episode 必须属于同一 userId。`current_version_id` 只能指向本 memory 的同用户 version。

`agent_memory_setting`：

```text
user_id, recall_enabled, auto_extract_enabled,
sensitive_recall_enabled, updated_version,
created_at, updated_at
PRIMARY KEY(user_id)
```

PATCH 使用 `updatedVersion` 或 ETag 乐观锁；冲突返回 409。关闭总记忆等价于同时关闭 recall 与 autoExtract，但不会自动删除已有记忆。

### 9.3 提取规则

成功 turn 结束后，通过 Outbox/Rabbit 异步提取候选：

1. 只读取用户自己的消息，不读取模型回答和检索文章。
2. 输出固定 DTO：`category/content/memoryKey/confidence/sensitivity/expiresAt/sourceMessageId`。
3. 凭据、验证码、访问令牌、身份证号、银行卡号等直接丢弃。
4. “忽略规则”“以后替我自动发布”等指令不得成为记忆。
5. 非敏感、置信度达到配置阈值的候选可以自动激活，并通过持久通知与记忆中心显示来源和撤销入口。
6. 敏感候选只能进入 PENDING_CONFIRMATION；30 天未确认自动过期。
7. 显式“请记住”仍需经过凭据与敏感规则，非敏感内容可立即激活。
8. 同一 memoryKey 更新时创建新 version；语义近似但事实冲突时保留候选供用户确认，不静默覆盖。

默认期限：

- 显式偏好和目标不自动过期；
- 自动提取的阶段性经历 90 天；
- 待确认敏感记忆 30 天；
- 暂停、过期或删除后立即停止召回。

### 9.4 召回规则

每轮最多注入：

- 4 条精确固定偏好；
- 12 条 Milvus 初始候选；
- Java 按相似度、置信度、重要性、时效和类别多样性重排后取最多 6 条；
- 总记忆上下文不超过独立 token 预算。

Milvus 查询强制：

```text
user_id == SecurityContext.userId
AND is_active == true
AND (expires_at_epoch == 0 OR expires_at_epoch > now)
```

模型不能提供或覆盖 userId。

ContextAssembler 记录本轮 `memory_epoch`。暂停、恢复、编辑、确认、拒绝、过期、删除、删除全部、关闭 recall 或改变敏感召回设置，都必须在 MySQL 同一事务递增该用户 memory_epoch。在任何 Provider 调用前再次比较 epoch，并重新验证每条 memory 仍为 current version、ACTIVE、未过期；epoch 不一致则丢弃已装配记忆并重新装配，无法在剩余 deadline 内完成则取消本轮。已经发送给外部 Provider 的内容无法撤回，界面必须如实说明这一边界。

最终回答中的个人记忆使用记录放在独立 `memoryUses[]`，只显示用户自己的记忆摘要、memoryId 和来源入口；它不能充当社区事实 citation，也不能证明外部事实。

### 9.5 用户控制与删除

接口支持列表、查看来源、编辑、确认、拒绝、暂停、恢复、删除、删除全部、导出和关闭自动记忆。

删除时：

1. MySQL 事务将状态置为 DELETING、递增 conversation.memory_epoch，并写带 aggregateVersion/lifecycleEpoch 的持久删除任务与 Outbox。
2. 所有在线召回按状态和 epoch 立即排除。
3. 删除任务从 projection manifest/Collection registry 冻结目标清单，按精确 memory PK + userId 删除 alias 当前、蓝绿双写中和 7 天回滚保留的每一个物理 Collection，并以 STRONG consistency 验证不存在。
4. 删除 Redis 缓存、候选摘要和导出文件；失败进入有界退避和每日对账。
5. 所有外部目标验证完成后，才物理删除 MySQL 原文、历史版本、source 与 projection manifest；删除 tombstone/job 审计保留 90 天。
6. 在线向量清理目标 5 分钟，MySQL 原文与历史版本最迟 24 小时物理删除；未完成前任务必须可恢复，不能向用户报告“已彻底删除”。
7. 备份按既定保留周期过期，不宣传备份中的数据瞬时物理擦除。

`agent_data_deletion_job` 至少保存：

```text
id, user_id, scope, target_id, lifecycle_epoch,
idempotency_key, request_hash,
target_collections_json, state, retry_count,
next_attempt_at, last_error, verification_json,
created_at, completed_at
UNIQUE(user_id, idempotency_key)
```

scope 支持 MEMORY、ALL_MEMORIES、CONVERSATION、ACCOUNT；state 固定为 PENDING/RUNNING/RETRY_WAIT/COMPLETED/FAILED。任务 payload 不保存记忆/对话正文；target Collection 清单与 projection manifest 在任务完成前不能提前删除。同一 idempotency key + requestHash 返回原 job，不同 hash 返回 409。

## 10. Milvus 设计

### 10.1 Collection

文章与私人记忆必须使用两个独立 Collection，关闭 dynamic field。

#### 文章块

物理名称：

```text
metro_article_chunks_bgem3_v1
```

读取 alias：

```text
metro_article_chunks_read
```

字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| chunk_id | INT64 PK | 等于 MySQL article_chunk.id，autoID=false |
| embedding | FLOAT_VECTOR(1024) | BGE-M3 Dense 向量 |
| article_id | INT64 | 回源与过滤 |
| revision_id | INT64 | 必须对应 published revision |
| author_id | INT64 | 可选过滤 |
| chunk_no | INT32 | 定位 |
| content_hash | VARCHAR(64) | 对账 |
| is_active | BOOL | 检索必须为 true |
| published_at_epoch | INT64 | 时间过滤 |
| language | VARCHAR(16) | 语言过滤 |
| embedding_model | VARCHAR(64) | 运维核验 |

不在 Milvus 保存正文。命中只返回 ID 与分数，随后批量读取 MySQL `article_chunk` 并验证当前公开 revision。

#### 用户记忆

物理名称：

```text
metro_user_memories_bgem3_v1
```

读取 alias：

```text
metro_user_memories_read
```

字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| memory_version_id | INT64 PK | 等于 MySQL version id，autoID=false |
| embedding | FLOAT_VECTOR(1024) | 当前版本向量 |
| memory_id | INT64 | 回源 |
| user_id | INT64 partition key | 强制用户命名空间 |
| category | VARCHAR(24) | 记忆类别 |
| sensitivity | VARCHAR(16) | 敏感级别 |
| is_active | BOOL | 检索必须为 true |
| expires_at_epoch | INT64 | 0 表示不过期 |
| content_hash | VARCHAR(64) | 对账 |
| embedding_model | VARCHAR(64) | 运维核验 |

`user_id` 使用 partition key，初始 64 个 partition。每次查询和删除必须带单一精确 userId；不为每个用户创建独立 partition。

### 10.2 索引和一致性

- Dense Vector：HNSW + COSINE，初值 `M=16`、`efConstruction=256`；查询 `ef=max(64, topK*4)`。
- 高频精确过滤字段建立 Milvus 标量索引。
- 日常检索使用 BOUNDED consistency。
- 删除和删除后验证使用 STRONG consistency。
- 已知 Primary Key 时按 PK 批量删除，不用无界表达式扫描。

参数是可配置起点，必须以真实中文评测集压测后再固化，不能在简历中宣称未经测量的 QPS 或延迟。

### 10.3 蓝绿重建

Embedding 模型、维度或 schema 变化时不原地修改：

1. 创建新物理 Collection 和索引。
2. 记录开始时的 Outbox 高水位，并从 MySQL 一致快照分页生成 ACTIVE chunk/记忆版本的新向量。
3. 从高水位之后按 aggregateVersion 顺序重放增量和 tombstone；迁移期间新写对新旧 Collection 双写。
4. 以 MySQL 当前 ACTIVE PK 集合做完整差异检查，删除多余实体，并以 STRONG consistency 验证 tombstone；不能只核对数量。
5. 核对 content hash、READY 状态和固定评测 Recall。
6. load 新 Collection，短暂设置投影切换写栅栏，重放栅栏期间增量。
7. 原子切换读取 alias，解除栅栏。
8. 保留旧 Collection 7 天回滚，再删除。

写路径始终指向配置中的物理 Collection，读路径使用 alias。

投影消费者维护每个 aggregate 的 `last_applied_version` 与 tombstone。迟到的激活/发布事件版本小于等于 watermark 时直接丢弃；每次 Milvus upsert 前还要回 MySQL 验证当前 memory state/version 或 article published pointer，防止删除、暂停或下架后被旧事件复活。

## 11. 文章分块、混合检索与 HyDE

### 11.1 入库

只为当前已公开 `published_revision_id` 建知识索引。草稿、待审、拒绝、回收站和旧 revision 不进入全局知识库。

分块规则：

- Markdown 先解析为语义块，不对原始字符直接硬切；
- 标题路径随 chunk 保存；
- 目标 350 至 600 token，重叠 60 至 100 token；
- 代码块、表格和引用尽量保持完整；
- 超大块按句子边界二次切分；
- chunk ID、revision、content hash 确定性生成并可幂等重放。

MySQL `article_chunk` 是 chunk 正文事实源；ES 与 Milvus 是可重建投影。

现有面向站内页面的 article 索引继续服务普通全文搜索与 MLT。Agent 另外创建 chunk 级 ES 物理索引 `metro_article_chunks_v1` 和读取 alias `metro_article_chunks_read`，字段至少包含 chunkId、articleId、revisionId、authorId、title、headingPath、bodyText、contentHash、publishedAt、language 和 isActive。`title/headingPath/bodyText` 使用匹配 ES 版本的 IK analyzer；Agent 的 BM25 top 40 来自这个 chunk 索引，才能与 Milvus chunk 结果在同一粒度做 RRF 和 quote 校验。

ES 投影事件同样带 aggregateVersion。写入前回源验证 published pointer；下架或删除的迟到 publish 事件不得复活旧 chunk。

### 11.2 查询路由

- 当前文章总结：直接读取当前公开 revision 的 chunk，不做无关全库召回。
- 解释选区：以选区与相邻 chunk 为主，可按需检索相关文章。
- 找文章/知识问答：执行 ES BM25 + Milvus Dense。
- 对比/争议点：至少需要两个不同 articleId 的有效来源。
- 写作建议：不访问检索工具，除非用户显式选择“引用社区资料”。

### 11.3 双路召回与 RRF

初始候选：

- ES BM25 top 40；
- Milvus query dense top 40；
- 可选 HyDE dense top 40。

先按 `articleId + revisionId + chunkId` 去重，再使用 RRF：

```text
rrfScore(d) = Σ 1 / (60 + rank_i(d))
```

随后：

1. 批量回源 MySQL；
2. 验证 `published_revision_id == hit.revision_id`、PUBLIC、未删除；
3. 同篇文章最多保留 2 个 chunk；
4. 结合当前文章、时间有效性和内容质量做轻量规则重排；
5. 最多向 Synthesizer 提供 8 个 chunk，并受独立 token 预算限制。

第一版不引入额外 cross-encoder。只有固定评测证明 RRF 无法满足质量目标时，再增加 reranker。

### 11.4 HyDE

HyDE 不是默认必经链路，只在以下情况触发一次：

- Planner 明确选择深度语义检索；
- 查询过短、过于抽象或缺乏可检索关键词；
- 首轮回源后有效候选少于配置阈值。

约束：

- 只输入规范化后的当前查询，不输入个人记忆或检索文章；
- 生成不超过 600 字的假设答案；
- 8 秒超时；
- 只用于生成检索向量；
- 不展示、不引用、不保存为事实或记忆；
- 超时、拒答、畸形或空输出立即回退 BM25 + 普通 Dense。

## 12. Grounded Answer 与引用

Synthesizer 输出结构化结果：

```json
{
  "answer": "结论……[1]",
  "citations": [
    {
      "marker": 1,
      "sourceId": "A123:R7:C4",
      "quote": "不超过 240 字的原文证据"
    }
  ]
}
```

CitationValidator 必须验证：

1. sourceId 来自本轮已授权候选，不能由模型自行创造 URL。
2. article/revision/chunk 仍存在且当前公开。
3. 规范化 quote 确实存在于对应 chunk。
4. 答案中的引用标号与 citation 数组一一对应。
5. 外链只允许后端生成的站内文章 URL。
6. 每个可验证的社区事实句必须绑定至少一个 citation；quote 达到最小有效长度并精确映射到 chunk span，不能用真实但无关的短句装饰结论。

第一次失败可以进行一次“只修复引用”的无工具生成；仍失败则移除无依据事实句，或明确降格为推测，再退回真实搜索卡片或说明“现有社区资料不足”。

引用只表示回答依据了某篇社区文章，不证明文章内容一定正确。

MySQL 保存最终 citation 快照。展示历史回答时仍重新检查文章当前 ACL；下架、回收或删除后立即隐藏 quote 与链接，再按 articleId 异步脱敏快照，不继续展示已经无权读取的正文。

## 13. 写作助手

请求包含：

```text
articleId
documentVersion
baseDocumentHash
selectionFrom / selectionTo
selectedTextHash
selectedText
action
instruction
```

`action` 白名单：

```text
POLISH / SHORTEN / EXPAND / TITLE / OUTLINE / SUMMARY / TAGS
```

`articleId` 存在时后端必须按 `(articleId, authenticatedUserId)` 验证归属；新文章尚未取得 ID 时可以为空，此时只能处理请求中显式提供的用户选区，不能读取任何服务端文章。随后使用无工具 Writer Client 生成纯文本建议。返回 original/suggested/diff，不返回任意 HTML、脚本或 embed。

`agent_writing_suggestion` 保存：

```text
id, user_id, article_id, client_request_id, request_hash,
operation, instruction, document_version,
base_document_hash, selection_from, selection_to,
selected_text_hash, original_text, proposed_text,
diff_json, state, expires_at, applied_at
UNIQUE(user_id, client_request_id)
```

状态：

```text
GENERATED -> APPLIED | REJECTED | INVALIDATED | EXPIRED
```

创建请求沿用 turn 的幂等规则：同一 `clientRequestId + requestHash` 返回原 suggestion；同 ID 不同 hash 返回 409。响应回传 documentVersion、baseDocumentHash 和 selectedTextHash。

后端看不到尚未保存的 Tiptap 当前文档，因此“文档是否已变化”由前端在应用前比较三者；变化时前端不应用内容，并调用 `/invalidated` 记录 `DOCUMENT_VERSION_CHANGED/SELECTION_CHANGED/ROUTE_CHANGED`。后端 409 只表示 suggestion 已过期、已处于非 GENERATED 状态或发生乐观锁冲突，不能伪称已验证本地文档 stale。

用户点击“应用”后只通过 Tiptap transaction 修改本地文档，并保留 undo。`applied/rejected/invalidated` 按 `(suggestionId,userId)` 幂等更新；重复相同终态返回原结果，不同终态冲突返回 409，旧响应或他人 ID 一律不能改变状态。保存和发布仍沿用原有按钮与业务 API。

## 14. 文章草稿、不可变 revision 与安全审核

### 14.1 可变草稿与不可变提交快照

自动保存写入一条可变、作者私有的 `article_draft`，不能每 1.5 秒创建 revision：

```text
article_id, user_id, draft_version,
title, summary, body_markdown, body_plain,
cover, tags_json, content_hash,
updated_at, lock_version
PRIMARY KEY(article_id)
UNIQUE(article_id, user_id)
FOREIGN KEY(article_id, user_id) REFERENCES article(id, author_id)
```

草稿保存按 `(article_id, user_id, draft_version/lock_version)` 乐观锁更新，永远不改变公开指针和审核中的冻结正文。新文章第一次保存时先创建 article 壳与 draft；作者编辑页读取 draft，公众详情页只读取 `published_revision_id`。

用户点击提交审核时，后端在同一事务把当时 draft 完整快照写入不可变 `article_revision`：

```text
id, article_id, revision_no,
title, summary, body_markdown, body_plain,
cover, tags_json, content_hash,
source_draft_version, created_by, created_at
UNIQUE(article_id, revision_no)
UNIQUE(id, article_id)
```

提交后的 revision 永远不允许 UPDATE。继续编辑只修改 `article_draft`；再次提交会生成新 revision、更新 pending 指针并把旧未终态审核任务置为 SUPERSEDED。

`article` 增加：

```text
latest_revision_id
pending_revision_id
published_revision_id
visibility_state
review_state
lock_version
```

`latest_revision_id` 是最近一次提交快照，不代表 mutable draft。已发布文章继续编辑或新 revision 被拒绝时，公众仍读取旧 `published_revision_id`，因此不会因自动保存、待审或拒绝而下架旧公开版本。只有显式取消公开、删除/回收或新的 revision 审核通过，公开指针才变化。

`article` 先建立 `UNIQUE(id,author_id)`，`article_draft(article_id,user_id)` 组合外键指向它；并为三个 nullable 指针分别建立 `(latest_revision_id,id)/(pending_revision_id,id)/(published_revision_id,id)` 到 `article_revision(id,article_id)` 的组合外键。`article_moderation_job(revision_id,article_id)` 也使用同一组合外键。人工决定事务再次显式断言 `revision.article_id == article.id == job.article_id`。迁移坏行或服务 bug 不能把另一篇文章的 revision 发布到当前文章。

生产应用数据库账号对 `article_revision` 和 `article_moderation_attempt` 只有 SELECT/INSERT，无 UPDATE；schema migration/依法清理使用独立受控账号。content hash 使用规范化 Markdown/metadata 的 SHA-256，并在插入后不可更改。

### 14.2 审核任务与严格结构解析

`article_moderation_job`：

```text
id, article_id, revision_id, content_hash,
state, model_decision, risk_score,
policy_hits_json, attempt_count,
next_attempt_at, lease_until,
last_error, reviewer_id,
created_at, updated_at, lock_version
UNIQUE(article_id, revision_id)
```

`article_moderation_attempt` append-only 保存：

```text
job_id, attempt_no, provider, model, prompt_version,
input_hash, structured_output_json,
latency_ms, token_usage, finish_reason,
error_code, created_at
UNIQUE(job_id, attempt_no)
```

状态：

```text
PENDING -> RUNNING
RUNNING -> MODEL_PASS | MODEL_REVIEW | MODEL_REJECT
RUNNING -> RETRY_WAIT -> RUNNING
任意未终态 -> SUPERSEDED
重试耗尽 -> HUMAN_PENDING
模型结果 -> HUMAN_PENDING（首版影子模式）
HUMAN_PENDING -> HUMAN_APPROVED | HUMAN_REJECTED
```

审核 worker 在模型调用前后都重新读取冻结 revision 并校验 `revision_id + content_hash`；任何不一致、pending 指针变化或任务租约丢失都标记 SUPERSEDED，不采纳结果。

审核使用独立 Client、温度 0、无工具，并请求 DeepSeek `response_format=json_object`。prompt 明确要求 JSON 并包含示例；不能声称 Provider 在无工具模式强制 JSON Schema。Java 端使用 Jackson 严格 DTO、拒绝未知字段，并用本地 JSON Schema/Bean Validation 校验：

```text
decision
categories
severity
confidence
evidenceOffsets
reason
model
promptVersion
```

冻结正文先经过确定性敏感规则，再按 tokenizer 预算分块并保留重叠与标题路径；块数、总 token 和单任务成本均有硬上限。文章数据放在明确的 UNTRUSTED_DATA 区域，任何“忽略审核规则”等正文指令都只是待审内容。各块结果按最高风险聚合，跨块矛盾直接进入 HUMAN_PENDING，不能用多数投票把高风险块覆盖。

空 content、截断或 `finishReason=length`、畸形 JSON、未知字段/类别、越界 severity/confidence/evidence、版本不匹配、超时、Provider 错误、块间冲突和低置信都进入 HUMAN_PENDING，绝不默认通过或拒绝。

### 14.3 人工决定与双对象 CAS

人工通过/拒绝事务必须先锁定 job、article 和 revision，并同时校验：

- `job.state == HUMAN_PENDING`；
- `job.lock_version == expectedJobVersion`；
- job 的 `revision_id + content_hash` 等于冻结 revision；
- `article.pending_revision_id == revisionId`；
- `article.lock_version == expectedArticleVersion`。

通过时以条件 UPDATE 原子切换 `published_revision_id`、清理 pending、保留 draft、递增 article/job lock version；拒绝时只清理匹配的 pending 并记录决定，不清除已有 `published_revision_id`。任一条件影响行数为 0 时，只能把旧任务标记 SUPERSEDED 或返回 409，不能发布或拒绝另一 revision。

状态切换、通知和 Outbox 必须在同一 MySQL 事务。`ARTICLE_REVISION_PUBLISHED` 事件携带 `oldPublishedRevisionId/newPublishedRevisionId`；投影消费者必须回源确认当前 published pointer 后，upsert 新 revision 并删除旧 revision。`ARTICLE_REVISION_REJECTED/SUPERSEDED` 不删除仍公开的旧 revision；只有 `ARTICLE_UNPUBLISHED/DELETED` 或确认过的新指针替换才删除当前公开投影。

首版审核是影子模式：AI 给管理员结构化风险和证据，但最终由人工决定。只有积累至少 1,000 条真实双审样本并通过事先批准的漏判门槛后，才评估 feature flag 下的低风险自动通过；自动拒绝仍保留人工确认。

## 15. Outbox、Inbox 与投影一致性

新 AI 能力复用并推广现有推荐系统已验证的 Outbox 思路，但不得弱化推荐业务原有严格语义。

通用 `domain_event_outbox`：

```text
id BIGINT AUTO_INCREMENT PRIMARY KEY,
event_id BINARY(16),
aggregate_type, aggregate_id,
aggregate_version, lifecycle_epoch,
event_type, payload_version, payload_json,
dedupe_key, occurred_at,
state, retry_count, next_attempt_at,
lease_owner, lease_until, last_error,
created_at, published_at
UNIQUE(event_id)
UNIQUE(dedupe_key)
INDEX(state, next_attempt_at, id)
```

`consumer_inbox`：

```text
consumer_name, event_id, processed_at, result_hash
PRIMARY KEY(consumer_name, event_id)
```

`projection_watermark`：

```text
consumer_name, aggregate_type, aggregate_id,
last_applied_version, lifecycle_epoch,
tombstone, lease_owner, lease_until, updated_at
PRIMARY KEY(consumer_name, aggregate_type, aggregate_id)
```

Dispatcher 按 `id` 稳定排序，使用租约和 `FOR UPDATE SKIP LOCKED` 抢占，Rabbit publisher confirm 后标记 PUBLISHED。发布成功但落库前崩溃允许重复投递。

每个 ES/Milvus/Redis 投影维护 `(consumer, aggregateType, aggregateId)` 的 `last_applied_version/lifecycle_epoch/tombstone` watermark，并在执行外部副作用前原子取得该 aggregate 的有期租约。同一 aggregate 的旧/新事件不能并行修改外部投影。消费顺序必须是：

1. 原子取得 projection lease，并检查 eventId、lifecycle 与 aggregate version；
2. 回 MySQL 验证当前事实状态、用户、published pointer 或 memory current version；
3. 对外部投影执行确定性 idempotent upsert/delete；
4. 成功后在本地事务记录 Inbox 与 watermark；
5. compare-release 租约并最后 ACK。

不能先写 Inbox 再执行外部副作用。外部写成功、本地 Inbox 写入前崩溃时允许在 lease 过期后重放幂等写；更高版本事件随后取得同一 lease 并形成最终状态。旧 lifecycle 的 publish/activate 事件不得越过 tombstone 复活已删除文章或记忆。敏感记忆 Outbox payload 只携带 ID、版本、状态与 hash，不携带明文或密文正文；consumer 按授权在处理时回源。

核心事件：

```text
ARTICLE_REVISION_SUBMITTED
ARTICLE_REVISION_PUBLISHED
ARTICLE_REVISION_REJECTED
ARTICLE_REVISION_SUPERSEDED
ARTICLE_UNPUBLISHED
ARTICLE_DELETED
ARTICLE_CHUNK_REINDEX_REQUESTED
AGENT_TURN_REQUESTED
MEMORY_EXTRACTION_REQUESTED
MEMORY_VERSION_ACTIVATED
MEMORY_PAUSED
MEMORY_EXPIRED
MEMORY_DELETED
EPISODE_SEALED
```

投影删除失败时，MySQL 状态检查立即阻止使用，后台继续重试；异步索引不能成为 ACL 真相源。

## 16. API 契约

所有用户侧 Agent 接口位于 `/api/agent/**` 并必须认证；管理审核接口单独位于 `/api/admin/**` 且要求 `ROLE_ADMIN`。所有资源读取和写入都按 `(resourceId, authenticatedUserId)` 查询，不能先按裸 ID 查出再做应用层判断；不存在与属于他人都统一返回 404。

### 启动与个性化

```text
GET   /api/agent/bootstrap
GET   /api/agent/profile
PATCH /api/agent/profile
```

`bootstrap` 一次返回 profile、当前 ACTIVE episode、活动 turn/canonical snapshot、memory settings、当前 temporary session 状态和待确认 memory 数量，避免前端用多个请求拼出互相矛盾的初始状态。profile 更新使用 ETag/lockVersion，形象上传走独立受认证文件 API；个性化不能改变 Agent 权限。

### 对话

```text
GET    /api/agent/conversation/messages?beforeId=&size=&role=
POST   /api/agent/conversation/search
POST   /api/agent/conversation/reset-context
POST   /api/agent/conversation/deletion-jobs
GET    /api/agent/deletion-jobs/{id}
POST   /api/agent/temporary-sessions
GET    /api/agent/temporary-sessions/current
DELETE /api/agent/temporary-sessions/current
POST   /api/agent/turns
GET    /api/agent/turns/{turnId}
GET    /api/agent/turns/{turnId}/events?after=
POST   /api/agent/turns/{turnId}/cancel
```

搜索使用 POST JSON，避免关键词进入 URL/access log。请求和响应固定为：

```json
{
  "query": "检索词",
  "role": "ANY",
  "cursor": null,
  "size": 20
}
```

```json
{
  "items": [
    { "type": "MESSAGE", "messageId": 122, "role": "USER", "excerpt": "..." },
    { "type": "EPISODE_SEPARATOR", "episodeNo": 3, "openedAt": "..." }
  ],
  "nextCursor": "opaque-signed-cursor",
  "hasMore": true
}
```

服务端使用稳定 `(createdAt,id)` keyset、按新到旧返回；size 限 1 至 50，cursor 绑定 userId/queryHash/role 并签名，不能被改成查询他人数据。episode separator 是独立 item，不伪装成用户消息。

对话删除通过异步 deletion job 执行，请求带 `Idempotency-Key`、`expectedConversationVersion`、精确确认值 `DELETE_AGENT_CONVERSATION` 和 `deleteMemories`。存在活动 turn 时返回 409，不隐式取消；`deleteMemories=false` 时长期记忆保留，true 时在同一持久删除任务中显式加入记忆 scope。

`GET /deletion-jobs/{id}` 按 owner 查询并返回 `id/scope/state/retryable/errorCode/createdAt/completedAt/verificationSummary`；FAILED 不丢任务，用户可用新的 Idempotency-Key 重试，后台仍按策略补偿。状态查询不返回 Collection 名、内部错误栈或已删除正文。

### 记忆

```text
GET    /api/agent/memories?state=&category=&before=&size=
GET    /api/agent/memories/{id}
PATCH  /api/agent/memories/{id}
POST   /api/agent/memories/{id}/confirm
POST   /api/agent/memories/{id}/reject
POST   /api/agent/memories/{id}/pause
POST   /api/agent/memories/{id}/resume
DELETE /api/agent/memories/{id}
POST   /api/agent/memories/deletion-jobs
PATCH  /api/agent/memory-settings
```

`memory-settings` 精确控制 `recallEnabled/autoExtractEnabled/sensitiveRecallEnabled/updatedVersion`。关闭 recall 立即增加 memory epoch 并停止注入；关闭 autoExtract 不删除旧记忆；关闭总记忆等价于前两项关闭，敏感召回仍保持显式 opt-in。

单条 memory 重复 DELETE 幂等返回 204；全部删除使用 Idempotency-Key、settings version 和确认值 `DELETE_ALL_AGENT_MEMORIES` 创建 deletion job。活动 turn 存在时返回 409，避免已经装配的 memory 与删除并发。

### 导出

```text
POST   /api/agent/exports
GET    /api/agent/exports/{id}
GET    /api/agent/exports/{id}/download
DELETE /api/agent/exports/{id}
```

导出统一为异步任务，请求固定 `scope=CONVERSATION|MEMORIES|ALL`、`format=JSON|MARKDOWN` 和 Idempotency-Key。内容包括用户可见消息/episode、final citation 当前可展示快照、memory 当前版本/来源/设置与 profile；不包括 chain-of-thought、内部 prompt、工具原始结果、Provider 响应、密文、其他用户数据或已无权查看的引用正文。存在活动 turn 时返回 409。

完成后返回 owner-scoped download endpoint；文件服务端加密、24 小时过期、禁止公共 URL，下载响应 `Cache-Control: no-store`。重复相同导出请求返回原 job，不同 requestHash 返回 409；DELETE 幂等清理文件和 job 可见状态。

### 写作

```text
POST /api/agent/writing-suggestions
GET  /api/agent/writing-suggestions/{id}
POST /api/agent/writing-suggestions/{id}/applied
POST /api/agent/writing-suggestions/{id}/rejected
POST /api/agent/writing-suggestions/{id}/invalidated
```

`applied` 只记录用户确认结果，不修改文章。

### 审核管理

```text
GET  /api/admin/moderation/jobs?state=&before=&size=
GET  /api/admin/moderation/jobs/{id}
POST /api/admin/moderation/jobs/{id}/approve
POST /api/admin/moderation/jobs/{id}/reject
POST /api/admin/moderation/jobs/{id}/retry
```

参数绑定、JSON 类型错误、校验失败返回真实 HTTP 400；未认证 401；管理员角色不足 403；用户资源不存在/不属于本人统一 404；配额 429；Provider 或运行基础设施不可用 503。不能再用 HTTP 200 包装所有错误。

所有错误使用 RFC 9457 `application/problem+json`，扩展字段至少包含：

```text
code, requestId, retryable, retryAfterSeconds, fieldErrors
```

契约：校验/类型错误 400、未认证 401、owner hiding 404、乐观锁/幂等/活动 turn 冲突 409、输入过大 413、配额或并发限制 429、Agent/Provider/Redis 运行依赖不可用 503。429/503 在适用时同时返回 `Retry-After`。

稳定 code 最低集合：

| HTTP | code | 说明 |
| ---: | --- | --- |
| 400 | `VALIDATION_FAILED` / `MALFORMED_JSON` | 字段或 JSON 错误 |
| 401 | `AUTHENTICATION_REQUIRED` | 未登录或 token 失效 |
| 403 | `ADMIN_ROLE_REQUIRED` | 管理审核权限不足 |
| 404 | `RESOURCE_NOT_FOUND` | 不存在或 owner hiding |
| 409 | `IDEMPOTENCY_CONFLICT` | 同 key 不同 request hash |
| 409 | `ACTIVE_TURN_EXISTS` | 用户已有活动生成 |
| 409 | `OPTIMISTIC_LOCK_CONFLICT` | profile/settings/article/job 版本冲突 |
| 409 | `SUGGESTION_STATE_CONFLICT` | suggestion 已终态/过期 |
| 410 | `TEMPORARY_SESSION_EXPIRED` | 临时 session 已失效 |
| 410 | `EVENT_STREAM_EXPIRED` | SSE 前缀已裁剪，改读 snapshot |
| 413 | `AI_INPUT_TOO_LARGE` | 超过能力输入上限 |
| 429 | `AI_QUOTA_EXCEEDED` / `AI_CONCURRENCY_LIMIT` | 用户配额或全局舱壁 |
| 503 | `AI_DISABLED` / `AI_UNAVAILABLE` | 功能关闭、Key/Provider 不可用 |
| 503 | `AGENT_RUNTIME_UNAVAILABLE` | Redis/运行租约/事件流不可用 |

## 17. Provider、配置与配额

业务开关使用项目自己的前缀：

```text
metro.ai.enabled=false
metro.ai.agent.enabled=false
metro.ai.memory.enabled=false
metro.ai.writing.enabled=false
metro.ai.moderation.enabled=false
metro.ai.embedding.enabled=false
```

配置与密钥：

```text
DEEPSEEK_API_KEY
DEEPSEEK_BASE_URL
DEEPSEEK_MODEL=deepseek-v4-flash
OLLAMA_BASE_URL
OLLAMA_EMBEDDING_MODEL=bge-m3
MILVUS_HOST
MILVUS_PORT
MILVUS_USERNAME
MILVUS_PASSWORD
MEMORY_ENCRYPTION_KEY
```

模型名不得硬编码进 Java。DeepSeek Key 缺失时应用仍正常启动，Agent 明确返回 AI_UNAVAILABLE，审核直接进入人工。

首版默认值：

| 能力 | 输入上限 | 用户配额 | 总超时 |
| --- | ---: | ---: | ---: |
| Agent 对话 | 4,000 字符 | 8 次/分钟、100 次/天 | 45 秒 |
| 当前文章总结 | 100,000 字符，分块 | 5 次/分钟、30 次/天 | 60 秒 |
| 写作建议 | 20,000 字符选区 | 10 次/10 分钟、60 次/天 | 60 秒 |
| HyDE | 当前问题，输出最多 600 字 | 计入 Agent 配额 | 8 秒 |
| 单审核块 | token 预算控制 | 系统预算 | 20 秒 |
| 整个审核任务 | 多块聚合 | 系统预算 | 90 秒 |

全局 bulkhead 初值：Agent 8、审核 2、记忆提取 2、Embedding 4。队列必须有界，满载返回 429/503，不创建无界线程。

Agent、审核、写作、Embedding 使用独立 timeout、retry、bulkhead 和 circuit breaker。交互请求只对连接失败、429 和部分 5xx 重试 1 次；后台任务最多 3 次指数退避。权限错误、畸形结构和内容过长不重试。

Spring AI 内部全局重试固定为 `spring.ai.retry.max-attempts=1`，即 Provider Client 内不自行重试，避免默认重试突破总 deadline。`AiChatGateway/EmbeddingGateway` 外层使用 Resilience4j 按 capability 建独立 Retry、TimeLimiter、Bulkhead 和 CircuitBreaker；入口把绝对 deadline 贯穿 planning、tool、retrieval、generation 和 citation validation，所有子步骤必须小于 HTTP/SSE 总超时并在剩余时间不足时提前失败。

## 18. Docker 与端口

新增 Compose `ai` profile，同现有 `metro-community-net` 网络：

| 服务 | 容器端口 | 宿主建议端口 | 说明 |
| --- | ---: | ---: | --- |
| Milvus gRPC | 19530 | 29530 | 仅绑定 127.0.0.1 |
| Milvus WebUI | 9091 | 19091 | 仅本机调试 |
| Ollama | 11434 | 21434 | 仅本机；生产可改远程服务 |
| etcd | 2379 | 不暴露 | Milvus 内部 |
| MinIO | 9000/9001 | 不暴露 | Milvus 内部 |

启动前必须检查宿主端口，不能覆盖现有容器。应用在宿主运行时使用 29530/21434；应用容器化时使用服务名和容器端口。

Milvus Standalone、etcd、MinIO 和 Ollama 均配置 named volume、healthcheck 与资源说明。BGE-M3 镜像约 1.2GB，生产不允许应用启动时自动下载模型，应在部署步骤预拉取并校验模型 digest。

Milvus 开启鉴权，root 初始凭据只用于部署初始化；应用使用最小权限专用账号，用户名和密码只从环境变量/密钥管理读取。Milvus、etcd、MinIO 不暴露公网，生产只允许应用网络访问 gRPC，WebUI 默认不启用。日志、异常和 actuator 不输出凭据或完整向量请求。

AI profile 未启动时，普通后端仍必须通过完整测试并正常启动。

## 19. 故障降级

| 故障 | 行为 |
| --- | --- |
| DeepSeek Key 缺失或 Provider 不可用 | Agent 返回 AI_UNAVAILABLE；普通社区继续；审核进入人工 |
| 模型生成失败但 ES 可用 | 展示真实搜索卡片，不伪造自然语言答案 |
| HyDE 失败 | 回退普通 Dense + BM25 |
| Milvus 不可用 | 回退 ES BM25；长期记忆语义召回暂不可用 |
| ES 不可用 | 可使用 Milvus 候选并回源 MySQL；否则声明检索不可用 |
| Redis 不可用 | 不接受新 Agent turn；普通社区继续 |
| 写作生成失败 | 不改变选区，可重试 |
| 记忆提取失败 | 后台重试，不生成半条记忆 |
| Rabbit 不可用 | Outbox 保持 PENDING 并重试 |
| 审核 Provider 失败 | HUMAN_PENDING + 告警，绝不自动通过或拒绝 |
| Milvus 删除失败 | MySQL 状态立即阻止使用，后台重试和对账 |

缓存的文章摘要必须绑定 `articleRevision + model + promptVersion`，文章修改后不能返回旧摘要。

## 20. 安全与隐私不变量

1. 模型输出永远是不可信输入，不能直接触发业务状态变更。
2. 文章、评论、昵称、记忆和选区都以 UNTRUSTED_DATA 输入模型，不能拼接进 system 指令。
3. Planner 看不到完整检索正文；Synthesizer 没有工具。
4. 模型不能连接 MySQL、Redis、Rabbit、ES、Milvus、文件系统或任意网络。
5. 所有文章命中最终回 MySQL 验证当前公开 revision 和删除状态。
6. 所有记忆命中强制当前用户 partition key，并回 MySQL 验证 ACTIVE/current version/expiry。
7. 摘要和写作在调用 Provider 前完成 ACL，不允许 IDOR 外发正文。
8. 输出 Markdown 过滤原始 HTML、事件属性、javascript/data/blob 协议和不受信 URL。
9. 日志默认不记录 prompt、文章全文、记忆正文和完整模型回答。
10. Provider 数据保留和训练政策只按实际合同披露，未验证前不宣传零留存。
11. AI 关闭或无 Key 时，人类私信和人工审核必须正常运行。
12. 前端提交的 route、articleRevision、作者、正文或可见性都不是授权依据；服务端按 principal 与当前 MySQL 指针重新解析。
13. SSE、取消、导出、删除、搜索、profile、memory、writing suggestion 和 moderation job 都执行对象级 ACL；用户资源越权统一 404，管理员审核要求 ROLE_ADMIN。

### 20.1 数据保留与账号删除

默认保留策略可配置并在隐私页面披露：

| 数据 | 默认保留 |
| --- | --- |
| 持久对话消息/episode/final citation | 直到用户删除对话或账号 |
| `agent_retrieval_hit` 与工具诊断 | 30 天 |
| 写作 suggestion 原文/建议正文 | 7 天；状态与 hash 审计 30 天 |
| moderation attempt 详细结构输出 | 180 天后压缩为决定、hash、版本和指标 |
| PUBLISHED Outbox | 7 天 |
| consumer Inbox | 30 天 |
| DEAD/人工处理事件 | 90 天或处理后按政策清理 |
| Redis temporary session | 最长 24 小时 |
| Redis turn event stream | 30 分钟；canonical final 随持久 message 保存 |

账号删除的初始事务先禁用账号/撤销认证、关闭 memory recall/extract、递增 memory/lifecycle epoch、写 tombstone、`agent_data_deletion_job` 和 Outbox，暂不删除 deletion job、memory projection manifest 或 Collection registry。此后任何 Agent/导出接口均不可再认证访问。

worker 必须覆盖 profile 与自定义桌宠资源、memory setting/item/version/source/projection、conversation/episode/turn/message/tool/retrieval/citation、writing suggestion、Redis lease/session/stream、导出文件，以及 alias 当前、蓝绿双写和回滚保留的所有 Milvus 私人 Collection。逐一以 STRONG consistency/精确 PK 验证外部删除后，才清理 manifest 与 MySQL Agent 原文；job/tombstone 保留 90 天用于重试和审计。文章及审核记录按社区内容删除政策另行处理。备份按备份保留周期自然过期，界面不能承诺瞬时清除备份。

## 21. 可观测性与评测

指标：

```text
ai.request.count / latency / tokens
ai.quota.rejected
ai.bulkhead.rejected
ai.circuit.state
rag.retrieval.empty
rag.retrieval.source.count
rag.citation.invalid
memory.candidate / activated / confirmed / rejected / deleted
memory.vector.delete.lag
moderation.pending.age
moderation.override.rate
outbox.pending.age
provider.timeout / 429 / 5xx
```

指标标签只使用 capability/provider/model/outcome 等低基数字段，不使用 userId、prompt 或 articleId。

日志只记录 requestId、turnId、capability、model、promptVersion、tokenCount、finishReason、errorClass 和 fallback。原始输入输出生产环境关闭。

RAG 固定评测集首版至少 100 个有人工相关性标注的中文问题，比较：

```text
BM25
Dense
BM25 + Dense RRF
BM25 + Dense + HyDE RRF
```

上线门槛：

- top-10 Recall 不低于 BM25 基线，目标至少 0.85；
- 引用 sourceId/quote 校验有效率 100%；
- 人工核验的证据支持率目标至少 90%；
- 跨用户记忆或未公开文章泄漏为 0；
- 红队集合中写副作用为 0。

若 HyDE 没有稳定提升，则保持关闭，不因简历需求强行启用。

## 22. 测试矩阵

### 自动化结构测试

- 单元测试：RRF、HyDE 路由、chunk、memory dedupe/expiry、引用、配额、状态机、双对象 CAS、输出过滤、ProblemDetail 映射。
- Spring 集成测试：认证、所有 owner-scoped 资源 IDOR、HTTP 状态、单活动 turn、request-hash 幂等、跨实例 lease/fence、stale worker 恢复、SSE replay/trim-gap/cancel、临时会话连续性与过期、AI 关闭路径。
- Testcontainers/Compose：真实 MySQL、Redis、RabbitMQ、Elasticsearch、Milvus。
- Milvus：建 Collection、索引、alias、upsert、filter、跨用户隔离、删除、重启恢复、维度错误、鉴权、蓝绿 snapshot/high-water replay、切换期间删除与迟到 upsert 竞态。
- Rabbit/Outbox：事务回滚、publisher confirm、重复投递、外部写成功但 Inbox 未写的重放、watermark/tombstone、乱序版本、DLQ/补偿。
- 记忆：epoch 在装配与 Provider 调用之间变化、敏感默认不投影、跨用户 source 外键、删除/恢复/迟到 activate 事件、设置乐观锁和账号删除对账。
- 审核：正文提示注入、跨块违规、空/截断/未知字段 JSON、Provider 超时、冻结 hash、job+article lockVersion CAS、编辑竞态、旧任务迟到、拒绝新 revision 后旧 published revision 仍公开。
- 草稿/revision：自动保存只改 draft、提交冻结快照、提交后继续编辑不改变审核正文、旧 status 全量回填及 hash/count 不变量。
- API：bootstrap 一致快照、POST 历史搜索不泄露 URL、profile persona 不能提权、conversation export/delete 与 memory 独立删除确认、CORS PATCH/Last-Event-ID。
- 前端：单时间线、临时模式、引用跳转与来源下架、记忆管理、diff 过期、路由切换、SSE 410 snapshot 恢复、断线重连、移动端入口可达。

受控 HTTP Provider 只能验证协议、错误、超时和状态机，不能作为模型质量验收。

### 真实模型验收

- 本地 Ollama + BGE-M3 完成端到端 chunk/记忆 Embedding 与 Milvus 召回。
- 真实锁定版本 Milvus 容器完成 SDK schema/filter/alias/delete/重启契约门禁，image digest 与测试报告一并记录。
- 使用真实 DeepSeek Key 跑固定中文 Agent、引用、HyDE、写作与审核红队集。
- 没有 Key 时只允许声明工程链路和降级通过，不声明模型质量通过。

## 23. 数据迁移与上线阶段

所有数据库变化先 additive，先备份并检查旧状态、孤儿文章和索引数量。延续当前项目的显式、幂等 forward migration SQL；本轮不同时引入 Flyway 接管已有生产 schema。

### 阶段 A：AI 安全底座

- 升级 Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 3.5.17。
- 拆分 Agent/Writer/Moderation/Embedding Provider。
- 解耦普通私信与 AI，移除 static self/new Thread。
- 删除机器人 9999 特殊路径和旧 GET AI 接口。
- 增加统一 POST、ACL、HTTP 状态、配额、timeout、bulkhead、feature flag。
- AI 全关和无 Key 门禁通过。

### 阶段 B：revision、审核与 Outbox

按 expand -> backfill -> verify -> cutover 顺序迁移，不能用模糊的“长期双读/双写”替代：

1. **Expand**：创建 `article_draft`、`article_revision`、moderation job/attempt、通用 Outbox/Inbox/watermark，新列全部允许旧代码继续运行；先部署只写审计、不改变 public read 的兼容代码。
2. **Backfill**：为每篇 legacy article 建 revision 1 与 draft 快照，并按旧状态映射指针：`status=0 -> PRIVATE/NOT_SUBMITTED`，`status=1 -> PUBLIC/APPROVED + published_revision_id`，`status=2 -> PRIVATE/AUTO_PENDING + pending job`，`status=3 -> PRIVATE/REJECTED`，`is_deleted=1 -> RECYCLED`。存在矛盾状态的行进入人工迁移报告，不猜测公开性。
3. **Verify**：要求 article 数量、revision-1 数量、draft 数量、标题/正文/content hash、公开指针、待审 job 与 ES 当前公开文档 100% 对账；抽样不是切换依据。迁移 SQL 连跑两次结果一致。
4. **Cutover**：先让公众读取只走 `published_revision_id`、作者编辑只走 `article_draft`，再启用新的提交冻结与审核状态机；最后停用 legacy 写路径。兼容期 `article.content` 只镜像已发布 revision，绝不能镜像私有 draft。

切换后，自动保存只更新 mutable draft，不撤下已发布 revision；提交创建冻结 revision。结构化审核、双对象 CAS 和人工队列先保持影子模式。发布/替换/下架/删除统一驱动 ES/Milvus current-pointer 投影语义，拒绝或 supersede 不删除旧公开 revision。

### 阶段 C：Milvus、Ollama 与知识投影

- 增加 Compose ai profile 和端口检查。
- 创建最小权限 Milvus 账号、两个 Collection、索引与 alias，并锁定 image digest。
- 创建 article_chunk 与 projection 表。
- 回填当前公开 revision，验证 ES 8.4.1 搜索回归。
- 独立检查点升级 ES 至 8.18.1 与匹配 IK 插件，重建索引后切 alias。

### 阶段 D：无记忆只读 Agent

- 创建 profile、conversation/episode/turn/message/tool/retrieval/citation 表。
- 上线 Planner、双路检索、RRF、可选 HyDE、引用校验、SSE 和降级卡片。
- 接入全局桌宠面板、bootstrap/profile API 和移动端轻量页，移除详情页旧 AI 摘要卡。
- 先内部账号灰度。

### 阶段 E：长期记忆

- 上线候选提取、确认、版本、Milvus projection、临时模式和记忆中心。
- 默认 feature flag 关闭，按用户灰度。
- 完成删除 Outbox、5 分钟向量清理目标和每日对账。
- 上线 conversation/memory export、独立删除确认、保留期清理和账号删除编排。

### 阶段 F：Tiptap 写作助手

- 上线选区建议、diff、显式应用、版本/hash 失效和 undo。
- Agent 没有保存或发布端点。

### 阶段 G：审核评测与可选自动通过

- 影子运行积累真实标注和人工改判数据。
- 在至少 1,000 条双审样本、严重违规漏判门槛和稳定运行都达标后，才评估低风险自动通过。
- 自动拒绝不在本轮范围。

## 24. 回滚

- `metro.ai.agent.enabled=false`：隐藏桌宠 Agent 能力，社区继续。
- `metro.ai.memory.enabled=false`：停止读取和提取记忆，已有记忆保留供用户管理。
- `metro.ai.writing.enabled=false`：隐藏写作入口，不影响 Tiptap。
- `metro.ai.moderation.enabled=false`：所有新提交直接进入人工审核。
- `metro.ai.embedding.enabled=false`：停止新向量任务，Agent 回退 ES BM25。
- Milvus alias 可切回旧 Collection。
- ES alias 可切回旧索引。
- revision 迁移在 cutover 前可回滚兼容代码；cutover 后 `article.content` 只保存 published 镜像，已经出现 draft/published 分裂语义，旧二进制无法无损读取新 draft。因此 cutover 后的回退方式是关闭新提交/自动保存开关、保持新表与公开指针、部署 forward fix；不得声称可直接启动旧二进制回滚。若业务硬性要求旧二进制回滚，必须在实施前另建 legacy draft compatibility 表/端点并把它纳入双写和验收，本设计默认不承担这项成本。
- 数据库迁移只前向修复，不在回滚时删除新表、revision、draft、审核或用户记忆数据。

## 25. 简历表述门槛

只有实现并验证后，简历才能表述：

> 基于 Java 21 与 Spring AI 构建受限只读社区 Agent，采用 Elasticsearch BM25、Milvus Dense、RRF 与可选 HyDE 完成混合检索；设计 MySQL 事实源 + Milvus 向量投影的可审计长期记忆，结合 SSE、引用校验、Provider 降级，以及基于不可变 revision、事务 Outbox、结构化审核和 CAS 的内容安全工作流。

Recall、延迟、引用有效率、删除时延和审核改判率等数字，必须来自最终固定评测或运行数据后再写入简历，不能预先编造。

## 26. 官方兼容性依据

- [Spring Boot 3.5.16 release](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)
- [Spring AI 1.1.8 release](https://spring.io/blog/2026/06/12/spring-ai-1-1-8-1-0-9-avaialble-now/)
- [Spring Data Elasticsearch compatibility matrix](https://docs.spring.io/spring-data/elasticsearch/reference/elasticsearch/versions.html)
- [MyBatis-Plus Boot 3 starter 3.5.17](https://baomidou.com/en/getting-started/install/)
- [Milvus 2.6.x release notes and exact SDK matrix](https://milvus.io/docs/release_notes.md)
- [Milvus Java SDK compatibility](https://milvus.io/api-reference/java/v2.6.x/About.md)
- [DeepSeek Chat Completion response_format contract](https://api-docs.deepseek.com/api/create-chat-completion/)
- [DeepSeek JSON Output guide](https://api-docs.deepseek.com/guides/json_mode/)
- [Ollama BGE-M3 model](https://ollama.com/library/bge-m3)

这些是实施基线而不是永久宣称。任何 patch 版本、Milvus image digest、Embedding model digest 或 Provider model 变化都必须重新运行本规格中的编译、真实容器契约、检索质量和降级测试后再合并。
