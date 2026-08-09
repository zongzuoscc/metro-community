# Community Agent A–G Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不破坏社区、人工审核、私信、搜索和现有推荐链路的前提下，分 A–G 七个可回滚阶段交付 Java 单 Agent、长期记忆、混合检索、写作助手和安全审核。

**Architecture:** 保持 Spring Boot 模块化单体，MySQL 是对话、记忆、revision 和删除状态的事实源，Redis/RabbitMQ/Elasticsearch/Milvus 只承担可重建的运行态、传输或投影。先将 Provider 与存量业务解耦，再完成 revision + Outbox 事实层，然后依次接入向量投影、无记忆 Agent、长期记忆、写作 diff 和审核评测。

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, MyBatis-Plus Boot 3 Starter 3.5.17, MySQL 8, Redis, RabbitMQ, Elasticsearch 8.x, Milvus 2.6.20 / Java SDK 2.6.22, Ollama `bge-m3`, Resilience4j, Micrometer/Prometheus, Testcontainers.

## Global Constraints

- 只用 Java 实现后端；不新建 Python 服务或独立 AI 微服务。
- 依赖基线固定为 Java 21、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 3.5.17 Boot 3 Starter。
- 不在本路线升级 Spring Boot 4、Spring AI 2 或 Elasticsearch 9。
- 数据库变更采用 `docs/database/migrations/` 下显式、幂等、只向前的 SQL；不引入 Flyway，不在回滚时删除新表或数据。
- 每个用户只有一个 Agent、一条可见时间线和一个同时活动生成；不增加多 Agent 或多会话管理。
- Agent 永远是 1 级只读权限；不提供关注、点赞、收藏、评论、保存、发布、删除、任意 HTTP/SQL/文件系统工具。
- 安全状态机、ACL、userId、引用合法性、记忆状态、配额、发布与 diff 应用由 Java 确定；模型输出不能直接改变业务状态。
- AI/Key/Provider/Milvus/Ollama/Redis 的 Agent 运行依赖不可用时，普通社区、人工审核、私信、搜索、编辑和推荐仍必须运行。
- 生产默认不记录 prompt、文章全文、记忆正文、完整模型回答、密钥或向量请求。
- 单元测试可用于快速红绿循环，Provider adapter 协议测试只允许进程内本地 stub HTTP server；阶段最终验收不能仅依赖 mock，集成边界使用真实 Testcontainers。
- 实施、测试和评测不调用 live 生产服务；真实 DeepSeek 验收只能在隔离的显式验收环境中使用专用 Key。

---

## Cross-stage dependency graph

```text
A AI safety foundation
  └─> B immutable revision + moderation state machine + outbox
        └─> C Ollama/Milvus + article chunk projections
              └─> D memory-free read-only Agent + SSE/UI
                    ├─> E long-term memory + temporary mode + deletion/export
                    └─> F Tiptap selection diff assistant
B shadow moderation data ────────────────────────────────> G evaluation and optional auto-pass decision
```

No stage may use a later-stage data model as an implicit prerequisite. In particular:

- A does not add `article_revision`, a generic Outbox, Milvus collections, Agent turns, memory tables, or writing suggestions.
- B does not require Milvus or an Agent UI.
- C indexes only the current `published_revision_id`; it does not create conversations or memories.
- D ships without memory and must remain useful with BM25-only fallback.
- E depends on D's run guard/conversation boundary and C's vector repository boundary.
- F consumes A's Writer capability and D's authentication/error conventions, but never gains article save/publish authority.
- G changes no automatic decision until its sample and leakage gates are met.

## Permanent invariants and release gates

| Invariant | Automated gate | Release-blocking evidence |
| --- | --- | --- |
| AI disabled/no Key does not stop the application | no-AI context + full Testcontainers suite | application starts; normal authenticated private message and manual moderation succeed |
| `chat_msg` contains human private messages only going forward | WebSocket/HTTP private-message integration tests | no `9999` branch, injected `ChatService`, no `ChatUtils`, no unbounded native thread |
| AI APIs do not reuse legacy `Result` errors | `/api/agent/**` contract tests | RFC 9457 body and real 4xx/5xx; existing `/api/**` tests still see `Result` |
| Public content comes only from current published revision | revision/CAS/ACL tests | draft/pending/rejected/deleted text is never returned or indexed as public |
| Async delivery cannot outrun its source transaction | Outbox rollback/confirm/replay tests | no direct Rabbit publish in a source transaction for migrated events |
| Vector/search hits are never authorization truth | MySQL source-of-truth integration tests | stale or cross-user projection hit is discarded after MySQL revalidation |
| One user has at most one active generation | MySQL guard + Redis fence race tests | stale worker cannot write delta/final after a higher fence |
| Personal memory cannot cross users or survive disabled/deleted state | MySQL FK/epoch/Milvus filter/delete tests | cross-user leakage count is zero |
| Agent has no write side effects | tool-registry and red-team tests | whitelist contains only five specified read-only tools; write-effect count is zero |
| Writer never saves/publishes | suggestion API + front-end stale/hash tests | backend only records proposal/status; Tiptap applies after explicit user action |
| Moderation never fail-opens | malformed/timeout/provider-off tests | every uncertain result reaches `HUMAN_PENDING`, never public/rejected automatically |

## Stage A: AI safety foundation

**Outcome:** ordinary private messages and manual review no longer depend on AI; the obsolete prototype is removed; versioned, typed, capability-scoped provider infrastructure is disabled safely by default and exposes bounded execution, quota, metrics, and AI-only errors.

**Detailed executable plan:** `docs/superpowers/plans/2026-08-10-community-agent-foundation.md`.

**Required order and commit boundaries:**

1. `fix(chat): decouple private messaging from legacy ai` — inject `ChatService` into the WebSocket endpoint, persist synchronously before push, remove `9999` shortcuts and delete `ChatUtils`.
2. `refactor(ai): remove legacy ai prototype endpoints` — delete `AiController`, `MetroAiService`, `AiToolConfig`, and the free-text legacy AI audit consumer.
3. `build(ai): upgrade spring ai foundation` — Boot 3.5.16, Spring AI 1.1.8, MyBatis-Plus 3.5.17, DeepSeek/Ollama starters, WebFlux, Actuator, Prometheus, Resilience4j; default all model auto-configuration off.
4. `feat(ai): add typed capability provider gateways` — add `AiChatGateway`, `EmbeddingGateway`, typed properties, conditional DeepSeek/Ollama adapters, provider error taxonomy, and force Spring AI internal retry to one attempt.
5. `feat(ai): enforce capability budgets and telemetry` — add capability-specific limits, deadline, quota, bulkhead, retry, circuit-breaker and low-cardinality metrics.
6. `fix(moderation): fail closed to manual review` — add a compatibility human-review router for the legacy status model; disabled, missing-Key, malformed, timeout and provider errors leave the article in the existing manual queue and never publish/reject.
7. `feat(ai-api): add scoped problem details and cors contract` — add the AI-only RFC 9457 advice/security handlers and allow `PATCH`, `Last-Event-ID`, `Idempotency-Key`; keep legacy `Result` unchanged.
8. `docs(ai): document disabled-by-default foundation` — environment/docs cleanup and full regression evidence.

**Gate commands (local/Testcontainers only):**

```bash
./mvnw -Dtest=PrivateMessageIntegrationTest,NoAiStartupIntegrationTest test
./mvnw -Dtest=DeepSeekAiChatGatewayContractTest,OllamaEmbeddingGatewayContractTest test
./mvnw -Dtest=AiCapabilityExecutorTest,AiQuotaServiceIntegrationTest,AiMetricsTest test
./mvnw -Dtest=ModerationFallbackIntegrationTest,AiProblemDetailIntegrationTest,AiCorsIntegrationTest test
./mvnw test
./mvnw -DskipTests package
./mvnw dependency:tree -Dincludes=org.springframework.ai,com.baomidou:mybatis-plus-spring-boot3-starter
```

Expected: all tests pass; the dependency tree contains Spring AI 1.1.8 and MyBatis-Plus 3.5.17, contains no `spring-ai-openai-spring-boot-starter`, and `pom.xml` contains no Spring Milestone repository. No DeepSeek/Ollama request is sent by the full default suite.

**Promotion gate:** start the packaged jar against isolated Testcontainers-compatible dependencies with every `metro.ai.*.enabled=false` and empty Provider keys; verify `/actuator/health`, ordinary private-message persistence, the existing article/admin/manual-review path, search, and recommendation tests. Stop immediately if any non-AI endpoint returns an AI availability error.

## Stage B: immutable revision, safe moderation, and generic Outbox

**Depends on:** A's capability-scoped Moderation gateway, bounded execution, metrics, disabled/no-Key semantics, and AI-only error taxonomy.

**Deliverables:**

- Add the explicit idempotent forward migration `docs/database/migrations/2026-08-10-article-revision-moderation-outbox.sql`; do not add Flyway.
- Add `article_draft`, immutable `article_revision`, `article_moderation_job`, append-only `article_moderation_attempt`, `domain_event_outbox`, `consumer_inbox`, and `projection_watermark` plus the specified composite uniqueness/FKs.
- Execute expand -> deterministic backfill -> 100% hash/count/pointer verification -> cutover. Conflicting legacy rows go into an explicit migration report; they are not guessed.
- Move autosave to mutable private draft; submission freezes a new immutable revision and supersedes an older non-terminal job.
- Make public reads use only `published_revision_id`; rejected or pending new revisions keep the old published revision visible.
- Replace direct Rabbit sends for migrated events with transactional Outbox; add confirm-aware dispatcher and per-aggregate lease/watermark/inbox consumers.
- Replace compatibility fallback with structured, tool-free, temperature-zero, JSON-object moderation; strict Jackson unknown-field rejection, Bean Validation/local schema, chunk limits and highest-risk aggregation.
- Keep first release in shadow mode: every model result enters `HUMAN_PENDING`; administrator approval/rejection uses the specified job+article+revision double-object CAS.

**Commit boundaries:** schema expand; legacy backfill/verifier; draft/revision write path; public-read cutover; generic Outbox/inbox/watermark; structured moderation; admin CAS API; retention/operations docs. Each commit must leave public reads and manual review usable.

**Gate commands:**

```bash
./mvnw -Dtest=ArticleRevisionMigrationIntegrationTest,ArticleDraftRevisionIntegrationTest test
./mvnw -Dtest=ArticleModerationStateMachineTest,ArticleModerationIntegrationTest test
./mvnw -Dtest=DomainEventOutboxIntegrationTest,ProjectionWatermarkIntegrationTest test
./mvnw test
```

Expected: migration run twice is unchanged; article/revision/draft counts and hashes match; rollback publishes no event; duplicate/out-of-order events converge; prompt injection, blank/truncated/unknown JSON, low confidence, timeout and Provider failure all result in human pending; no model decision changes `published_revision_id`.

**Promotion gate:** verify 100% production-like snapshot counts/hashes and ES public-document pointers in a cloned, isolated database before cutover. After cutover, rollback is feature-disable + forward fix; do not start an old binary against the new draft/published semantics.

## Stage C: Ollama, Milvus, and knowledge projections

**Depends on:** B's immutable published pointer and Outbox/watermark semantics; A's Embedding gateway. It does not depend on Agent conversations.

**Deliverables:**

- Add Compose `ai` profile only here: Milvus 2.6.20, etcd, MinIO, Ollama, named volumes, health checks, loopback-only host ports and preflight collision checks.
- Resolve `milvusdb/milvus:v2.6.20` to an immutable digest and record it in a deployment lock file; use Java SDK 2.6.22.
- Add explicit idempotent forward SQL for `article_chunk`, projection manifest/registry and aggregate watermarks; no Flyway.
- Implement project-owned `ArticleVectorRepository` and collection registry; do not use Spring AI VectorStore to create production collections.
- Create the specified 1024-dimension article and memory physical schemas/aliases with dynamic fields disabled, HNSW+COSINE, scalar indexes, BOUNDED reads and STRONG deletion verification.
- Parse only current published revisions into deterministic 350–600 token chunks with 60–100 token overlap; MySQL stores body text, ES/Milvus store projections.
- Add chunk-level ES physical index/alias and BM25 top-40 retrieval. Keep normal site search separate.
- Perform blue/green snapshot + high-water replay + write fence + alias switch, including deletes and late-event races.
- Upgrade Elasticsearch to 8.18.1 only at its own checkpoint, with matching IK plugin, full reindex and alias switch; do not combine it with the first Milvus schema commit.

**Commit boundaries:** Compose/digest; Milvus schema repository; article chunk SQL/parser; ES chunk projection; Milvus projection; projection replay/delete; blue/green alias; isolated ES 8.18.1 checkpoint.

**Gate commands:**

```bash
./mvnw -Dtest=ArticleChunkerTest,ArticleProjectionIntegrationTest test
./mvnw -Dtest=MilvusSchemaContractTest,MilvusArticleVectorRepositoryIntegrationTest test
./mvnw -Dtest=ProjectionReplayRaceIntegrationTest,MilvusRestartRecoveryIntegrationTest test
./mvnw -Dtest=ElasticsearchChunkProjectionIntegrationTest,ArticleSearchRegressionIntegrationTest test
./mvnw test
```

Expected: real locked Milvus container passes create/index/alias/upsert/filter/delete/restart/auth/dimension-error contracts; stale events cannot revive unpublished content; every hit is revalidated against MySQL; AI profile being absent still passes the full application suite.

## Stage D: memory-free read-only Agent

**Depends on:** A provider/budget/error foundation, B current-public revision/Outbox, C retrievers/projections. Memory is explicitly disabled and absent from context.

**Deliverables:**

- Add explicit idempotent SQL for profile, one conversation per user, episode, run guard, turn, visible message, tool trace, retrieval hit and final citation with same-user composite FKs.
- Implement MySQL admission order, monotonic `run_fence`, Redis Lua claim/renew/release, bounded event Stream/canonical snapshot, stale-worker recovery and one active generation per user.
- Add POST turn creation, owner-scoped snapshot, SSE replay/410 gap recovery and cancel; Bearer remains in Authorization, never query parameters.
- Implement memory-free `ConversationContextAssembler`, at-most-two-round `ReadOnlyPlanner`, five-tool whitelist, maximum four tool calls, and tool-free Synthesizer.
- Implement ES BM25 + Milvus Dense RRF, optional one-shot HyDE, MySQL ACL revalidation, citation validator, one citation-repair pass, safe Markdown/URL sanitization and real-search fallback cards.
- Add bootstrap/profile APIs and desktop/mobile single-timeline UI; remove the old article AI summary card. No conversation list or second Agent.

**Commit boundaries:** schema/repositories; run guard/admission; event stream/snapshot/cancel; read-only tools/ACL; hybrid retrieval; grounded answer/citations; API contracts; profile/bootstrap; UI shell/accessibility; internal-account flag.

**Gate commands:**

```bash
./mvnw -Dtest=AgentRunGuardIntegrationTest,AgentTurnRecoveryIntegrationTest test
./mvnw -Dtest=AgentSseIntegrationTest,TemporaryUnsupportedContractTest test
./mvnw -Dtest=ReadOnlyPlannerTest,HybridRetrievalIntegrationTest,CitationValidatorTest test
./mvnw -Dtest=AgentOwnerScopeIntegrationTest,AgentSecurityRedTeamIntegrationTest test
./mvnw test
```

Expected: one-user concurrency, idempotency hash conflicts, fence takeover, cancel and SSE trim-gap recovery pass on real MySQL/Redis; tools cannot accept `userId`; unpublished/cross-user sources never reach Provider; the write-side-effect red-team count is zero. Temporary mode remains unavailable until E rather than persisting partial semantics.

## Stage E: long-term memory, temporary mode, deletion and export

**Depends on:** D's run guard/conversation/epoch hooks and C's project-owned memory vector repository. Starts behind `metro.ai.memory.enabled=false`.

**Deliverables:**

- Add explicit idempotent SQL for memory item/version/source/projection/settings and durable deletion/export jobs with same-user composite FKs.
- Implement candidate extraction from user messages only, credential rejection, sensitivity classifier, confirmation, versioning, expiry, pause/resume and settings optimistic locking.
- Encrypt sensitive content with AEAD key ring and keyed HMAC; missing active/previous keys fail closed. Sensitive vector projection remains opt-in and off by default.
- Implement exact preference + Milvus candidate recall, Java reranking, token budget, `memory_epoch` recheck immediately before Provider calls and separate `memoryUses[]`.
- Add Redis temporary session with absolute 24-hour TTL, no MySQL content, no memory recall/extraction, same run guard and explicit expiry/restart semantics.
- Implement deletion state machine: MySQL DELETING/tombstone first, exact PK deletion in every active/double-write/rollback collection, STRONG verification, cache/export cleanup, then physical MySQL content deletion.
- Add owner-scoped async export, encrypted 24-hour file and account deletion orchestration. Never export prompts, tool raw results, Provider responses or inaccessible citations.

**Commit boundaries:** memory schema; encryption/key rotation; extraction; settings/control API; projection/recall/epoch; temporary session; deletion job; export; account deletion; retention/reconciliation.

**Gate commands:**

```bash
./mvnw -Dtest=MemoryIsolationIntegrationTest,MemoryEpochRaceIntegrationTest test
./mvnw -Dtest=SensitiveMemoryEncryptionIntegrationTest,MemoryProjectionIntegrationTest test
./mvnw -Dtest=TemporarySessionIntegrationTest,MemoryDeletionIntegrationTest test
./mvnw -Dtest=AgentExportIntegrationTest,AgentAccountDeletionIntegrationTest test
./mvnw test
```

Expected: cross-user memory/source FK attacks fail; paused/deleted/expired/old-version memory cannot be recalled; default sensitive projection count is zero; late activation cannot revive deletion; exact vector cleanup is verified within the test SLO; temporary mode creates no conversation rows and cannot outlive its absolute expiry.

## Stage F: Tiptap selection-diff writing assistant

**Depends on:** A's Writer gateway/budgets and D's authenticated AI-only API/errors. It does not depend on memory and does not get article write authority.

**Deliverables:**

- Add explicit idempotent SQL for `agent_writing_suggestion`; store the specified hashes/version/range/original/proposal/diff and terminal state.
- Implement `POLISH/SHORTEN/EXPAND/TITLE/OUTLINE/SUMMARY/TAGS` only; use a tool-free Writer client.
- Verify owner for an existing `articleId`; for a new article accept only the explicitly supplied selection.
- Return plain original/suggested/diff, never HTML/embed/script. Backend does not claim it can see unsaved Tiptap state.
- Frontend compares `documentVersion`, `baseDocumentHash`, and `selectedTextHash`; explicit apply uses one Tiptap transaction and retains undo. Route/selection/document changes invalidate instead of applying.
- Applied/rejected/invalidated endpoints record the user's decision idempotently; they never save or publish the article.

**Commit boundaries:** SQL/repository; writer request/idempotency/ACL; diff response; terminal-state API; Tiptap preview/apply/undo/stale; retention/metrics.

**Gate commands:**

```bash
./mvnw -Dtest=WritingSuggestionIntegrationTest,WritingSuggestionOwnerScopeIntegrationTest test
npm --prefix ../metro-community-frontend run test -- --run writing-suggestion
npm --prefix ../metro-community-frontend run build
./mvnw test
```

Expected: duplicate same hash returns the same suggestion, changed hash conflicts, stale frontend state never applies, another user cannot observe/change a suggestion, and no request to the writing endpoints changes article draft or published content.

## Stage G: moderation evaluation and optional auto-pass decision

**Depends on:** B's shadow moderation and durable attempts; A metrics. This stage is an evidence gate, not a promised automatic behavior change.

**Deliverables:**

- Build a versioned Java evaluation runner over redacted/fixed moderation cases and shadow outcomes; keep the evaluation set outside runtime prompt paths.
- Accumulate at least 1,000 real double-reviewed samples with administrator decision, model decision, prompt/model version and permissible evidence offsets.
- Report severe-violation false-negative rate, override rate, parse/timeout/provider-failure rate, latency and token/cost distribution without user/content labels.
- Require a separately approved severe-miss threshold and stable operations before introducing an auto-pass feature flag.
- If approved, allow only low-risk auto-pass behind a kill switch and continue sampled human review. Automatic rejection remains out of scope.

**Commit boundaries:** evaluation data contract; offline runner; quality report; operational dashboard/alerts; optional separately approved feature-flag commit.

**Gate commands:**

```bash
./mvnw -Dtest=ModerationEvaluationRunnerTest,ModerationRedTeamTest test
./mvnw -Pevaluation -Dmoderation.dataset=/absolute/path/to/approved-redacted-dataset verify
./mvnw test
```

Expected: the report records at least 1,000 eligible double-reviewed samples, passes the separately approved severe-miss threshold, and shows no fail-open decision. If any evidence gate is absent or fails, leave automatic pass disabled; there is no schedule-based exception.

## Cross-stage merge and operations protocol

For every stage:

1. Run the stage's focused red/green tests while implementing each commit.
2. Run the full backend suite on isolated Testcontainers before the final stage commit.
3. Inspect `git diff --check`, `git status --short`, dependency tree and configuration for secrets.
4. Record image/model/plugin digests and test reports when the stage introduces an external runtime.
5. Merge schema expand before code that writes it, verify backfills before read cutover, and remove legacy writes only after cutover evidence.
6. Roll back with feature flags/alias switch/forward fix. Never drop new tables or erase audit evidence as a rollback mechanism.
7. Do not promote performance, Recall, citation, deletion-lag or moderation-quality claims until measured by the fixed evaluation gates.
