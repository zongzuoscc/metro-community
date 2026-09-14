# Community Agent Stage C Knowledge Projection Design

**Status:** 已批准总设计的 Stage C 细化；不改变 A–G 产品范围。

## 1. 目标与边界

Stage C 只交付 Ollama/Milvus 运行底座、当前公开文章的确定性分块事实、ES chunk BM25 投影、Milvus Dense 投影，以及可恢复的重放和蓝绿切换。它不交付公开 Agent API、RRF/HyDE 编排、对话、长期记忆事实表、写作建议或自动审核决定；这些仍属于 D–G。

永久约束：

- MySQL 的 `article`、`article_revision` 和 `article_chunk` 是公开性、revision identity 与 chunk 正文的事实源。
- Elasticsearch 与 Milvus 都是可删除、可重建的候选投影，永远不承担 ACL 或公开性判定。
- 只索引当前 `published_revision_id`。草稿、待审、拒绝、旧 revision、RECYCLED/PURGED 与显式下架内容不得进入可用候选。
- 所有 Stage C 能力默认关闭；不启动 Compose `ai` profile、未配置 Milvus/Ollama 或依赖故障时，普通社区、审核、私信、站内搜索和推荐仍能启动与运行。
- 默认测试套件不拉取 BGE-M3、不调用真实 Ollama、不依赖 Milvus；真实模型质量只在显式 opt-in 验收中声明。
- 本阶段不让 Spring AI `VectorStore` 创建或迁移生产 Collection。业务接口不暴露 Milvus SDK 类型。

## 2. 方案选择

采用“事实层先行 + 派生事件 + 项目自管 schema”方案：

1. current-pointer 文章事件先由 chunk fact consumer 收敛为 MySQL `article_chunk` 当前事实，并在同一事务追加 `ARTICLE_CHUNK_REINDEX_REQUESTED`。
2. ES 与 Milvus 各自消费派生事件，使用独立 Inbox/watermark/lease，回源同一 chunk set 后执行外部幂等副作用。
3. 搜索仓储只返回 candidate ID/score，应用层批量回 MySQL 验证当前公开 pointer 后才允许使用。

不采用两个替代方案：

- 不在文章审批/生命周期事务中直写 ES/Milvus；外部故障不能扩大 MySQL 写事务，也不能制造“数据库回滚但向量已写”的事实分裂。
- 不采用 Spring AI `VectorStore` 自动建表；它无法锁死本项目的字段、dynamic-field、partition key、alias、删除校验与蓝绿双写契约。
- 不把 Elasticsearch 升级与首个 Milvus schema 或 chunk index 合并；C1–C3 完成基础设施/事实层后，C4 先独立迁移 ES/IK/站内索引到受支持的 8.18.1 轴，C5 才创建 chunk index。

## 3. 运行架构与数据流

### 3.1 Chunk 事实流

`ArticleChunkFactConsumer` 订阅当前指针相关事件：

- `ARTICLE_REVISION_PUBLISHED`
- `ARTICLE_REVISION_REJECTED`
- `ARTICLE_REVISION_SUPERSEDED`
- `ARTICLE_UNPUBLISHED`
- `ARTICLE_DELETED`

处理顺序固定为：

1. 以 consumer `article-chunk-current-pointer` 取得既有 `projection_watermark` aggregate lease。
2. 直接通过 mapper 读取 article current pointer 与 immutable revision；不得调用受 rollout read mode 影响的页面 read service。
3. 当前为公开 revision 时，在事务外执行有界、确定性 Markdown 分块；非公开时形成空 active set。
4. 在短事务内按 `parser checkpoint FOR SHARE -> article -> published revision -> article_chunk_set -> article_chunk rows -> watermark` 顺序锁定并重新验证 `(parserGeneration, articleId, publishedRevisionId, contentHash, lifecycleEpoch, lockVersion)`。live event、reconcile 与 CHUNK_FACT rebuild 必须共享该锁序与同一 materialization service。
5. 幂等写入目标 chunk rows，停用该文章其余 active rows，计算 `chunk_set_hash`。
6. 同一事务追加唯一 `ARTICLE_CHUNK_REINDEX_REQUESTED` Outbox，写 chunk consumer Inbox/watermark 后提交。
7. 最后 ACK 原消息。事务或 lease 校验失败时不允许留下半个 active set 或派生事件。

拒绝或 supersede 事件不按事件名直接删除。consumer 永远回源 current pointer：若旧 published revision 仍公开，则保持其 chunk set；只有回源为非公开才形成空集。

如果新公开 revision 的 parser/事务失败，旧 active rows 可暂时保留以便重放，但 candidate resolver 会因 revision pointer 不匹配将其全部丢弃；此时知识检索只能暂无该文章，不能回退暴露旧 revision。

派生事件的 aggregate 是独立 `ARTICLE_CHUNK_SET`，不是 `ARTICLE`。payload 冻结 `articleId/revisionId/parserGeneration/parserVersion/chunkSetHash/activeChunkCount/chunkSetVersion/sourceLifecycleEpoch/sourceAggregateVersion`，dedupe 继续遵守现有 `aggregate + lifecycle + version + eventType`，其中 event aggregate version 是单调 `chunk_set_version`。`chunk_set_hash` 的 canonical input 固定为 parser generation/version、published revision identity 与按 chunk_no 排序的 `(chunkId,chunkHash,embeddingInputHash)`；空 active set 也覆盖 generation identity。同一 article lockVersion 不允许靠伪造第二个 article 事件触发 parser 升级；parser 或 estimator 变更必须提升 parser generation，通过持久化 CHUNK_FACT rebuild 逐 article推进 `chunk_set_version`、产生新 rows 和正常 derived event。只有它同时改变外部 schema/模型时才另建物理 target；单纯 parser 输出变化不伪造 target generation。

### 3.2 外部投影流

派生事件 fan-out 到：

- `article-chunk-elasticsearch`
- `article-chunk-milvus`

每个 consumer 都执行：aggregate lease/version gate → current chunk set 回源 → registry 解析写目标 → 外部 effect → manifest + Inbox/watermark complete → ACK。ES 与 Milvus 失败互不阻塞；一个投影可降级而另一个继续工作。

一个逻辑 consumer 在蓝绿期间对每个物理 target 单独记录 manifest effect。ACTIVE target 失败会阻止 Inbox/watermark complete 和 ACK。BUILDING/DRAINING target 失败不拖垮已服务的 ACTIVE target，但必须持久化 `repair_required`、pin 住对应 Outbox，且 BUILDING 不得进入 VERIFYING/ACTIVE、DRAINING 不得被声称为可回滚。只有一次性 operator CAS 显式撤销该次 rebuild/rollback 资格后，才能解除其 retention pin。已成功 target 只承受幂等重写。

外部 target rebuild 不是绕过 consumer fencing 的第二条写路径。snapshot item 只冻结“需要检查的 entity”，真正写入前必须回读 current truth：watermark 落后时，由 one-shot coordinator 调用与 live 相同的 bounded reconcile/普通 acquire（listener 尚 DISABLED 也可用 deterministic synthetic event 推进）；watermark 恰等于 current tuple而 raw BUILDING target仍缺失时，使用同一 watermark row 的 `acquireRepair`。任何路径都不以 snapshot 旧 identity 直写。每次 Provider/upsert/delete 前后同时 assert aggregate owner、rebuild job owner/token、item owner、target generation、switch fence 与 current truth，所有 cursor/item/manifest/proof 更新都用 affected-row=1 的 exact CAS。

Embedding 只发生在 Milvus consumer 中，并且必须通过既有 `AiCapabilityExecutor` 的 EMBEDDING policy 调用；worker 不得直接调用 gateway 或再套第二层 retry。每次真实 Provider 调用前后都重验 current pointer、chunk hash、模型 identity、deadline 与 aggregate lease。返回向量必须恰为 1024 个有限浮点数；NaN、Infinity、错维、空结果和 input/output 数量不一致都 fail closed，不能写零向量。

### 3.3 查询边界

Stage C 暴露两个内部 candidate repository：

- `ArticleChunkBm25Repository`：读取 ES alias，top K 上限 40。
- `ArticleVectorRepository`：读取 Milvus alias，BOUNDED consistency，top K 上限 40。

两者都不返回正文或“已授权结果”。`PublishedArticleChunkResolver` 批量读取 MySQL，并要求 `article.PUBLIC && !is_deleted && article.published_revision_id == chunk.revision_id && chunk.is_active && chunk.parser_generation == article_chunk_set.parser_generation && chunk.revision_id == article_chunk_set.published_revision_id`；不通过的候选被丢弃并计入低基数 drift metric。后续 D 的 RRF 只能使用该 resolver 的结果。

## 4. MySQL 数据契约

Stage C 继续使用显式、幂等、可中断恢复的 forward SQL，不引入 Flyway。fresh `script.sql` 与 upgrade migration 的最终 metadata fingerprint 必须相同。提交归属固定：C2 先创建 consumer/target/manifest/rebuild/fence 通用控制面表与初始 data manifest，并扩展 projection lease 的 DB-time `renew/assertOwned/acquireRepair/completeRepair` 与 bounded repair orchestration，Milvus schema manager 才能登记物理 Collection；C3 再创建 parser generation/checkpoint、`article_chunk_set`、`article_chunk`、Rabbit topology 与 chunk materializer。C2 不得先创建“registry 不知道”的 Collection，C3 也不得重复发明控制面表；C7 只补各 target 的完整 anti-entropy/delete/race 矩阵，不得把 C4 已依赖的 repair 原语拖到 C7。

### 4.1 `article_chunk`

核心字段：

```text
id BIGINT assigned positive PK
article_id BIGINT NOT NULL
revision_id BIGINT NOT NULL
chunk_no INT NOT NULL
parser_generation BIGINT NOT NULL
parser_version VARCHAR(32) NOT NULL
title VARCHAR(100) NOT NULL
heading_path_json VARCHAR(2000) NOT NULL
body_text MEDIUMTEXT NOT NULL
start_codepoint INT NOT NULL
end_codepoint INT NOT NULL
estimated_tokens INT NOT NULL
revision_content_hash CHAR(64) ASCII BINARY NOT NULL
chunk_hash CHAR(64) ASCII BINARY NOT NULL
embedding_input_hash CHAR(64) ASCII BINARY NOT NULL
language VARCHAR(16) NOT NULL
is_active BOOLEAN NOT NULL
published_at DATETIME(6) NOT NULL
created_at DATETIME(6) NOT NULL
updated_at DATETIME(6) NOT NULL
```

约束：

- `UNIQUE(revision_id, parser_generation, chunk_no)`；同一 immutable revision + parser generation 的重放必须得到完全相同的 row。
- `(revision_id, article_id)` 外键引用 immutable revision identity；`article_id` 仍单独引用 article。
- `parser_generation` 外键引用已登记的 `article_chunk_parser_generation.generation`；row 的 `parser_version` 必须与该 generation 的 immutable identity 相同。
- `id` 由 `SHA-256(UTF8("article-chunk-id-v1\n" + revisionId + "\n" + parserGeneration + "\n" + parserVersion + "\n" + chunkNo + "\n" + chunkHash))` 的低 63 位确定性生成，零值重映射为 1。若同一个 id 已绑定不同完整 identity，立即报 collision，禁止覆盖。
- 旧 parser generation 或旧 published revision rows 可保留审计，但 `is_active=false`；任何文章最多只有一个 active generation + revision。
- MySQL 是 chunk body 的唯一事实源；ES 可保存从 MySQL 重建的 `bodyText` BM25 投影。Outbox、Milvus、日志与 metrics 不携带正文。
- ID 输入用 UTF-8、十进制整数和单个 LF 分隔符编码；“低 63 位”精确定义为 digest 最后 8 bytes 按 big-endian 读取后清除符号位。golden fixture 锁定跨 JVM/架构结果。
- Stage C 不物理删除 `article_chunk` 历史 rows。未来若增加 retention，必须同时证明所有 required target 的 tombstone、Outbox 保留期、rebuild pin 和 rollback window 都已越过该 chunk identity；否则旧 worker 可能在无法枚举旧 ID 时复活文档。
- duplicate materialization 对完全相同 identity 不执行 UPDATE，所以 `created_at/updated_at` 也保持不变；只有 active state 真实切换才用 DB time 更新 `updated_at`。

### 4.2 `article_chunk_set`

`article_chunk_set` 是每篇文章的 chunk 集合控制事实，也是派生事件的独立版本轴：

```text
article_id BIGINT PK/FK
published_revision_id BIGINT NULL
parser_generation BIGINT NOT NULL
parser_version VARCHAR(32) NOT NULL
chunk_set_version BIGINT NOT NULL
source_lifecycle_epoch BIGINT NOT NULL
source_aggregate_version BIGINT NOT NULL
chunk_set_hash CHAR(64) ASCII BINARY NOT NULL
active_chunk_count INT NOT NULL
published_at DATETIME(6) NULL
lock_version BIGINT NOT NULL
updated_at DATETIME(6) NOT NULL
```

source article 的新 lifecycle/version、published pointer 变化或 parser generation 升级都会在 article 锁下将 `chunk_set_version + 1`；同一 source identity + parser generation 重放则完全 no-op。下游 watermark/ES script/Milvus manifest 比较 `(source_lifecycle_epoch, chunk_set_version)`，`source_aggregate_version` 仅用于证明回源文章版本。测试必须证明 article lockVersion 不变时 parser generation 1 → 2 仍产生新 derived event，而重复 generation 2 不产生事件。

`published_at` 不使用重放时的 `NOW()`：有 HUMAN_APPROVED job 时取其 immutable `reviewed_at`，legacy published revision 无 job 时取 article `create_time`，同 revision 的 restore/re-publish 保留已冻结值，替换 revision 才切换为新审核时间。Stage C v1 的 `language` 固定为 `zh-CN`，不启用无版本启发式检测；未来改变需提升 parser version。

### 4.3 Parser generation admission

parser 不是只靠字符串约定的进程内配置。`article_chunk_parser_generation` 为每一代冻结 `generation`、`parser_version`、`token_estimator_version`、dependency fingerprint、required build digest、state (`BUILDING|ACTIVE|DRAINING|RETIRED|FAILED`)、rollback deadline、operator/time 与 lock version；这些 identity 字段激活后不可改写。`article_chunk_parser_checkpoint` 是唯一 singleton current pointer，保存 `active_generation` 与 lock version，并外键引用 generation row。普通 runtime 对两表只读，one-shot operator 才能 exact CAS。

启用 chunk-fact listener 的进程必须让本地 generation、parser/estimator version、dependency fingerprint 与 build digest 全部 exact 匹配 checkpoint 当前代，否则 listener 不启动且直调 fail closed。每次 materialization 事务按 `parser checkpoint FOR SHARE -> article FOR UPDATE` 的固定锁序读取当前代；operator 切代用 checkpoint `FOR UPDATE`，所以旧 worker 要么在切代前完整提交，要么在切代后因 identity 不匹配零写入，不可能以更高 article/version 将 v2 active set 反向覆盖成 v1。

切代顺序为：创建 BUILDING generation 与 `CHUNK_FACT` rebuild job → 停止/排空旧 generation listener → 部署并验证新 build → exact CAS checkpoint 与 generation states → 由新 generation 重建全量 chunk facts。回滚只允许旧 generation 仍为 DRAINING、rollback deadline 未过且旧 build 重新通过 admission 时，以同一 fence/CAS 成对交换 ACTIVE/DRAINING；过期后只能创建更高 generation forward-fix。`article_chunk`、`article_chunk_set`、派生事件、ES/Milvus identity 与 rebuild item 都携带 `parser_generation`；CHUNK_FACT job 必须绑定明确 target generation，不能由执行进程自行选择 parser。

### 4.4 Projection consumer registry、target registry、manifest 与 rebuild

`projection_consumer_registry` 冻结 `consumer_name/aggregate_type/state(ACTIVE|DRAINING|DISABLED)/proof_mode(WATERMARK|TARGET_MANIFEST)/required_for_retention/lock_version`，`projection_consumer_event_type` 以 `(consumer_name,event_type)` 为主键表达真实订阅。原 delete/unpublish 事件映射 site-search + chunk-fact，`ARTICLE_CHUNK_REINDEX_REQUESTED` 映射 chunk-ES + chunk-Milvus。queue/binding 无论 listener/client capability 是否开启都是 durable，所以关闭投影时 mandatory Outbox publish 仍可路由并积压，不得因“没有 listener”返回 unroutable。

初始 data manifest 固定为：

| consumer | aggregate | initial state | proof mode | required for retention | event types |
|---|---|---|---|---|---|
| `article-search-current-pointer` | `ARTICLE` | `ACTIVE` | `WATERMARK` | true | `ARTICLE_REVISION_PUBLISHED`, `ARTICLE_REVISION_REJECTED`, `ARTICLE_REVISION_SUPERSEDED`, `ARTICLE_UNPUBLISHED`, `ARTICLE_DELETED` |
| `article-chunk-current-pointer` | `ARTICLE` | `DISABLED` | `WATERMARK` | false | `ARTICLE_REVISION_PUBLISHED`, `ARTICLE_REVISION_REJECTED`, `ARTICLE_REVISION_SUPERSEDED`, `ARTICLE_UNPUBLISHED`, `ARTICLE_DELETED` |
| `article-chunk-elasticsearch` | `ARTICLE_CHUNK_SET` | `DISABLED` | `TARGET_MANIFEST` | false | `ARTICLE_CHUNK_REINDEX_REQUESTED` |
| `article-chunk-milvus` | `ARTICLE_CHUNK_SET` | `DISABLED` | `TARGET_MANIFEST` | false | `ARTICLE_CHUNK_REINDEX_REQUESTED` |

Stage C Rabbit topology在既有 `community.domain.events` 上无条件声明 durable `article.chunk.fact.queue`、`article.chunk.elasticsearch.queue`、`article.chunk.milvus.queue` 及各自 `<queue>.dlq`，统一使用 `community.dlx`，并按上表 routing key 精确绑定；capability flag 只控制 named listener，不删除 queue/binding。生产发布继续 mandatory + correlated confirm，因此 disabled listener 只会积压，不会让 Outbox误判 unroutable。

这两张消费者表的初始 exact rows 由 Stage C migration 幂等写入并做 schema/data manifest 验证；普通 runtime 只读，独立 operator 才能用 exact CAS 切换状态。DB CHECK 固定 `(state IN ('ACTIVE','DRAINING') AND required_for_retention=1) OR (state='DISABLED' AND required_for_retention=0)`，不存在 ACTIVE+not-required 逃逸。consumer 状态图只允许：

```text
DISABLED -> ACTIVE -> DRAINING -> DISABLED
                      \-> ACTIVE  (rollback)
```

DISABLED→ACTIVE 只有在 parser/target admission、snapshot rebuild、queue catch-up 与 current-state exact diff 全部通过后，才能在同一 operator 事务中完成。ACTIVE 不得直达 DISABLED。退役先 ACTIVE→DRAINING 并冻结 retirement high-water；operator 暂停 Outbox dispatcher，等待 queue/unacked/DLQ=0、watermark/proof追平该 high-water、无 `repair_required`、无未撤销 BUILDING/VERIFYING/DRAINING rollback资格、无 rebuild/retention pin，再在同一短事务 exact CAS DRAINING→DISABLED、required=false 并永久撤销旧 rollback，随后恢复 dispatcher。若验证失败保持 DRAINING+required；DRAINING→ACTIVE rollback 也必须在旧资格/target/proof仍完整时 exact CAS。future re-enable 不信任 disabled 期间积压消息，重新做 snapshot+queue catch-up+exact diff。retention selector/delete 与 activation/retirement 按 consumer registry row→Outbox row 的同一锁序串行。对于已知 DomainEventType，缺 consumer row、缺 subscription 或出现未知 ACTIVE consumer 时 destructive retention 一律 fail closed，不得把“注册表为空”解释为“无人需要”。

`projection_target_registry` 对每个经 Stage C projection admission 管理的物理 ES index/Milvus Collection 一行，至少冻结：kind、consumer name、physical name、read alias、schema fingerprint、model name/digest、dimension、generation、role、state、required-for-retention、rebuild-job reference、rollback deadline、lock version 与 operator identity。它不伪造接管仍写 concrete `article`、尚无 manifest 的 Stage B site-search index；该 consumer 在 C4 正式迁移前后都可继续以 WATERMARK proof 工作。registry 不另存可变 snapshot/replay cursor，避免与 rebuild job 产生两个权威。`consumer_name` 只允许 SCHEMA_ONLY target 为 null；其余状态必须引用 exact consumer registry row。consumer 的 `proof_mode` 只决定 destructive retention 采用 watermark 还是 target manifest，不禁止 WATERMARK consumer 建立 BUILDING/VERIFYING/ACTIVE target。状态只允许：

```text
SCHEMA_ONLY -> BUILDING
SCHEMA_ONLY -> RETIRED
BUILDING -> VERIFYING -> ACTIVE -> DRAINING -> RETIRED
BUILDING -> FAILED
VERIFYING -> FAILED
```

同一个 kind 只能有一个 ACTIVE read target；BUILDING 可与 ACTIVE 双写。7 天回滚窗口内还允许一个受约束的成对转换：旧 `DRAINING -> ACTIVE` 与当前 `ACTIVE -> DRAINING` 必须在 projection switch fence、alias readback、schema/model identity 与 rollback deadline 验证后由同一次 operator 事务/CAS 完成；不允许单独把 DRAINING 提升为 ACTIVE。普通 runtime principal 不能任意提升 registry state，切换只由 one-shot projection operator 的 exact CAS 完成。

`projection_entity_manifest` 以 `(target_id, entity_kind, entity_id)` 为主键，记录 desired lifecycle/version/hash、last applied identity、tombstone/effect state、repair requirement/next attempt 与 lock version。它只是幂等 effect/repair 证据：外部成功而本地 manifest 提交前崩溃时允许暂时缺行。需要写入或删除的物理 target 集合始终从 `projection_target_registry` 枚举，实体 PK 则从 MySQL chunk facts 枚举；manifest 绝不是 target-set authority。

`projection_rebuild_job` 是 snapshot high-water、`last_replayed_outbox_id`、source cursor、租约、验证摘要和 alias switch proof 的唯一权威；`job_kind` 固定为 `CHUNK_FACT|ES_TARGET|MILVUS_TARGET`。CHUNK_FACT job 的 physical target 为 null，但必须绑定 target parser generation，并以该代分页更新 `article_chunk_set`、产生正常 derived events；外部 target job 必须绑定 registry target。`projection_rebuild_item` 以 `(job_id,entity_kind,entity_id)` 冻结 snapshot source identity/hash、item owner、effect state 与 hard deadline。重建不能只在进程内保存游标；崩溃重启必须从已提交 item 继续。expired job lease 不能自动授权第二个 operator 越过仍可能在途的外调：job 先进入 RECOVERY_REQUIRED，只有证明旧 one-shot process 已终止并等待 SDK/server hard RPC deadline + safety margin 后，operator 才能 exact CAS takeover；无法证明就不切 alias。

`projection_switch_fence` 每 kind 一行，至少包含 `kind/state(OPEN|FENCING|FENCED)/generation/owner/lease_until/fence_high_water_id/lock_version/updated_at`。one-shot operator 使用 DB-time lease + exact CAS 设置 fence；每个 consumer 在解析 target 前和每次外部 effect 紧前都 `assertOpen(expectedGeneration)`。业务 MySQL 事务仍可写 Outbox，但无新外部 effect 可越过持久化 fence。

既有 `consumer_inbox` 和 `projection_watermark` 继续作为 per-consumer event exactly-once 与 aggregate ordering；不复制第二套水位算法。

## 5. 确定性 Markdown 分块

实现使用 `org.commonmark:commonmark:0.29.0` 与同版本 `commonmark-ext-gfm-tables` AST，token estimate 使用当前 Spring AI 1.1.8 解析的 `com.knuddels:jtokkit:1.1.0` CL100K。这三个 Maven artifact 的 coordinate 与 SHA-256 也进入 Stage C dependency lock。parser 不渲染或执行 HTML。输入先将换行规范为 LF，再维护 heading path，将 paragraph、list、quote、code fence 与 table 作为语义块。任何 CommonMark/GFM/JTokkit 依赖升级都必须重跑 golden corpus；输出或 token estimate 改变时必须提升 parser/estimator version，不允许原 version 静默漂移。

冻结参数：

```text
parser_version=commonmark-bgem3-v1
parser_generation=1
target_tokens=512
minimum_tokens=350
maximum_tokens=600
overlap_tokens=80
token_estimator=cl100k-estimate-v1
```

规则：

- token 是确定性 sizing estimate，不宣称等同 BGE tokenizer；600 远低于 BGE-M3 8K context，真实 Ollama contract 仍验证不截断和 1024 维。
- estimate 覆盖 exact embedding input：title、heading path 与 chunk body，不能只计算正文。
- 优先组合完整语义块到 350–600；末尾不足 350 时尽量与前块合并，全文本身不足 350 时允许单个短块。
- 单个语义块超过 600 时依次按句子、换行、Unicode code-point 安全边界切分；任何循环必须严格推进，不能拆 UTF-16 surrogate pair。
- code/table/quote 在不超过 600 时保持完整；超限时才按行安全切分。
- overlap 取上一块尾部 80 estimated tokens，并在新块预算内；不能导致重复空块或超过 600。
- 输入字符数、块数、单块正文与规划耗时都有配置硬上限。超限在写 MySQL/调用 Provider 前失败，旧 active set 不变。
- `chunk_hash` 覆盖 parser version、revision hash、chunk no、heading path、code-point span 与 body；`embedding_input_hash` 覆盖实际发给 Ollama 的 UTF-8 字节。
- `heading_path_json` 是无多余空白的 canonical JSON string array，顺序从一级到当前标题；保留 Unicode code point，不做 NFC/NFKC 静默改写。`start_codepoint/end_codepoint` 是 LF-normalized `body_markdown` 上的半开区间 `[start,end)`，引用和 overlap 也必须可回溯到该区间。
- `body_text` 由 AST 的 text/code 内容确定性导出；soft/line break 转 LF，block 之间单个空行，不包含 HTML 渲染结果。golden corpus 同时锁定 canonical heading JSON、span、body bytes、token estimate、chunk hash 和 ID。

## 6. Milvus 契约

### 6.1 部署与权限

Compose 保留基础服务的 project network，另建 `metro-ai-app-net` 与 `metro-milvus-internal-net` (`internal: true`)。Milvus 同时连接两者，Ollama 只连 app network，etcd/MinIO 只连 internal network，不得因“无宿主端口”就把它们暴露给全部基础容器。`ai` profile 缺省时不创建任何 AI container/volume/network。该 profile 只启动 etcd、MinIO、Milvus standalone 与 Ollama。宿主端口只绑定 `127.0.0.1`；etcd/MinIO 不暴露宿主端口，Milvus 9091 management/health 只在 `ai-debug` profile 映射为 19091（它不被宣称为业务 Web UI）；所有服务有 named volume、healthcheck 和资源说明。

启动脚本在任何 pull/create/volume 副作用前取得 project-scoped deployment mutex，用稳定 Compose project name，并禁止固定 `container_name`。它从 `docker compose --profile ai config` 的最终渲染结果读取实际端口/绑定 IP，检查范围、重复映射、现有 Docker published ports 并尝试 TCP bind；任一冲突都零容器副作用退出。通过后才解析/拉取 lock 中的 exact digest，启动使用 `--pull never`；失败回滚只能操作本次带该 project label 的新资源。现有基础服务端口也收紧为 loopback 并补齐 health dependency，不保留无审计的 `0.0.0.0` 默认绑定。

`milvusdb/milvus:v2.6.20` 必须解析成 registry 返回的 immutable digest 后才可写 lock file；网络或 registry 失败时 Task C1 失败，禁止占位 digest。lock 同时记录每个 Compose image 的 tag、OCI manifest-list digest、linux/amd64 与 linux/arm64 child digest，以及官方 Milvus Compose 模板 URL/hash、Java SDK 2.6.22。Ollama 另冻结 image tag/version + manifest/child digest，BGE-M3 冻结 upstream reference、model manifest digest 与所有 blob identity。只有显式 model-provision 操作可拉取 upstream 漂移 tag，校验 digest 后必须复制成本地 immutable alias `metro-bge-m3:<manifest-prefix>`；应用永不使用 `bge-m3:latest`。CI 在实际目标架构重新解析并比对 child digest。生产禁止应用启动时自动 pull model；worker 装配前用 `/api/tags` 比对 alias + model digest，再执行一次 1024 维有限浮点 admission。

Milvus 开启 authorization。初始 root 凭据只用于 one-shot bootstrap，立即轮换并创建三个互异 user：`metro_schema_operator`、`metro_article_projection_writer`、`metro_article_candidate_reader`；分别只绑定同样互异的 role `schema_operator`、`article_projection_writer`、`article_candidate_reader`。user/role 名都必须满足 Milvus 2.6 的字母开头且仅 `[A-Za-z0-9_]` 契约，连字符名在任何外部副作用前拒绝。权限 manifest 以真实 server 返回的 privilege name 做 exact allowlist；schema operator 负责 Collection/index/alias/RBAC/load，article writer 只对 registry 已授权的 article physical target 取得 Collection 级 upsert/delete/query 权限，article reader 只取得 article physical Collection 的 Search/Query 权限。`query-by-PK` 与有界 delete 是 repository 强制的表达式契约，不伪称为 Milvus RBAC 的行级能力。

Milvus grant 始终绑定物理 Collection，alias 只用于业务请求路由；不能向 alias 授权后假设权限随切换迁移。新 article BUILDING Collection 必须在写入前授予 article writer、在验证/切换前授予 article reader，并以三身份分别做正/反权限验证；ACTIVE/DRAINING article Collection 在整个回滚窗保留相应 grant，RETIRE/drop 后才撤销。alias 切换后的 readback 同时验证 alias target 与 reader 对新物理 Collection 的真实 Search/Query。两个 article runtime principal 对 memory physical Collection 的 Search/Query/Insert/Upsert/Delete 必须全部得到 `PermissionDenied`。

Stage C 的 memory Collection/alias 仅由 one-shot schema operator 创建和验证，并在 target registry 以 `SCHEMA_ONLY`、`consumer_name=NULL`、`required_for_retention=false` 登记；它没有 queue/subscription、runtime user/role 或应用 Bean，不能被普通 target resolver 返回。Stage E 必须在受审计的一次性 admission 中先创建互异的 memory writer/reader users+roles及 exact consumer/subscription，再把 target 绑定 consumer 并从 SCHEMA_ONLY CAS 到 BUILDING；tenant filter 仍是 memory repository + MySQL authority 的强制契约，不能冒充行级 RBAC。默认 root、schema operator 或任意权限超集凭据出现在 runtime 时 fail closed。凭据只来自环境/secret，不能进入 lock、日志、异常或 actuator。

容器 health 与模型 readiness 分离：etcd 用 `etcdctl endpoint health`，MinIO 用 `mc ready local`，Milvus 用 `http://localhost:9091/healthz` 并配置 90s start period，Ollama 用 `/api/version` 或 `/api/tags` 只证明进程存活。BGE-M3 digest + 1024 维 embedding 是独立 admission，不得塞进 Docker health 并触发自动下载。

### 6.2 Schema

文章 Collection 与 memory Collection 严格使用总设计定义的字段，并在首代 schema 补齐投影竞态所需的显式 identity。字段顺序及 contract 如下；所有字段均 `nullable=false`、无 default，未列 `maxLength` 的非 VarChar 字段不得设置该参数：

| article field | Milvus type | contract |
|---|---|---|
| `chunk_id` | `Int64` | primary key, `autoID=false` |
| `embedding` | `FloatVector` | `dimension=1024` |
| `article_id` | `Int64` | scalar |
| `revision_id` | `Int64` | scalar |
| `author_id` | `Int64` | scalar |
| `chunk_no` | `Int32` | scalar |
| `content_hash` | `VarChar` | `maxLength=64` |
| `is_active` | `Bool` | scalar |
| `published_at_epoch` | `Int64` | UTC epoch millis |
| `language` | `VarChar` | `maxLength=16` |
| `embedding_model` | `VarChar` | `maxLength=64` |
| `parser_version` | `VarChar` | `maxLength=32` |
| `parser_generation` | `Int64` | admitted parser generation |
| `lifecycle_epoch` | `Int64` | source lifecycle |
| `aggregate_version` | `Int64` | `article_chunk_set.chunk_set_version` |
| `source_aggregate_version` | `Int64` | source article version |

| memory field | Milvus type | contract |
|---|---|---|
| `memory_version_id` | `Int64` | primary key, `autoID=false` |
| `embedding` | `FloatVector` | `dimension=1024` |
| `memory_id` | `Int64` | scalar |
| `user_id` | `Int64` | partition key |
| `category` | `VarChar` | `maxLength=24` |
| `sensitivity` | `VarChar` | `maxLength=16` |
| `is_active` | `Bool` | scalar |
| `expires_at_epoch` | `Int64` | UTC epoch millis |
| `content_hash` | `VarChar` | `maxLength=64` |
| `embedding_model` | `VarChar` | `maxLength=64` |
| `lifecycle_epoch` | `Int64` | source lifecycle |
| `aggregate_version` | `Int64` | source aggregate version |

两者都 `enableDynamicField=false`、HNSW/COSINE `M=16, efConstruction=256`，vector index 名固定为 `idx_embedding_hnsw`。

文章 scalar indexes 固定为 `idx_article_active_bitmap` (`is_active` BITMAP)，以及 `idx_article_article_id_inverted`、`idx_article_revision_id_inverted`、`idx_article_author_id_inverted`、`idx_article_language_inverted`、`idx_article_published_at_epoch_inverted`、`idx_article_embedding_model_inverted` 对应同名字段的 INVERTED；memory 固定为 `idx_memory_active_bitmap`/`idx_memory_sensitivity_bitmap` BITMAP，以及 `idx_memory_id_inverted`、`idx_memory_category_inverted`、`idx_memory_expires_at_epoch_inverted`、`idx_memory_content_hash_inverted`、`idx_memory_embedding_model_inverted` INVERTED。实际 SDK/server 若不接受某一组合，contract RED 后只能通过显式设计修订改变，不能静默退回 AUTOINDEX。

文章物理初始名 `metro_article_chunks_bgem3_v1`，read alias `metro_article_chunks_read`。memory 物理初始名 `metro_user_memories_bgem3_v1`，read alias `metro_user_memories_read`；`user_id` 是 partition key 且显式 `numPartitions=64`，不接受服务器默认值。C2 创建后两个 target 都先以 `SCHEMA_ONLY`、consumer null、retention false 登记，普通 resolver 不能返回；article principals 可在受控 contract 中验证 article Collection 权限，但直到 C6 绑定 `article-chunk-milvus`、建立 rebuild job并 CAS 到 BUILDING 前没有 runtime listener。Stage C 只创建/验证 memory schema，不写私人记忆数据，memory target 继续保持 SCHEMA_ONLY 到 Stage E。

文章 Collection 的 `content_hash` 精确等于 `article_chunk.embedding_input_hash`，用于证明向量对应 exact title + heading path + body UTF-8 input；不得在 `revision_content_hash`、`chunk_hash` 和 `embedding_input_hash` 之间任意选择。

schema guard 精确比较字段顺序、DataType、`nullable/default`、每个 VarChar 的 `maxLength`、向量维度、PK/autoID、dynamic field、partition key/partition count、index type/metric/params 和 alias target。已有同名但不兼容的 Collection 必须 fail closed 并要求新 generation，不能原地“修”向量 schema。

日常 search 明确传 BOUNDED，`ef=max(64, topK*4)`；已知 PK 删除后用 STRONG query 验证不存在。删除不得使用无界表达式。

每次文章 current-set 收敛都必须从 MySQL 枚举该 article 的全部历史 `article_chunk.id`：先 upsert 当前 set 的 exact IDs，再对 `historical IDs - current IDs` 在 registry 中 ACTIVE/BUILDING/DRAINING 的每个物理 Collection 按精确 PK 删除并 STRONG 验证；空 current set 等价于删除全部历史 IDs。revision replacement 与 parser generation 切换不能只 upsert 新向量，否则旧向量会长期占据 BOUNDED topK 并造成召回损失。不能只枚举已记录 effect-success 的 manifest，因为外部写成功但本地 manifest 提交前崩溃会留下“未登记”向量。

### 6.3 Milvus 无条件 upsert 的边界

Milvus 2.6 不提供等价于 ES scripted update 的 per-row compare-and-set。安全契约因此是：

1. Stage C 扩展通用 lease 为 DB-time `renew/assertOwned`；live consumer 在 Provider/外部 effect 前后检查或续租，并以 MySQL aggregate lease/watermark 串行正常 effect。实体携带 lifecycle/version/is_active。
2. lease 丢失或旧调用晚返回时，该 worker 不得 complete watermark/Inbox；它只能对当前 target/entity 做单调 `repair_required=1, next_attempt_at=LEAST(existing, DB_NOW)` 的 fail-safe 请求，不得写 desired identity。repair dispatcher 重投 current-state effect；若该单调标记也失败，周期 anti-entropy 仍会枚举 desired manifest 与 raw target 的差异。
3. 删除使用 STRONG verification；最终 race test 要求 repair 后 raw target 无旧 active entity。
4. 无论 raw repair 是否尚未完成，任何候选在 MySQL current-pointer revalidation 前都不得离开知识检索边界，所以晚到旧实体的用户曝光数始终为零。

owner token 每次 delivery 都是新 UUID。`renew` 只能在同 consumer/aggregate、同 owner、`lease_until > DB CURRENT_TIMESTAMP(6)` 且 watermark 仍等于 acquire 时的 previous lifecycle/version 时延长；`assertOwned` 使用同一谓词。`complete` 继续要求 owner + unexpired lease + previous watermark exact，再一次性写新 watermark/Inbox 并清 lease。任一 affected-row 非 1 都是 lease lost，不得使用 JVM 时间或“延长 timeout”代替 fencing。

普通 `acquire(event)` 继续把 `event tuple <= watermark` 判为 STALE；reconcile/anti-entropy 不得伪造 event version 绕过它。Stage C 另加 `acquireRepair(consumer, aggregate, currentLifecycle, currentVersion, targetId, targetGeneration, lease)`：只在现有 watermark **恰等于** current tuple、租约为空/过期且 registry target generation 仍匹配时，用同一 watermark row exact CAS 取得新 owner。repair lease 在每次外部 effect 前后重验 lease、projection fence、MySQL current truth 与 target identity；live higher-version event 因同一 lease 串行，若它先推进 watermark，旧 repair CAS 必为 0。

`completeRepair` 只在 owner/unexpired lease、watermark exact 未变、current truth 与 target generation 仍匹配时，原子更新对应 manifest/清 `repair_required` 并释放 lease；它不推进/回退 watermark、不改变 tombstone、不插 event Inbox。失败或 post-check stale 保持/重置单调 repair request，由下次 anti-entropy 处理。测试必须覆盖 equal-watermark raw document/vector 缺失能恢复、repair 与更高 live event 两种先后顺序、repair effect 后失租不误清标记，以及 Milvus 晚写最终被 current-state repair 收敛。

不在文档或简历中声称 Milvus raw storage 具备不存在的原子版本 CAS。

## 7. 独立 Elasticsearch 8.18.1 C4 检查点

Spring Data Elasticsearch 5.5.x 的官方轴是 Elasticsearch 8.18.1；把 Java/REST client 强降到 8.4.1 已证明同时存在应用源码 API 差异和 SDE bytecode 缺类，不能靠 `clean compile` 或有限路径测试声明支持。因此 ES server/plugin/data migration 前移为 C4 独立提交，仍不与 C2 Milvus schema 或 C5 chunk index 合并：

1. 取得 ES/Kibana 8.18.1 的 tag + manifest-list/amd64/arm64 digest，与精确匹配的 IK artifact URL/SHA-256/plugin descriptor/license；不存在或无法验证时停止检查点。
2. 在 `pom.xml` 持久显式锁定 `elasticsearch-java` 与 low-level REST client 8.18.1，保持 Spring Data Elasticsearch 5.5.13；临时 `-D` override 不算实现。门禁用 `help:evaluate` 断言 effective `elasticsearch-client.version=8.18.1`，用 dependency tree + Maven Enforcer/专用 alignment test 证明两 client 各只有一个 8.18.1、无 8.18.8/第二版本，再跑无 override 的 clean compile、linkage/API smoke、原始 client、Spring Data 与全量回归。
3. 新建 8.18.1 数据 volume/测试集群，不在旧 8.4.1 data volume 上直接试写。从 MySQL current pointer truth 重建物理 index `metro_articles_8_18_1_v1`，逻辑 alias 固定为 `article`，不把旧 ES 文档当事实源。
4. 新 article target 以 BUILDING 注册并补齐 current-state manifest；mapping/analyzer、公开性、推荐/MLT、delete/late-event、count/hash、watermark、alias readback 与 exact diff 全部通过后才 ACTIVE。`article-search-current-pointer` 的 destructive retention proof 继续使用 WATERMARK，target manifest 只承担蓝绿、repair 与运维对账；旧 concrete `article` 不被假装成已纳管 target。
5. 在 projection fence 下最终 replay，在新集群原子创建/切换 `article` alias 后再切部署 endpoint；启动 verifier 必须证明 `article` 是指向 registry ACTIVE physical index 的 alias，代码不再接受同名 concrete index。
6. 保留旧 8.4.1 集群/volume 与上一版 binary 的回滚窗口。回滚是 endpoint + binary 的受控成对操作，不在新 volume 上节点降级；上一版会继续推进共享 WATERMARK 但不知道新 target manifest，因此从旧端返回 8.18.1 时必须再次停流量/排空旧 binary、取得 site-search projection fence，并从 MySQL current truth 对新 target 做不依赖旧 Outbox/Inbox 的全量 raw-vs-source exact repair/diff。equal-watermark row 用 `acquireRepair`，behind row 用普通 current-state acquire；全部 manifest、alias readback、tombstone/count/hash 通过后才成对恢复新 endpoint+binary。失败继续旧端或保持 fence，不能仅凭 startup alias/registry check 放流量。测试必须在回滚窗口真实 publish/replace/delete，再证明 re-promotion 前新 target缺口被全部修复。新集群产生无法由旧 binary表示的独占写入后不声称镜像级降级安全。

## 8. Elasticsearch chunk 投影

C5 只在上述 8.18.1 server/client/plugin/alias tuple 全部门禁通过后创建物理 index `metro_article_chunks_v1` 和 read alias `metro_article_chunks_read`，与站内 `article` alias 完全分离。mapping 使用 `dynamic=strict`；`title/headingPath/bodyText` 使用匹配服务器版本的 `ik_max_word` index analyzer 与 `ik_smart` search analyzer；identity/hash/version 字段使用 keyword/long/boolean/date 精确类型。

每个 chunk document 显式携带 `documentKind,chunkId,articleId,revisionId,authorId,chunkNo,title,headingPath,bodyText,revisionContentHash,chunkHash,parserVersion,parserGeneration,language,publishedAt,lifecycleEpoch,aggregateVersion,sourceAggregateVersion,isActive,tombstone`，其中 `aggregateVersion=chunk_set_version`。ES `_id` 固定为 `chunk:<chunkId>`，control doc 为 `article:<articleId>`，不共享数值 ID 命名空间。bulk response 必须逐 item 检查；HTTP 200 但任一 item error 仍视为失败。收敛时从 MySQL 读取该 article 的全部历史 `article_chunk.id`，对非 current set 逐一写相同或更高 aggregate identity 的 durable tombstone；不能只删除 manifest 已完成的 rows，否则已 acquire 的旧 worker 可在新 index 中创建无 fence 文档。另写一个不可搜索的 per-article control document 供 chunk-set hash/lifecycle/version 对账。搜索固定排除 control、tombstone 与 isActive=false。BM25 repository 使用 PIT + stable search_after 或单页 top40，不复用普通站内 search 的 page-number/candidate cap。

ES 外部 effect 继续使用同文档 Painless scripted monotonic fence，原子比较 `(lifecycleEpoch,aggregateVersion)`，避免已 acquire 的旧调用跨 lease 覆盖新 tombstone。mapping/alias 不兼容时 fail closed；应用不能让 dynamic mapping 猜 hash/version 类型。

## 9. 重放、删除与蓝绿切换

### 9.1 Reconcile

每个投影都有 bounded、keyset、可恢复 reconcile：

- watermark 尚未达到 current truth 时从 current public article/chunk truth 生成 deterministic synthetic event，走普通 event acquire；watermark 已等于 current truth但 raw target/manifest 漂移时走上述 repair-acquire，绝不伪造更高 version。
- live、reconcile 与 repair 走同一个 effect service、同一 watermark lease row、manifest 与 registry target resolver。
- BUSY 不 ACK/不越过 cursor；DB 异常不解释为 tombstone。
- 全量对账比较 PK + revision/content/chunk hash，不以 count 相等代替。

destructive Outbox retention 通过 event-to-consumer registry 枚举该 event 映射中 `state IN (ACTIVE,DRAINING) AND required_for_retention=true` 的全部逻辑 consumer；因此初始只有 site-search，chunk-fact 只在 operator 完成 admission 并原子转 ACTIVE+required 后加入，DISABLED consumer 不会造成永久 pin。WATERMARK consumer 要求 exact/newer watermark，destructive exact tuple 还要求 tombstone=true；TARGET_MANIFEST consumer 才枚举其 registry ACTIVE target并要求相同或更新 lifecycle/version 的成功 manifest。BUILDING/DRAINING target 若尚未追平，其 `repair_required`/rebuild item 必须继续 pin 住对应 Outbox，直到追平或 operator 显式撤销可推广/回滚资格。不能只检查旧 site-search consumer，也不能因 site-search 没有 target row就要求虚构 manifest。任一 BUILDING rebuild 还会用 `snapshot_high_water_id/last_replayed_outbox_id` pin 住需要的 Outbox 范围，直到 alias proof 已提交。retention 的 keyset selector 与 exact batch delete 在同一短事务中重验同一 registry state/required/proof predicates，activation CAS 与正在删除的行由数据库锁序串行。

`ARTICLE_CHUNK_REINDEX_REQUESTED` payload 必须携带 `activeChunkCount`。该值为 0 时 retention 把它视为 destructive chunk-set event，要求 exact 或 newer watermark；若恰好是 exact version，ACTIVE target 的 control/manifest 必须明确 tombstone=true。后续更新 chunk-set version 已收敛时可删旧事件，但仍受 BUILDING/DRAINING pin 约束。selector 与 exact batch delete 都要重验这些谓词。

### 9.2 Blue/green

重建顺序固定为：

1. one-shot operator 创建 BUILDING physical target 并验证 schema/index/load。
2. 在 REPEATABLE READ consistent snapshot 中冻结 rebuild item 的 active PK/hash 集合，并记录已提交 Outbox high-water；事务内只复制 identity，不调用 ES/Milvus/Ollama。
3. 事务外分页回源时把 snapshot item 当枚举提示；每个 entity 先重验 current hash，watermark behind 走 deterministic synthetic event 的普通 aggregate lease，equal-watermark drift 走 repair lease，随后才生成 embedding/写外部 target。item 在外调前短事务标成 `EFFECT_IN_FLIGHT(owner,deadline)`，外调使用小于该 deadline 的硬 client/server timeout；完成/失败由同 owner exact CAS。开启 registry 驱动的 ACTIVE+BUILDING 双写。
4. 通过专用 `DomainEventOutboxMapper.selectPublishedAfterId(id, limit)` 从 high-water 后稳定重放增量。由于 auto-increment 可存在未提交低 id，high-water 不是唯一正确性依据，随后必须执行 MySQL current-set exact diff。
5. 对新 target 做 active PK、hash、dimension、STRONG tombstone 与固定 recall contract；任一 mismatch 留在 VERIFYING/FAILED。
6. 设置全局 projection switch fence，阻止 live/reconcile/repair/rebuild 新外部 effect 开始；等待所有 aggregate lease、job/item `EFFECT_IN_FLIGHT` 与 one-shot operator future 真实完成。租约过期本身不算外调结束；crash recovery 必须按上述 process termination + hard deadline barrier 收敛。文章事务仍可写 Outbox。
7. 捕获 fence high-water，重放到该点并再做 current-set raw exact diff；确认无 in-flight attempt 后切 Milvus alias/ES alias，再 exact CAS registry generation/state/proof。
8. 解除 fence；之后积压事件写新 ACTIVE，同时在 7 天窗口继续写 DRAINING target。
9. 回滚只允许在旧 target 持续双写、重新验证通过且 rollback deadline 未过时切 alias；过期后 one-shot operator 才可 RETIRE/drop。

alias 切换成功但 registry CAS 失败、或相反，必须由启动/恢复 verifier 根据 alias target + registry generation fail closed 并要求 operator repair，不能让普通 runtime 猜测哪个是真值。

## 10. 故障与可观测性

- Milvus unavailable：Dense repository 返回 typed unavailable；后续 Agent 可退 ES BM25。普通站点不失败。
- Ollama unavailable/错维：Milvus projection 保持可重试，ES chunk projection继续；不写假向量。
- ES unavailable：Dense candidate 可继续，但仍需 MySQL 回源；普通站内搜索沿用自身错误契约。
- Rabbit unavailable：事实事务只留 PENDING Outbox；不直发外部系统。
- 外部 effect 成功、本地 complete 失败：lease 后重复确定性 effect。
- metrics 只带固定 target kind/outcome/state；不带 article/chunk/user ID、正文、向量或凭据。

关键指标：Outbox age、chunk fact lag、每个 projection watermark lag、repair_required count、registry state、alias drift、embedding dimension/error、external effect latency、MySQL revalidation drop count。Actuator health 不触发 collection create、模型 pull 或正文检索。

## 11. 测试与发布门槛

自动门禁至少包含：

- Compose/lock：profile isolation、loopback ports、health/dependency、no-latest、registry digest、model auto-pull absence、端口冲突零容器副作用。
- Schema SQL：fresh/upgrade/idempotence/prefix interruption/drift/grant、article_chunk deterministic collision、registry state/CAS。
- Chunk：CJK/emoji/heading/list/quote/code/table、350/600/80 exact boundaries、oversize progress、same input byte-identical rows、parser generation admission/change/rollback 与旧 worker fence。
- ES：mapping/alias、BM25 top40、bulk partial failure、replace/reject-old-public/unpublish/recycle/delete/restore、late acquired effect、site-search regression。
- Milvus locked container：auth/RBAC、article principals 对 memory 全部 PermissionDenied、memory SCHEMA_ONLY registry owner、exact schema/dynamic off/HNSW/COSINE/scalar index/alias、1024 upsert、wrong dimension、BOUNDED search、STRONG delete、restart recovery。
- Projection：duplicate、out-of-order、old lifecycle、effect-success/complete-fail、lease expiry、Milvus late response repair、MySQL revalidation zero exposure。
- Blue/green：snapshot gap、uncommitted low Outbox id、double write、delete during rebuild、crash at every state、fence drain、alias/registry split-brain、rollback deadline。
- ES upgrade：8.18.1 + matching IK checksum、fresh rebuild、aliases、current site search/recommendation/MLT regressions，以及旧8.4 rollback期间 publish/replace/delete 后的强制 full repair re-promotion。
- AI profile absent：完整 `./mvnw test` 仍为绿色且无 Milvus/Ollama 网络调用。

Milvus 使用独立 `MilvusIntegrationTestSupport`/GenericContainer，不加入现有 `IntegrationTestSupport` 的 static Startables。该 support 在独立 Testcontainers Network 上创建 etcd/MinIO/Milvus，使用随机宿主端口与 auth config mount；restart 合约对同一 container ID 做 in-place restart，不用会删除容器的 `stop()` 假装重启。真实类命名为 `*MilvusIT`，只由显式 Maven `milvus-contract` profile/Failsafe 执行，默认 Surefire 不发现、不 skip 也不拉镜像。唯一验收命令为 `./mvnw -Pmilvus-contract verify`；profile 必须绑定 Failsafe `integration-test`/`verify` phase、include `**/*MilvusIT.java`、设置 `failIfNoTests=true`、验证 lock digest，并从报告断言 `tests>0, skipped=0`。`-Pmilvus-contract test` 不算真实门禁。`NoAiStartupIntegrationTest` 还必须证明 Milvus client、schema operator、chunk listeners 与 Ollama embedding worker 均未装配或未连接。

本地 ARM64 使用原生 arm64 child digest 跑 schema/auth/restart/delete，禁止强制 `linux/amd64` + QEMU 后声称 ARM 兼容。若生产同时支持 amd64，CI 还必须在原生 amd64 runner 对对应 child digest 跑同一套合约。Ollama 在 Apple Silicon Docker 上不宣称未实测的 GPU 加速。

Stage C 的精确 Surefire 类为 `ArticleChunkerTest`、`ArticleChunkProjectionIntegrationTest`、`ElasticsearchDependencyAlignmentTest`、`ElasticsearchUpgradeIntegrationTest`、`ArticleChunkProjectionReplayRaceIntegrationTest`、`ElasticsearchArticleChunkProjectionIntegrationTest` 与 `ArticleSearchRegressionIntegrationTest`；每个报告都必须 `tests>0, skipped=0`，不得因同一 `-Dtest` 列表中另一个类存在就容忍缺类。不得用 Stage B 已存在的 `ArticleProjectionIntegrationTest` 名称充当新功能证据。真实 Milvus 容器门禁为 `MilvusSchemaContractMilvusIT`、`MilvusArticleVectorRepositoryMilvusIT`、`MilvusRestartRecoveryMilvusIT`。

真实 BGE-M3 quality smoke 是 opt-in，报告必须写 `NOT RUN` 或记录 model digest、数据集和结果；受控 stub 与固定向量只能证明协议和状态机，不能证明语义质量。

## 12. 版本依据

- Milvus 2.6.20 release matrix（Java SDK 2.6.22）：<https://milvus.io/docs/v2.6.x/release_notes.md>
- Milvus Java 2.6 API：<https://milvus.io/api-reference/java/v2.6.x/About.md>
- Milvus standalone Compose：<https://milvus.io/docs/v2.6.x/install_standalone-docker-compose.md>
- Milvus schema/index/consistency/RBAC：<https://milvus.io/docs/v2.6.x/schema.md>、<https://milvus.io/docs/v2.6.x/consistency.md>、<https://milvus.io/docs/v2.6.x/authenticate.md>
- Ollama BGE-M3（1024 embedding metadata）：<https://ollama.com/library/bge-m3>
- Ollama model identity/API：<https://docs.ollama.com/api/tags>、<https://docs.ollama.com/docker>
- Elasticsearch 8.18.1 release notes：<https://www.elastic.co/guide/en/elasticsearch/reference/current/release-notes-8.18.1.html>
- Elasticsearch Java client compatibility：<https://www.elastic.co/docs/reference/elasticsearch/clients/java>
- Spring Data Elasticsearch version matrix：<https://docs.spring.io/spring-data/elasticsearch/reference/elasticsearch/versions.html>
- IK source/release authority：<https://github.com/infinilabs/analysis-ik>
- CommonMark Java source/release authority：<https://github.com/commonmark/commonmark-java>

这些版本是本阶段锁定基线，不是“latest”声明。任何 server/SDK/image/model/plugin digest 变化都重新触发真实容器 schema、alias、filter、delete、restart、dimension 与检索回归门禁。
