# Community Agent Stage B Revision, Moderation, and Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不依赖 Milvus、Ollama 或 Agent UI 的前提下，把文章正文拆成作者私有可变草稿与不可变提交修订，以 revision/hash 绑定审核，并用事务 Outbox、Inbox 和投影水位可靠驱动 Elasticsearch 与审核通知。

**Architecture:** MySQL 是 article、draft、revision、moderation job 和事件的唯一事实源。迁移严格执行 expand → SHADOW 双写 → 在线 backfill → VERIFY_FENCE 写栅栏下最终 backfill/verify → pointer-read cutover → revision-write cutover；兼容列只镜像当前公开 revision。RabbitMQ 允许重复投递，ES 是可重建投影，消费者在外部幂等副作用成功后才写 Inbox/watermark 并 ACK。

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, MyBatis-Plus 3.5.17, MySQL 8, RabbitMQ 3.13, Elasticsearch 8.4.1, Resilience4j, Micrometer, Testcontainers.

## Global Constraints

- 只修改后端模块化单体；本阶段不增加 Milvus SDK、Milvus/Ollama Compose 服务、向量表、RAG、Agent 对话、记忆或写作建议。
- 保持 Java 21、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 3.5.17；不引入 Flyway。
- 数据库变更使用 `docs/database/migrations/2026-08-10-article-revision-moderation-outbox.sql`，要求 MySQL 8 上可重复执行；只向前修复，回滚不 DROP 新表/列/数据。
- `article.author_id` 是作者列；不存在 `article.user_id`。`article_draft(article_id,user_id)` 必须组合外键引用 `article(id,author_id)`。
- `article_revision` 和 `article_moderation_attempt` append-only；生产应用账号只有 SELECT/INSERT，UPDATE/DELETE 只给迁移/依法清理账号。
- `article.latest_revision_id/pending_revision_id/published_revision_id` 通过 `(revision_id,article_id) -> article_revision(id,article_id)` 组合外键保证不会跨文章指针。
- `article.content`（以及兼容 title/summary/cover）在 cutover 后只镜像 `published_revision_id`；不得镜像 mutable draft、pending revision 或 rejected revision。
- 公众详情/列表/搜索/推荐候选只读取当前 `published_revision_id`；作者编辑只读取本人的 `article_draft`。
- cutover 前禁止“已发布文章继续编辑”的新语义；只有 pointer-read 已验证后才能启用 revision-write。
- 每个审核决定同时 CAS job、article、revision id 和 content hash；模型和人工都不能把旧 revision 的结论应用到新 revision。
- 首发保持 shadow moderation：任何模型结果最终都进入 `HUMAN_PENDING`；本阶段不自动通过、不自动拒绝。
- `domain_event_outbox.id` 必须是 `BIGINT AUTO_INCREMENT PRIMARY KEY`，`event_id BINARY(16)` 必须唯一；不要把 UUID 当聚簇主键。
- Dispatcher 使用稳定 `id` 顺序、短事务租约和 `FOR UPDATE SKIP LOCKED`；Rabbit confirm 后才标记 PUBLISHED，confirm 后落库前崩溃允许重复发布。
- 对外部投影的顺序固定：投影租约/版本检查 → MySQL 回源 → 幂等 ES upsert/delete → 本地事务写 Inbox/watermark → ACK。严禁先写 Inbox 再调用 ES。
- 通知是 append-only delivery，只用 eventId Inbox + `message.source_event_id` 唯一键；ES 是 current-state projection，使用 aggregate watermark/tombstone。
- 所有 Stage B 集成边界使用真实 MySQL 8、RabbitMQ 和 Elasticsearch Testcontainers；不以 mock 代替迁移、confirm、重复投递、乱序或投影测试。

---

## Deployment state machine

实现一个单值配置而不是一组可能互相矛盾的布尔开关：

```java
public enum ArticleRevisionMode {
    LEGACY,       // 默认；只运行旧读写，Stage B 新路径不改变业务结果
    SHADOW,       // 同事务镜像 draft/revision/job/outbox；公众仍走旧读，禁止编辑已发布文章
    VERIFY_FENCE, // 公众仍走旧读；短暂阻止全部文章写，执行最终 backfill + 100% verify
    POINTER_READ, // 短暂切换窗口；公众只读 pointer，所有文章写操作返回 503
    CUTOVER       // 公众读 published revision，作者读写 draft，提交冻结 revision
}
```

配置为 `metro.article.revision-mode=${METRO_ARTICLE_REVISION_MODE:LEGACY}`。生产只允许按 `LEGACY -> SHADOW -> VERIFY_FENCE -> POINTER_READ -> CUTOVER` 前进；降级可从 SHADOW 回 LEGACY。Backfill 只能在 SHADOW 已经对所有 legacy 写事务进行同事务双写后启动；最终 verifier 只能在 VERIFY_FENCE 阻断文章写时给出 promotion PASS。进入 POINTER_READ 后，旧二进制不再是安全回滚路径；只能关闭文章写入并部署 forward fix。

**可执行任务顺序固定为 `1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8`：Schema → 通用 Outbox → SHADOW 双写 → Backfill/Verify → 读分流 → 投影 → 模型影子审核 → 人工 CAS/Cutover。不得在 Task 3 SHADOW 双写上线前运行 Task 4 BACKFILL。**

## Locked file and interface map

| Boundary | Exact package/files | Responsibility |
| --- | --- | --- |
| Revision mode | `article/config/ArticleRevisionProperties.java`, `ArticleRevisionMode.java` | one valid rollout state |
| Immutable content | `article/model/ArticleDraft.java`, `ArticleRevision.java`, `ArticleContentSnapshot.java` | mutable draft vs append-only snapshot |
| Migration | `article/migration/StageBArticleMigrationService.java`, `StageBArticleMigrationVerifier.java` | deterministic keyset backfill and 100% verification |
| Draft/submission | `article/service/ArticleDraftService.java`, `ArticleSubmissionService.java` | owner CAS and frozen revision/job creation |
| Events | `event/domain/*`, `event/outbox/*` | typed envelope, transactional append, confirm-aware dispatch |
| Projection consistency | `event/projection/*` | Inbox, per-aggregate lease/watermark and tombstone |
| ES projection | `article/projection/ArticleSearchProjectionConsumer.java` | converge ES to MySQL published pointer |
| Notification projection | `article/projection/ArticleModerationNotificationConsumer.java` | one durable message per event id |
| Moderation | `ai/moderation/revision/*` | frozen input, strict JSON, bounded shadow worker |
| Admin CAS | `ai/moderation/web/ModerationAdminController.java`, `ArticleModerationDecisionService.java` | versioned human decision |

## Canonical article content hash

All writers, backfill, verifier, jobs and projections use one implementation and one version. Do not hash Java object serialization.

```text
sha256(
  "article-content-v1\n" +
  len(title) + ":" + title + "\n" +
  len(summary) + ":" + summary + "\n" +
  len(bodyMarkdown) + ":" + bodyMarkdown + "\n" +
  len(cover) + ":" + cover + "\n" +
  len(canonicalTagsJson) + ":" + canonicalTagsJson
)
```

Null becomes empty UTF-8 text; line endings become LF; tags are trimmed, de-duplicated, sorted by Unicode code point and encoded as a compact JSON array. `body_plain` is derived for display/search and is not separately hashed. Hashes are lowercase 64-character hex.

---

### Task 1: Expand the schema without changing runtime behavior

**Files:**

- Create: `docs/database/migrations/2026-08-10-article-revision-moderation-outbox.sql`
- Create: `docs/database/operations/2026-08-10-stage-b-immutable-table-grants.sql`
- Create: `docs/database/operations/2026-08-10-stage-b-schema-expand-runbook.md`
- Modify: `script.sql`
- Modify: `src/main/java/cumt/zongzuo/community/entity/Article.java`
- Create: `src/main/java/cumt/zongzuo/community/article/model/ArticleDraft.java`
- Create: `src/main/java/cumt/zongzuo/community/article/model/ArticleRevision.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/moderation/revision/ArticleModerationJob.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/moderation/revision/ArticleModerationAttempt.java`
- Create mappers beside each model under `article/persistence` and `ai/moderation/revision`
- Create: `src/test/java/cumt/zongzuo/community/article/migration/ArticleRevisionSchemaIntegrationTest.java`

**Interfaces:** This task only exposes typed rows/mappers. It does not change `ArticleServiceImpl`, listeners, public queries or existing audit endpoints. New revision pointer/state/version fields on `Article` are internal (`@JsonIgnore`) and excluded from generic MyBatis-Plus `updateById`; later lifecycle code updates them only through explicit CAS statements.

- [ ] **Step 1: Write the failing real-MySQL schema contract**

Start a dedicated `mysql:8.0` container, create the exact legacy `article`, `message` and dependency tables, execute the migration twice with `ResourceDatabasePopulator`, and assert:

```java
assertThat(column("domain_event_outbox", "id")).satisfies(c -> {
    assertThat(c.type()).isEqualTo("bigint");
    assertThat(c.autoIncrement()).isTrue();
});
assertThat(uniqueColumns("domain_event_outbox", "uk_domain_event_id"))
        .containsExactly("event_id");
assertThat(foreignKeyColumns("article_draft", "fk_article_draft_owner"))
        .containsExactly(tuple("article_id", "id"), tuple("user_id", "author_id"));
assertThat(foreignKeyColumns("article", "fk_article_published_revision"))
        .containsExactly(tuple("published_revision_id", "id"), tuple("id", "article_id"));
assertThat(allTableCountsAfterSecondRun()).isEqualTo(allTableCountsAfterFirstRun());
```

Also assert no foreign key references an `article.user_id` column, all pointer columns remain nullable, and inserting a pointer to another article's revision fails. Add interruption recovery cases: execute only the migration statements through the third article column, restart with the full migration, and reach the identical schema; separately pre-create one expected index and one pointer FK, rerun, and prove no duplicate. If an existing same-name column/index/FK has incompatible columns, order or type, fail with `SCHEMA_DRIFT` instead of silently accepting it.

Use a real long transaction to hold the `article` metadata lock after the partial prefix. The full migration must fail within the bounded session `lock_wait_timeout`; after releasing the transaction, rerunning the complete file must recover the same exact target. Execute the rendered grant template against real MySQL principals too: global/schema `UPDATE`, `DELETE`, or `ALL PRIVILEGES` must fail closed, while the dedicated role must allow real SELECT/INSERT and deny real UPDATE/DELETE on both append-only tables.

- [ ] **Step 2: Run the schema test RED**

Run: `./mvnw -Dtest=ArticleRevisionSchemaIntegrationTest test`

Expected: FAIL because the migration and Stage B tables do not exist.

- [ ] **Step 3: Add exact additive DDL**

The migration must use a dedicated connection, capture the previous session `lock_wait_timeout`, set a short bounded value before any DDL, and restore it after a successful run. It uses literal `information_schema` guards plus `PREPARE/EXECUTE/DEALLOCATE` for every ALTERed column, index and FK. The unguarded DDL below is the **fresh-install `script.sql` target only** and must not be pasted as the forward migration. The forward file then sets `@schema_name=DATABASE()` and repeats this executable form for each object:

```sql
SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=@schema_name AND table_name='article'
     AND column_name='latest_revision_id') = 0,
  'ALTER TABLE article ADD COLUMN latest_revision_id BIGINT NULL',
  'SELECT 1');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema=@schema_name AND table_name='article'
     AND index_name='uk_article_id_author') = 0,
  'CREATE UNIQUE INDEX uk_article_id_author ON article(id,author_id)',
  'SELECT 1');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.referential_constraints
   WHERE constraint_schema=@schema_name AND table_name='article'
     AND constraint_name='fk_article_latest_revision') = 0,
  'ALTER TABLE article ADD CONSTRAINT fk_article_latest_revision
     FOREIGN KEY(latest_revision_id,id)
     REFERENCES article_revision(id,article_id) ON DELETE RESTRICT',
  'SELECT 1');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;
```

The migration contains one literal block of that form for this complete manifest; no `ALTER ... ADD` outside a guard is permitted:

```text
COLUMNS
article.latest_revision_id BIGINT NULL
article.pending_revision_id BIGINT NULL
article.published_revision_id BIGINT NULL
article.visibility_state VARCHAR(24) NULL
article.review_state VARCHAR(24) NULL
article.lifecycle_epoch BIGINT NOT NULL DEFAULT 1
article.lock_version BIGINT NOT NULL DEFAULT 0
message.source_event_id BINARY(16) NULL

INDEXES (validate uniqueness and ordered columns, not name alone)
article.uk_article_id_author UNIQUE(id,author_id)
article.idx_article_latest_pointer (latest_revision_id,id)
article.idx_article_pending_pointer (pending_revision_id,id)
article.idx_article_published_pointer (published_revision_id,id)
message.uk_message_source_event UNIQUE(source_event_id)
article_draft.PRIMARY(article_id)
article_draft.uk_article_draft_owner UNIQUE(article_id,user_id)
article_revision.PRIMARY(id)
article_revision.uk_article_revision_no UNIQUE(article_id,revision_no)
article_revision.uk_article_revision_identity UNIQUE(id,article_id)
article_moderation_job.PRIMARY(id)
article_moderation_job.uk_article_moderation_revision UNIQUE(article_id,revision_id)
article_moderation_job.uk_article_moderation_identity UNIQUE(id,article_id)
article_moderation_job.idx_moderation_queue (state,next_attempt_at,id)
article_moderation_attempt.PRIMARY(id)
article_moderation_attempt.uk_moderation_attempt UNIQUE(job_id,attempt_no)
article_revision_migration_issue.PRIMARY(id)
article_revision_migration_issue.uk_revision_migration_issue UNIQUE(article_id,issue_code)
article_revision_migration_issue.idx_revision_migration_unresolved (resolved_at,article_id)
domain_event_outbox.PRIMARY(id)
domain_event_outbox.uk_domain_event_id UNIQUE(event_id)
domain_event_outbox.uk_domain_event_dedupe UNIQUE(dedupe_key)
domain_event_outbox.idx_domain_outbox_dispatch (state,next_attempt_at,id)
consumer_inbox.PRIMARY(consumer_name,event_id)
projection_watermark.PRIMARY(consumer_name,aggregate_type,aggregate_id)
projection_watermark.idx_projection_lease (lease_until)

FOREIGN KEYS (validate child and referenced ordered columns)
fk_article_draft_owner: article_draft(article_id,user_id) -> article(id,author_id)
fk_article_revision_article: article_revision(article_id) -> article(id)
fk_article_revision_creator: article_revision(article_id,created_by) -> article(id,author_id)
fk_moderation_revision: article_moderation_job(revision_id,article_id) -> article_revision(id,article_id)
fk_attempt_job: article_moderation_attempt(job_id) -> article_moderation_job(id)
fk_revision_migration_article: article_revision_migration_issue(article_id) -> article(id)
fk_article_latest_revision: article(latest_revision_id,id) -> article_revision(id,article_id)
fk_article_pending_revision: article(pending_revision_id,id) -> article_revision(id,article_id)
fk_article_published_revision: article(published_revision_id,id) -> article_revision(id,article_id)
```

`CREATE TABLE IF NOT EXISTS` is safe because a MySQL CREATE TABLE statement is atomic. After every CREATE, the migration still verifies every listed column/index/FK definition through `information_schema`; absent indexes/FKs are added with guarded PREPARE blocks, while incompatible existing definitions abort as schema drift. Fresh-table definitions are:

```sql
ALTER TABLE article
  ADD COLUMN latest_revision_id BIGINT NULL,
  ADD COLUMN pending_revision_id BIGINT NULL,
  ADD COLUMN published_revision_id BIGINT NULL,
  ADD COLUMN visibility_state VARCHAR(24) NULL,
  ADD COLUMN review_state VARCHAR(24) NULL,
  ADD COLUMN lifecycle_epoch BIGINT NOT NULL DEFAULT 1,
  ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uk_article_id_author ON article(id, author_id);

CREATE TABLE IF NOT EXISTS article_draft (
  article_id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  draft_version BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL,
  summary VARCHAR(255) NULL,
  body_markdown MEDIUMTEXT NULL,
  body_plain MEDIUMTEXT NULL,
  cover VARCHAR(255) NULL,
  tags_json JSON NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_article_draft_owner(article_id,user_id),
  CONSTRAINT fk_article_draft_owner FOREIGN KEY(article_id,user_id)
    REFERENCES article(id,author_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS article_revision (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  article_id BIGINT NOT NULL,
  revision_no BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL,
  summary VARCHAR(255) NULL,
  body_markdown MEDIUMTEXT NULL,
  body_plain MEDIUMTEXT NULL,
  cover VARCHAR(255) NULL,
  tags_json JSON NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_draft_version BIGINT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_article_revision_no(article_id,revision_no),
  UNIQUE KEY uk_article_revision_identity(id,article_id),
  CONSTRAINT fk_article_revision_article FOREIGN KEY(article_id)
    REFERENCES article(id) ON DELETE RESTRICT,
  CONSTRAINT fk_article_revision_creator FOREIGN KEY(article_id,created_by)
    REFERENCES article(id,author_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS article_moderation_job (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  article_id BIGINT NOT NULL,
  revision_id BIGINT NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(24) NOT NULL,
  model_decision VARCHAR(16) NULL,
  risk_score DECIMAL(6,5) NULL,
  policy_hits_json JSON NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NULL,
  lease_owner VARCHAR(96) NULL,
  lease_until DATETIME(6) NULL,
  last_error VARCHAR(500) NULL,
  reviewer_id BIGINT NULL,
  review_reason VARCHAR(500) NULL,
  reviewed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_article_moderation_revision(article_id,revision_id),
  UNIQUE KEY uk_article_moderation_identity(id,article_id),
  INDEX idx_moderation_queue(state,next_attempt_at,id),
  CONSTRAINT fk_moderation_revision FOREIGN KEY(revision_id,article_id)
    REFERENCES article_revision(id,article_id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS article_moderation_attempt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  provider VARCHAR(32) NULL,
  model VARCHAR(96) NULL,
  prompt_version VARCHAR(32) NOT NULL,
  input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  structured_output_json JSON NULL,
  latency_ms BIGINT NOT NULL,
  token_usage_json JSON NULL,
  finish_reason VARCHAR(32) NULL,
  error_code VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_moderation_attempt(job_id,attempt_no),
  CONSTRAINT fk_attempt_job FOREIGN KEY(job_id)
    REFERENCES article_moderation_job(id) ON DELETE RESTRICT
);
```

Add the three article pointer composite FKs only after `article_revision` exists. Also create the Task 2 event tables and the durable migration report:

```sql
CREATE TABLE IF NOT EXISTS article_revision_migration_issue (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  article_id BIGINT NOT NULL,
  issue_code VARCHAR(64) NOT NULL,
  observed_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  details_json JSON NOT NULL,
  detected_at DATETIME(6) NOT NULL,
  resolved_at DATETIME(6) NULL,
  resolution_note VARCHAR(500) NULL,
  UNIQUE KEY uk_revision_migration_issue(article_id,issue_code),
  INDEX idx_revision_migration_unresolved(resolved_at,article_id),
  CONSTRAINT fk_revision_migration_article FOREIGN KEY(article_id)
    REFERENCES article(id) ON DELETE RESTRICT
);
```

Add nullable `message.source_event_id BINARY(16)` plus unique key `uk_message_source_event`; legacy notification rows keep null and remain valid. Mirror the final fresh-install schema in `script.sql`.

The grants script never attempts a table `REVOKE` that can neither override inherited schema/global grants nor safely handle an absent direct grant. It is a credential-free operator template for a newly provisioned dedicated principal and dedicated role. Before granting the role it fails closed on effective global/schema UPDATE/DELETE (including expanded `ALL PRIVILEGES` rows), direct table/column immutable mutation grants, or an unapproved inherited role. The controlled role receives only SELECT/INSERT on `article_revision` and `article_moderation_attempt`, is made the principal's default role, and its exact postcondition is verified. The accompanying runbook covers backup/restore evidence, table size, maintenance window, online-DDL rehearsal, `information_schema.innodb_trx`, `performance_schema.metadata_locks`, bounded-lock recovery, and rerun verification.

- [ ] **Step 4: Run GREEN and legacy startup checks**

Run:

```bash
./mvnw -Dtest=ArticleRevisionSchemaIntegrationTest,NoAiStartupIntegrationTest test
./mvnw -DskipTests compile
```

Expected: PASS; Stage B tables/FKs are correct and default LEGACY startup has unchanged behavior.

- [ ] **Step 5: Commit**

```bash
git add docs/database script.sql src/main/java/cumt/zongzuo/community/article \
  src/main/java/cumt/zongzuo/community/ai/moderation/revision \
  src/main/java/cumt/zongzuo/community/entity/Article.java \
  src/test/java/cumt/zongzuo/community/article/migration
git commit -m "feat(article): expand immutable revision schema"
```

---

### Task 2: Build the generic Outbox, Inbox, and projection lease foundation

**Files:**

- Create: `src/main/java/cumt/zongzuo/community/event/domain/DomainEvent.java`
- Create: `src/main/java/cumt/zongzuo/community/event/domain/DomainEventType.java`
- Create: `src/main/java/cumt/zongzuo/community/event/outbox/DomainEventOutbox.java`
- Create: `src/main/java/cumt/zongzuo/community/event/outbox/DomainEventOutboxMapper.java`
- Create: `src/main/java/cumt/zongzuo/community/event/outbox/DomainEventOutboxService.java`
- Create: `src/main/java/cumt/zongzuo/community/event/outbox/DomainEventOutboxDispatcher.java`
- Create: `src/main/java/cumt/zongzuo/community/event/outbox/OutboxLeaseLostException.java`
- Create: `src/main/java/cumt/zongzuo/community/event/projection/ConsumerInboxMapper.java`
- Create: `src/main/java/cumt/zongzuo/community/event/projection/ProjectionWatermarkMapper.java`
- Create: `src/main/java/cumt/zongzuo/community/event/projection/ProjectionLeaseService.java`
- Modify: `src/main/java/cumt/zongzuo/community/config/RabbitConfig.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/cumt/zongzuo/community/event/DomainEventOutboxIntegrationTest.java`
- Create: `src/test/java/cumt/zongzuo/community/event/ProjectionWatermarkIntegrationTest.java`

**Interfaces:**

```java
public record DomainEvent(UUID eventId, String aggregateType, long aggregateId,
        long aggregateVersion, long lifecycleEpoch, DomainEventType eventType,
        int payloadVersion, JsonNode payload, Instant occurredAt) {}
public interface DomainEventOutboxService {
    UUID append(String aggregateType, long aggregateId, long aggregateVersion,
            long lifecycleEpoch, DomainEventType type, int payloadVersion,
            JsonNode payload, String dedupeKey);
}
public interface ProjectionLeaseService {
    ProjectionLease acquire(String consumer, DomainEvent event, Duration lease);
    void complete(ProjectionLease lease, DomainEvent event, boolean tombstone, String resultHash);
}
```

`DomainEventOutboxMapper` terminal methods return affected-row counts and expose the lease owner in every signature:

```java
int markPublished(long id, String leaseOwner, Instant publishedAt);
int markRetry(long id, String leaseOwner, int retryCount, Instant nextAttemptAt, String error);
int markDead(long id, String leaseOwner, int retryCount, String error, Instant failedAt);
```

- [ ] **Step 1: Write RED tests against real MySQL/Rabbit**

Prove source transaction rollback leaves no Outbox row/message; two dispatcher instances claim disjoint rows with `SKIP LOCKED`; ordering is ascending id; mandatory publisher confirm is required; a simulated crash after confirm before `markPublished` produces a duplicate; expired leases recover; a nack/return schedules bounded retry. Race owner A's late confirm and late nack against lease recovery/owner B claim: owner A must update zero rows and must not overwrite B's state/retry metadata. Publish one `ARTICLE_REVISION_PUBLISHED` and one `ARTICLE_REVISION_REJECTED` through the real exchange and assert the search and notification queues each receive an envelope with the same original `event_id`. For projection consistency prove duplicates, older lifecycle, and same-lifecycle versions less than or equal to the watermark cannot run the external callback.

Use an external-effect probe table only in tests. Its callback performs `INSERT ... ON DUPLICATE KEY UPDATE`, then `ProjectionLeaseService.complete` writes Inbox/watermark. Crash between those two steps must replay the idempotent callback once more before Inbox is written.

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dtest=DomainEventOutboxIntegrationTest,ProjectionWatermarkIntegrationTest test`

Expected: FAIL because generic event infrastructure does not exist.

- [ ] **Step 3: Implement exact durable schemas and dispatcher**

Use these table contracts in the Task 1 SQL:

```sql
CREATE TABLE IF NOT EXISTS domain_event_outbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id BINARY(16) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  aggregate_version BIGINT NOT NULL,
  lifecycle_epoch BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_version INT NOT NULL,
  payload_json JSON NOT NULL,
  dedupe_key VARCHAR(190) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  lease_owner VARCHAR(96) NULL,
  lease_until DATETIME(6) NULL,
  last_error VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL,
  published_at DATETIME(6) NULL,
  UNIQUE KEY uk_domain_event_id(event_id),
  UNIQUE KEY uk_domain_event_dedupe(dedupe_key),
  INDEX idx_domain_outbox_dispatch(state,next_attempt_at,id)
);
CREATE TABLE IF NOT EXISTS consumer_inbox (
  consumer_name VARCHAR(96) NOT NULL,
  event_id BINARY(16) NOT NULL,
  processed_at DATETIME(6) NOT NULL,
  result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY(consumer_name,event_id)
);
CREATE TABLE IF NOT EXISTS projection_watermark (
  consumer_name VARCHAR(96) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  last_applied_version BIGINT NOT NULL DEFAULT 0,
  lifecycle_epoch BIGINT NOT NULL DEFAULT 0,
  tombstone TINYINT(1) NOT NULL DEFAULT 0,
  lease_owner VARCHAR(96) NULL,
  lease_until DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(consumer_name,aggregate_type,aggregate_id),
  INDEX idx_projection_lease(lease_until)
);
```

Claim at most 100 rows in one short transaction with `SELECT ... FOR UPDATE SKIP LOCKED`, set unique owner/lease and state IN_FLIGHT, commit, then publish outside the DB transaction. Mark PUBLISHED only after correlated confirm ACK and no returned message. `markPublished`, `markRetry` and `markDead` each use `WHERE id=? AND state='IN_FLIGHT' AND lease_owner=?`; affected rows zero throws/records `OUTBOX_LEASE_LOST` and the late completion must not mutate the row. Never store exception bodies; persist class plus sanitized 500-character message. Keep the existing stricter recommendation Outbox unchanged.

Declare a durable topic exchange `community.domain.events` and separate durable queues/DLQs. Bind the moderation queue to `article.revision.submitted`. Bind **both** the article-search queue and article-notification queue to the exact routing keys `article.revision.published` and `article.revision.rejected`; additionally bind search to `article.revision.superseded`, `article.unpublished` and `article.deleted`. A published/rejected Outbox row is published once with one routing key, and Rabbit fan-out places the exact same serialized `event_id` into both queues. Do not synthesize or republish a second “notification event”. Extend the Jackson converter allowlist only for the typed event DTO package.

Projection ordering rules are exact: `incoming.lifecycleEpoch < watermark.lifecycleEpoch` is stale; equal lifecycle with `incoming.aggregateVersion <= lastAppliedVersion` is stale. A higher lifecycle starts a new version line. Crucially, equal lifecycle with a strictly higher version is eligible even when the previous watermark is a tombstone; the consumer re-reads MySQL and may clear tombstone on a legal restore. Test delete v5 → tombstone, then restore v6 in the same lifecycle → idempotent upsert and `tombstone=false`; delete v5 followed by delayed publish v5/v4 remains blocked.

Default dispatcher batch is 100, lease 30 seconds, maximum 12 delivery attempts and exponential delay capped at 5 minutes; exhaustion becomes DEAD rather than an infinite hot loop. Dedupe keys use `aggregateType:aggregateId:lifecycleEpoch:aggregateVersion:eventType`.

- [ ] **Step 4: Run GREEN and regression**

Run:

```bash
./mvnw -Dtest=DomainEventOutboxIntegrationTest,ProjectionWatermarkIntegrationTest,RecommendationEventOutboxIntegrationTest test
```

Expected: PASS; new generic semantics do not weaken the existing recommendation Outbox.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/event src/main/java/cumt/zongzuo/community/config/RabbitConfig.java \
  src/main/resources/application.yml src/test/java/cumt/zongzuo/community/event
git commit -m "feat(events): add generic transactional outbox"
```

---

### Task 3: Enable SHADOW dual-write before starting backfill

**Files:**

- Create: `src/main/java/cumt/zongzuo/community/article/config/ArticleRevisionMode.java`
- Create: `src/main/java/cumt/zongzuo/community/article/config/ArticleRevisionProperties.java`
- Create: `src/main/java/cumt/zongzuo/community/article/model/ArticleContentSnapshot.java`
- Create: `src/main/java/cumt/zongzuo/community/article/service/ArticleContentCanonicalizer.java`
- Create: `src/main/java/cumt/zongzuo/community/article/service/ArticleDraftService.java`
- Create: `src/main/java/cumt/zongzuo/community/article/service/ArticleSubmissionService.java`
- Create: `src/main/java/cumt/zongzuo/community/article/persistence/ArticleDraftMapper.java`
- Create: `src/main/java/cumt/zongzuo/community/article/persistence/ArticleRevisionMapper.java`
- Create request/response records under: `src/main/java/cumt/zongzuo/community/article/web/`
- Modify: `src/main/java/cumt/zongzuo/community/service/impl/ArticleServiceImpl.java`
- Modify: `src/main/java/cumt/zongzuo/community/controller/ArticleController.java`
- Modify: `src/main/java/cumt/zongzuo/community/ai/moderation/LegacyModerationSubmissionConsumer.java`
- Create: `src/test/java/cumt/zongzuo/community/article/ArticleDraftRevisionIntegrationTest.java`

**Interfaces:**

```java
public record SaveArticleDraftCommand(Long articleId, long expectedDraftVersion,
        String title, String summary, String bodyMarkdown, String cover, List<String> tags) {}
public record SubmitArticleRevisionCommand(long articleId, long userId,
        long expectedDraftVersion) {}
public record SubmissionResult(long articleId, long revisionId, long revisionNo,
        long moderationJobId, String contentHash) {}
```

- [ ] **Step 1: Write RED draft/submission tests**

Cover new shell + draft creation, owner-scoped update, stale draft version 409, autosave changing only draft, submission freezing every field/tag/hash, mutation after submit not changing revision/job, second submission superseding the old non-terminal job, and cross-article pointer rejection. Test all four modes:

- LEGACY: existing behavior unchanged.
- SHADOW: dual records are written but public reads remain legacy; editing an already-published article is rejected.
- VERIFY_FENCE and POINTER_READ: every draft/publish/delete/restore/manual decision write returns 503 `ARTICLE_CUTOVER_IN_PROGRESS`.
- CUTOVER: draft and revision semantics are enabled.

Assert in CUTOVER that `article.content/title/summary/cover` stay equal to the old published revision while the draft changes. For never-published shells, compatible content is null and title is empty; private text exists only in `article_draft`.

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dtest=ArticleDraftRevisionIntegrationTest test`

Expected: FAIL because saves still overwrite `article.content` and submission has no frozen snapshot.

- [ ] **Step 3: Implement owner CAS and atomic submission**

Draft save locks/loads by `(article_id,user_id)`, checks `expectedDraftVersion`, canonicalizes content and performs one conditional update of `draft_version + lock_version`. Do not update `article_tag` during autosave; tags remain in draft JSON.

SHADOW must be production-capable before Task 4 backfill starts. Every legacy mutation—draft save, submit, legacy manual approve/reject, recycle, restore and scheduled cleanup—first locks the article and lazily creates/updates the canonical shadow snapshot in the same transaction. This covers a row that has not yet been reached by backfill. Both the legacy mutation and the draft/revision/job/Outbox mirror acquire the article row first, which serializes them with the backfill runner; public queries still use legacy fields/status so observed behavior is unchanged. Submission is one MySQL transaction:

1. `SELECT ... FOR UPDATE` article and owner draft.
2. Verify not deleted, exact draft version and canonical hash.
3. Allocate `revision_no = MAX(revision_no)+1` under the article lock and INSERT the immutable revision.
4. CAS every older non-terminal job for that article to SUPERSEDED and append one
   `ARTICLE_REVISION_SUPERSEDED` Outbox event whose sorted `supersededJobIds`,
   `supersededRevisionIds` and `supersededContentHashes` arrays describe the whole batch. This keeps
   the locked public event type while allocating one unique article aggregate version.
5. INSERT a job bound to `(articleId,revisionId,contentHash)`. If AI moderation is disabled/unavailable, initialize HUMAN_PENDING; otherwise initialize PENDING.
6. Update latest/pending pointers, review state and article lock version; do not change published pointer or compatible published fields. If an older published pointer exists, visibility/status remain PUBLIC/1 while the new review is pending; otherwise they are PRIVATE/2.
7. Append `ARTICLE_REVISION_SUBMITTED` in the same transaction.

The old `article.audit.queue` producer remains only in LEGACY. SHADOW/CUTOVER use Outbox; the unconditional Stage A listener may ACK legacy messages but must never decide a revision job.

- [ ] **Step 4: Run GREEN and transaction rollback tests**

Run:

```bash
./mvnw -Dtest=ArticleDraftRevisionIntegrationTest,ModerationFallbackIntegrationTest test
```

Expected: PASS; rolling back submission leaves no legacy mutation/revision/job/Outbox, concurrent SHADOW writes cannot escape the mirror, and Stage A legacy tests still pass in LEGACY mode.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/article src/main/java/cumt/zongzuo/community/controller/ArticleController.java \
  src/main/java/cumt/zongzuo/community/service/impl/ArticleServiceImpl.java \
  src/main/java/cumt/zongzuo/community/ai/moderation/LegacyModerationSubmissionConsumer.java \
  src/test/java/cumt/zongzuo/community/article
git commit -m "feat(article): freeze draft submissions as revisions"
```

---

### Task 4: Deterministically backfill and verify every legacy article under SHADOW/fence

**Files:**

- Reuse from Task 3: `src/main/java/cumt/zongzuo/community/article/model/ArticleContentSnapshot.java`
- Reuse from Task 3: `src/main/java/cumt/zongzuo/community/article/service/ArticleContentCanonicalizer.java`
- Create: `src/main/java/cumt/zongzuo/community/article/migration/StageBArticleMigrationService.java`
- Create: `src/main/java/cumt/zongzuo/community/article/migration/StageBArticleMigrationVerifier.java`
- Create: `src/main/java/cumt/zongzuo/community/article/migration/StageBMigrationRunner.java`
- Create: `src/main/java/cumt/zongzuo/community/article/migration/StageBMigrationReport.java`
- Create mapper SQL under: `src/main/java/cumt/zongzuo/community/article/persistence/ArticleMigrationMapper.java`
- Create: `src/test/java/cumt/zongzuo/community/article/migration/ArticleRevisionMigrationIntegrationTest.java`
- Create: `src/test/java/cumt/zongzuo/community/article/migration/ArticleRevisionBackfillRaceIntegrationTest.java`

**Interfaces:**

```java
public record ArticleContentSnapshot(String title, String summary, String bodyMarkdown,
        String bodyPlain, String cover, List<String> tags, String contentHash) {}
public interface StageBArticleMigrationService {
    MigrationBatchResult backfillAfter(long afterArticleId, int limit);
}
public interface StageBArticleMigrationVerifier {
    StageBMigrationReport verifyAll();
}
```

- [ ] **Step 1: Write the failing SHADOW-race/backfill/verify tests**

Seed legacy rows for statuses 0/1/2/3, deleted rows, tags, nullable bodies, one invalid status and legacy ES documents. Start SHADOW first, then race legacy save/submit transactions against backfill while both lock the same article row. For a status-0 autosave, assert the committed legacy value equals the current shadow draft regardless of lock winner. Revision 1 is the immutable `MIGRATION_BASELINE`: because every writer locks the article before applying its mutation, it deterministically freezes the pre-mutation legacy snapshot and remains self-consistent after the mutable draft advances. It has no latest/pending/published pointer; a later submission appends `MAX(revision_no)+1`. For status 1 the published baseline must equal the public mirror; status 2/3 use their frozen pending/rejected mapping. A first run creates deterministic revision 1/draft rows, status mappings and a pending moderation job; a second run creates no rows and changes no hashes/ids. Assert:

```text
status=0 -> PRIVATE / NOT_SUBMITTED / no latest,pending,published pointer
status=1 -> PUBLIC / APPROVED / latest=published=revision1
status=2 -> PRIVATE / AUTO_PENDING / latest=pending=revision1 + one HUMAN_PENDING legacy job
status=3 -> PRIVATE / REJECTED / latest=revision1, no pending/published
is_deleted=1 -> visibility RECYCLED; status-derived pointers remain for safe restore
```

Unknown status/delete flags are inserted into `article_revision_migration_issue` with only IDs/codes/hashes, not full content; the row is not guessed. Verification must fail while any issue is unresolved and must prove:

```text
article_count = migrated_draft_count + unresolved_issue_count
article_count = revision1_count + unresolved_issue_count
every migrated draft hash = freshly canonicalized current legacy snapshot hash
every revision hash = freshly canonicalized content stored in that immutable revision
status0 baseline owns the article, is pointer-free and may differ from a later mutable draft/legacy value
status1 published revision hash/content = current public legacy mirror
every pointer references its own article
every status2 row has exactly one job bound to revision/hash
every public legacy ES document equals the published revision; no non-public/deleted id exists
```

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dtest=ArticleRevisionMigrationIntegrationTest,ArticleRevisionBackfillRaceIntegrationTest test`

Expected: FAIL because no canonicalizer/backfill/verifier or SHADOW-race contract exists.

- [ ] **Step 3: Implement keyset backfill and operator-only runner**

Acquire a named MySQL advisory lock so only one operator backfill runner advances at once, then use `SELECT ... WHERE id > ? ORDER BY id LIMIT ? FOR UPDATE` in bounded transactions. Do not use SKIP LOCKED here: a crashed competing runner must not let a legacy row fall behind a persisted cursor. Restarting from zero is safe because every insert/update is idempotent. For each eligible row:

1. Load tags in stable order and construct `ArticleContentSnapshot` with the canonical v1 hash.
2. `INSERT ... ON DUPLICATE KEY` revision 1 and draft; an existing mismatched row creates `BACKFILL_MISMATCH` and is never overwritten.
3. Resolve the revision id and update only null pointers/states with the exact mapping above.
4. For status 2, insert one `(article_id,revision_id)` job with frozen `content_hash`, state HUMAN_PENDING and low-cardinality reason `LEGACY_BACKFILL_MANUAL`; do not silently send legacy content to a Provider during migration.
5. Record invalid legacy state as a migration issue; never silently coerce it.

`StageBMigrationRunner` is disabled unless `metro.migration.stage-b.action=BACKFILL|VERIFY`; it refuses BACKFILL unless current mode is SHADOW or VERIFY_FENCE, and refuses a promotion-grade VERIFY unless mode is VERIFY_FENCE. It exits non-zero for unresolved issues or any verifier mismatch. It is not an automatic startup migration. VERIFY captures fence start/end timestamps and proves no article `update_time`, draft version, revision or pointer changed inside the verification window.

- [ ] **Step 4: Prove idempotence and 100% verification**

Run:

```bash
./mvnw -Dtest=ArticleRevisionMigrationIntegrationTest,ArticleRevisionBackfillRaceIntegrationTest test
./mvnw -Dtest=ArticleRevisionSchemaIntegrationTest,ArticleRevisionMigrationIntegrationTest,ArticleRevisionBackfillRaceIntegrationTest test
```

Expected: PASS; the second run is byte-for-byte stable and verify refuses partial parity.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/article src/test/java/cumt/zongzuo/community/article/migration
git commit -m "feat(article): backfill and verify immutable revisions"
```

---

### Task 5: Cut public reads and author editing onto separate sources

**Files:**

- Modify: `src/main/java/cumt/zongzuo/community/mapper/ArticleMapper.java`
- Modify: `src/main/resources/mapper/ArticleMapper.xml`
- Create: `src/main/java/cumt/zongzuo/community/article/service/PublishedArticleReadService.java`
- Create: `src/main/java/cumt/zongzuo/community/article/service/AuthorArticleReadService.java`
- Modify: `src/main/java/cumt/zongzuo/community/service/ArticleService.java`
- Modify: `src/main/java/cumt/zongzuo/community/service/impl/ArticleServiceImpl.java`
- Modify: `src/main/java/cumt/zongzuo/community/controller/ArticleController.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationCandidateService.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationFeedService.java`
- Create: `src/test/java/cumt/zongzuo/community/article/ArticlePublishedPointerIntegrationTest.java`

**Interfaces:** public mapper methods return `Article` compatibility views whose content fields come from joined `article_revision`; author edit methods return draft content plus `draftVersion`. No public service may call `selectById` and then decide visibility in Java.

- [ ] **Step 1: Write a sentinel-leak RED suite**

For one article store four different sentinels in legacy mirror, published revision, mutable draft and pending revision. Assert `/detail`, hot/feed/user/follow/search/similar, chronological feed and personalized recommendation candidates expose only the published sentinel. Draft/edit/my-list endpoints for the owner expose the draft sentinel; another user gets owner hiding. Pending/rejected revisions never replace the old public revision.

Assert public detail cache keys bind `articleId + publishedRevisionId + contentHash`, and replacing the pointer cannot return the old cached body. Stale ES search/MLT ids must be rehydrated through MySQL; an ES id whose MySQL pointer is no longer public is discarded.

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dtest=ArticlePublishedPointerIntegrationTest,RecommendationRecallIntegrationTest test`

Expected: FAIL because most paths select `article.*` and status 1.

- [ ] **Step 3: Replace every public SQL predicate and projection**

All public queries use the same join/predicate:

```sql
JOIN article_revision r
  ON r.id=a.published_revision_id AND r.article_id=a.id
WHERE a.visibility_state='PUBLIC'
  AND a.is_deleted=0
```

Select `r.title`, `r.summary`, `r.body_markdown AS content`, `r.cover`, and carry `r.id AS published_revision_id`, `r.content_hash`. Preserve counters/author/create time from article. Replace QueryWrapper public reads in `ArticleServiceImpl`; update the existing recommendation mapper methods instead of adding a second eligibility implementation.

`/api/article/detail/{id}` becomes strictly public. Author/admin private inspection uses owner draft/admin moderation endpoints. `/edit/{id}`, drafts, recycle bin and my-list load through `(articleId,authorId)` draft queries. POINTER_READ uses only public pointer reads and blocks writes; CUTOVER additionally enables author draft reads/writes.

- [ ] **Step 4: Run GREEN and forbidden-query scan**

Run:

```bash
./mvnw -Dtest=ArticlePublishedPointerIntegrationTest,RecommendationRecallIntegrationTest,RecommendationFeedIntegrationTest test
if rg -n 'query\.eq\("status", 1\)|a\.status = 1' \
  src/main/java/cumt/zongzuo/community/service/impl/ArticleServiceImpl.java \
  src/main/resources/mapper/ArticleMapper.xml; then exit 1; fi
```

Expected: PASS; no public read derives body visibility from legacy status/content.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/article src/main/java/cumt/zongzuo/community/mapper/ArticleMapper.java \
  src/main/resources/mapper/ArticleMapper.xml src/main/java/cumt/zongzuo/community/service \
  src/main/java/cumt/zongzuo/community/controller/ArticleController.java \
  src/main/java/cumt/zongzuo/community/recommendation src/test/java/cumt/zongzuo/community/article
git commit -m "feat(article): read public content from published revisions"
```

---

### Task 6: Project current published pointers to Elasticsearch and notifications

**Files:**

- Modify: `src/main/java/cumt/zongzuo/community/document/ArticleDoc.java`
- Create: `src/main/java/cumt/zongzuo/community/article/projection/ArticleSearchProjectionConsumer.java`
- Create: `src/main/java/cumt/zongzuo/community/article/projection/ArticleModerationNotificationConsumer.java`
- Create: `src/main/java/cumt/zongzuo/community/article/projection/ArticleProjectionSource.java`
- Modify: `src/main/java/cumt/zongzuo/community/entity/Message.java`
- Modify: `src/main/java/cumt/zongzuo/community/mapper/MessageMapper.java`
- Modify: `src/main/java/cumt/zongzuo/community/mq/EsSyncConsumer.java`
- Create: `src/test/java/cumt/zongzuo/community/article/ArticleProjectionIntegrationTest.java`
- Create: `src/test/java/cumt/zongzuo/community/article/ArticleProjectionReplayRaceIntegrationTest.java`

**Interfaces:** `ArticleDoc` gains `revisionId` and `contentHash`. `ArticleProjectionSource.loadCurrent(articleId)` returns either a current authorized published snapshot or a tombstone; it never trusts event payload content.

- [ ] **Step 1: Write real ES/Rabbit RED tests**

Start only the named projection listeners. Cover publish, replacement, reject-with-old-public, unpublish, recycle, hard-delete tombstone, restore, duplicate delivery, v3-before-v2, lifecycle tombstone followed by stale publish, and ES success followed by simulated Inbox failure. Specifically apply delete version 5, persist `tombstone=true`, then apply a legal restore version 6 in the same lifecycle; it must re-read MySQL, upsert, advance to version 6 and clear tombstone. Delayed version 5/4 and any older lifecycle remain ignored. Assert final ES is always identical to current MySQL pointer and all stored docs contain revisionId/hash.

For notification, deliver the same approve/reject event twice and assert one `message` row by `source_event_id`; commit of the message precedes Inbox/ACK. A rejected new revision must not delete or alter the old ES public document.

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dtest=ArticleProjectionIntegrationTest,ArticleProjectionReplayRaceIntegrationTest test`

Expected: FAIL because the current ES consumer accepts only a naked article id and no Inbox/watermark exists.

- [ ] **Step 3: Implement source-revalidated idempotent projections**

Search consumer flow:

```java
ProjectionLease lease = leases.acquire(CONSUMER, event, Duration.ofSeconds(30));
if (lease.skip()) return;
PublishedSnapshot source = sourceReader.loadCurrent(event.aggregateId());
if (source.present()) articleRepository.save(source.toDocument());
else articleRepository.deleteById(event.aggregateId());
leases.complete(lease, event, !source.present(), source.resultHash());
// listener returns; AUTO ack now occurs
```

If ES succeeds but `complete` fails, throw; after lease expiry Rabbit replays the idempotent save/delete, then records Inbox/watermark. Older lifecycle and equal-lifecycle version less than or equal to the watermark do not call ES; equal-lifecycle higher versions are eligible even after tombstone and may clear it based on current MySQL truth. Do not log article body.

Notification consumer handles only human-approved/human-rejected event types. It rehydrates job/revision/author, inserts `message(source_event_id,...) ON DUPLICATE KEY UPDATE source_event_id=source_event_id`, then inserts Inbox in the same local transaction. It does not use projection watermark because notifications are event facts, not current-state materialization.

In SHADOW/CUTOVER, disable the naked-id `EsSyncConsumer` for migrated article lifecycle events; retain it only for LEGACY and for existing non-migrated producers until their callers are removed.

- [ ] **Step 4: Run GREEN and search regressions**

Run:

```bash
./mvnw -Dtest=ArticleProjectionIntegrationTest,ArticleProjectionReplayRaceIntegrationTest,RecommendationRecallIntegrationTest test
```

Expected: PASS; duplicate/out-of-order events converge and stale projection data never becomes authorization truth.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/article/projection src/main/java/cumt/zongzuo/community/document \
  src/main/java/cumt/zongzuo/community/entity/Message.java src/main/java/cumt/zongzuo/community/mapper/MessageMapper.java \
  src/main/java/cumt/zongzuo/community/mq/EsSyncConsumer.java src/test/java/cumt/zongzuo/community/article
git commit -m "feat(article): project published revision events"
```

---

### Task 7: Run revision-bound structured moderation in shadow mode

**Files:**

- Create under `src/main/java/cumt/zongzuo/community/ai/moderation/revision/`:
  - `ModerationDecision.java`, `ModerationCategory.java`, `ModerationEvidence.java`
  - `ModerationModelOutput.java`, `ModerationChunk.java`, `ModerationAggregate.java`
  - `ModerationPromptFactory.java`, `ModerationChunker.java`, `ModerationOutputParser.java`
  - `ArticleModerationJobMapper.java`, `ArticleModerationAttemptMapper.java`
  - `ArticleModerationStateMachine.java`, `ArticleModerationWorker.java`
- Create: `src/main/java/cumt/zongzuo/community/ai/moderation/revision/ArticleModerationEventConsumer.java`
- Modify: `src/main/java/cumt/zongzuo/community/ai/config/MetroAiProperties.java`
- Modify: `src/main/java/cumt/zongzuo/community/ai/provider/DeepSeekAiChatGateway.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/moderation/ArticleModerationStateMachineTest.java`
- Create: `src/test/java/cumt/zongzuo/community/ai/moderation/ArticleModerationIntegrationTest.java`

**Interfaces:**

```java
public record ModerationModelOutput(
    ModerationDecision decision,
    Set<ModerationCategory> categories,
    @Min(0) @Max(4) int severity,
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
    List<ModerationEvidence> evidenceOffsets,
    @Size(max=500) String reason,
    String model,
    String promptVersion) {}
```

Jackson parsing uses a dedicated strict reader with unknown-property rejection. Provider output is accepted only when `finishReason` is a non-truncated success and model/promptVersion match the request.

- [ ] **Step 1: Write RED parser/state/worker tests**

Unit cases: blank/truncated JSON, unknown field/category, invalid enum/range/evidence offset, mismatched model/prompt version, low confidence, prompt injection text, empty content, chunk cap, whole-task token cap and conflicting chunks. Integration cases use a local controlled DeepSeek HTTP stub plus real MySQL/Rabbit and cover timeout, 429/5xx exhaustion, missing key/disabled capability, revision/hash mismatch before call, pointer change during call, lease loss and late response.

Every uncertain case must finish `HUMAN_PENDING`; no case changes `published_revision_id` or compatible public fields. Valid PASS/REVIEW/REJECT outputs are saved as model evidence but also finish HUMAN_PENDING in shadow mode.

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dtest=ArticleModerationStateMachineTest,ArticleModerationIntegrationTest test`

Expected: FAIL because no revision-bound model worker exists.

- [ ] **Step 3: Implement bounded, tool-free shadow moderation**

Claim jobs with one CAS from PENDING/RETRY_WAIT to RUNNING plus owner/lease. Before every Provider call and after its result, reload and compare:

```text
job.article_id == revision.article_id == article.id
job.revision_id == article.pending_revision_id
job.content_hash == revision.content_hash == freshly computed snapshot hash
lease owner/time and job lock version still match
```

Use deterministic sensitive rules first. Chunk with Spring AI `JTokkitTokenCountEstimator` as a conservative budget estimator, retain headings/overlap, and enforce configurable maximum chunks/estimated tokens. Each call is `AiCapability.MODERATION`, `AiResponseMode.JSON_OBJECT`, no tools, and an absolute deadline capped by the 20-second capability/90-second task budgets. Extend `DeepSeekAiChatGateway.requestOptions(command)` so MODERATION alone sets temperature 0.0 while model and JSON-object mode remain typed options. Do not add a second provider retry loop around `AiCapabilityExecutor`; Task 5 runtime already owns at most three background attempts.

Persist each append-only attempt with input hash, sanitized structured result or error code, tokens and latency. Aggregate highest risk; any contradiction/uncertainty wins HUMAN_PENDING. A valid aggregate records `model_decision/risk_score/policy_hits_json`; its MODEL_* transition and required HUMAN_PENDING transition occur in the same MySQL transaction, so no committed window can expose a model terminal state as publish authority.

- [ ] **Step 4: Run GREEN with disabled/no-Key gates**

Run:

```bash
./mvnw -Dtest=ArticleModerationStateMachineTest,ArticleModerationIntegrationTest,NoAiStartupIntegrationTest test
```

Expected: PASS; provider-off paths remain manual and model output has zero publish/reject authority.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/ai/moderation src/main/java/cumt/zongzuo/community/ai/config/MetroAiProperties.java \
  src/test/java/cumt/zongzuo/community/ai/moderation
git commit -m "feat(moderation): bind shadow review to immutable revisions"
```

---

### Task 8: Add double-object admin CAS and execute the cutover gate

**Files:**

- Create: `src/main/java/cumt/zongzuo/community/ai/moderation/web/ModerationAdminController.java`
- Create request/response DTOs in: `src/main/java/cumt/zongzuo/community/ai/moderation/web/`
- Create: `src/main/java/cumt/zongzuo/community/ai/moderation/revision/ArticleModerationDecisionService.java`
- Modify: `src/main/java/cumt/zongzuo/community/controller/ArticleController.java`
- Modify: `src/main/java/cumt/zongzuo/community/service/impl/ArticleServiceImpl.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-example.yml`
- Modify: `.env.example`
- Modify: `README.md`
- Create: `src/main/java/cumt/zongzuo/community/event/outbox/DomainEventRetentionTask.java`
- Create: `src/test/java/cumt/zongzuo/community/event/DomainEventRetentionIntegrationTest.java`
- Create: `docs/database/operations/2026-08-10-stage-b-cutover-runbook.md`
- Create: `src/test/java/cumt/zongzuo/community/ai/moderation/ArticleModerationAdminIntegrationTest.java`
- Create: `src/test/java/cumt/zongzuo/community/article/ArticleRevisionCutoverIntegrationTest.java`

**Interfaces:**

```java
public record ModerationDecisionRequest(long revisionId, long expectedJobVersion,
        long expectedArticleVersion, String reason) {}
public record ModerationJobResponse(long id, long articleId, long revisionId,
        String contentHash, String state, long jobVersion, long articleVersion,
        Long currentPublishedRevisionId) {}
```

Controller is `@AiApi`, mapped to `/api/admin/moderation`, and uses Stage A ProblemDetail/security. The old `/api/article/admin/audit` is allowed only in LEGACY; in SHADOW/POINTER_READ/CUTOVER it cannot make a decision.

- [ ] **Step 1: Write RED CAS and cutover tests**

Use real JWT admin/user accounts. Assert list/detail include frozen revision and both versions; normal user gets 403 ProblemDetail. Run concurrent approve/reject and stale job/article versions: exactly one wins, loser gets 409, and only the winner changes pointers/writes Outbox. Assert cross-article revision IDs, changed hash, superseded job, deleted article and non-HUMAN_PENDING job never decide.

Approval must replace published pointer, clear pending, preserve draft, mirror the approved revision to legacy fields/tags, set visibility PUBLIC/status 1, and append one `ARTICLE_REVISION_PUBLISHED` event atomically; Rabbit fan-out drives both search and notification consumers. Rejection clears only the matching pending pointer, preserves an old published pointer/mirror/ES doc (status remains 1), and appends one `ARTICLE_REVISION_REJECTED` event; without an older publication it becomes PRIVATE/status 3. Transaction rollback produces neither pointer change nor event.

Cutover test exercises `LEGACY -> SHADOW -> VERIFY_FENCE -> POINTER_READ -> CUTOVER`, proves BACKFILL is rejected before SHADOW, proves promotion VERIFY is rejected outside VERIFY_FENCE, verifies all writes are blocked during the fence, and proves published editing is enabled only in CUTOVER.

- [ ] **Step 2: Run RED**

Run: `./mvnw -Dtest=ArticleModerationAdminIntegrationTest,ArticleRevisionCutoverIntegrationTest test`

Expected: FAIL because the revision-aware admin API and promotion gate do not exist.

- [ ] **Step 3: Implement locked decision transaction and lifecycle events**

Decision transaction locks job, article and revision in stable order, then checks state, both expected versions, revision ownership and exact hash. Approve/reject use conditional updates with all checked columns in `WHERE`; affected row count zero is 409. Only the winner writes Outbox. Copy to legacy mirror and replace `article_tag` only on human approval.

Convert recycle/unpublish/restore/hard-delete paths and the seven-day recycle cleaner to pointer-aware lifecycle events. A destructive transition increments lifecycle epoch once and writes the tombstone event. A legal restore keeps that current lifecycle epoch, increments article aggregate/lock version and writes the higher-version restore/published event, allowing the projection to clear tombstone; it never reuses a pre-delete lifecycle. “Hard delete” becomes a durable tombstone (`is_deleted=1`, `ARTICLE_DELETED`) until projection verification/retention cleanup; do not physically delete revision/audit truth in the request transaction or scheduled cleaner. Restore publishes only an existing approved pointer; otherwise returns to PRIVATE.

Add bounded retention: delete PUBLISHED Outbox rows older than 7 days, Inbox rows older than 30 days, and resolved DEAD/operator records only after 90 days; batch by primary key and never delete a currently leased row. Expose low-cardinality pending-age/dead counters. Retention does not delete revisions, attempts or unresolved migration issues.

The runbook gives exact promotion sequence:

1. backup; run additive SQL twice, including a tested prefix-interruption recovery;
2. deploy the new binary in LEGACY and prove ordinary regression;
3. set SHADOW so every new legacy mutation is transactionally dual-written; keep published editing disabled and observe Outbox/job lag;
4. while SHADOW remains active, run online BACKFILL until unresolved issues are zero;
5. pause article writes by setting VERIFY_FENCE; run the final BACKFILL sweep plus full count/hash/pointer/ES VERIFY and archive the report;
6. without reopening writes, set POINTER_READ and run public sentinel checks;
7. set CUTOVER; resume writes; run submit/edit/reject/approve smoke;
8. retain old columns as published mirrors; never start old binary after this point.

Rollback after CUTOVER is `POINTER_READ`/write pause + forward fix, not schema rollback or old binary.

- [ ] **Step 4: Run the full Stage B gate**

Run:

```bash
export JAVA_HOME=/Users/yangyiming/.sdkman/candidates/java/21.0.11-amzn
./mvnw -Dtest=ArticleRevisionSchemaIntegrationTest,ArticleRevisionMigrationIntegrationTest,ArticleRevisionBackfillRaceIntegrationTest test
./mvnw -Dtest=ArticleDraftRevisionIntegrationTest,ArticlePublishedPointerIntegrationTest test
./mvnw -Dtest=DomainEventOutboxIntegrationTest,ProjectionWatermarkIntegrationTest test
./mvnw -Dtest=ArticleProjectionIntegrationTest,ArticleProjectionReplayRaceIntegrationTest test
./mvnw -Dtest=ArticleModerationStateMachineTest,ArticleModerationIntegrationTest,ArticleModerationAdminIntegrationTest test
./mvnw -Dtest=DomainEventRetentionIntegrationTest test
./mvnw test
./mvnw -DskipTests package
git diff --check
if rg -n 'milvus|Milvus|bge-m3|ArticleVectorRepository|MemoryVectorRepository' \
  src/main/java docs/database/migrations/2026-08-10-article-revision-moderation-outbox.sql; then exit 1; fi
```

Expected: all tests/builds pass on Java 21; migration runs twice; no Stage C code exists; no model decision changes public state; duplicate/out-of-order events converge.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cumt/zongzuo/community/ai/moderation src/main/java/cumt/zongzuo/community/controller/ArticleController.java \
  src/main/java/cumt/zongzuo/community/service/impl/ArticleServiceImpl.java src/main/resources \
  .env.example README.md docs/database/operations src/test/java/cumt/zongzuo/community
git commit -m "feat(moderation): cut over revision-bound human decisions"
```

---

## Stage B release invariants

| Invariant | Evidence required before CUTOVER |
| --- | --- |
| Every non-conflicting legacy article has one revision 1 and one draft | full verifier counts; unresolved issue count zero |
| Public body is only current published revision | sentinel-leak suite across every public/search/recommendation endpoint |
| Draft autosave cannot change public pointer/mirror | draft/revision integration test plus SQL audit |
| Pending/rejected revision keeps prior publication | admin CAS + ES projection integration tests |
| Revision/job cannot cross article | composite FK and negative insert tests |
| Model cannot publish/reject | state-machine tests and no public-pointer update in model package |
| Source transaction and event are atomic | rollback Outbox test |
| Duplicate/late events converge | real Rabbit/ES replay race tests |
| Inbox never suppresses unfinished external side effect | ES-success/Inbox-failure replay test |
| Old binary is not claimed as post-cutover rollback | reviewed cutover runbook |

## Deferred explicitly to later stages

- Stage C: Milvus/Ollama services, article chunks, dense vectors, chunk-level ES alias and blue/green vector rebuild.
- Stage D: Agent profile/conversation/turn/SSE, planner, tools, RRF/HyDE and citations.
- Stage E: long-term memory, temporary sessions, deletion/export orchestration.
- Stage F: Tiptap writing suggestion diff.
- Stage G: moderation quality evaluation and any decision on optional low-risk auto-pass after at least 1,000 dual-reviewed samples.
