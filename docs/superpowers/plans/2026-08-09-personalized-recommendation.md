# Metro Community 个性化推荐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付“推荐”和“最新”两个独立首页入口：推荐页在真实数据达到双门槛后使用可验证的机器学习精排，其他状态安全回退到时间线；最新页始终按发布时间排序。

**Architecture:** 推荐作为单体内独立 `recommendation` 领域模块：原业务成功后投递幂等行为事件，冷启动推荐页的真实曝光会写入 MySQL；消费者维护 Redis 画像，训练任务从真实曝光和后续互动构造样本、验证并发布版本化 Logistic Regression 模型。请求侧从关注、标签、ES 相似和热度新鲜度四路召回，双门槛满足且模型有效时以模型精排，否则以时间线响应推荐页；最新页始终复用 `ArticleService#getFeedArticles`。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis-Plus、MySQL 8、Redis 7、RabbitMQ 3、Elasticsearch 8、JUnit 5、Testcontainers、Vue 3、Vite、Vitest。

## Global Constraints

- 不使用 mock 数据、AI Key、Embedding、向量数据库、深度学习框架、外部模型服务或新的推荐微服务；第一版机器学习为 Java 内实现的离线 Logistic Regression。
- 后端统一升级到 Java 21；Maven 编译、测试和本地运行均使用 `/Users/yangyiming/.sdkman/candidates/java/21.0.11-amzn` 或等价 JDK 21。
- 保留 `/api/article/feed`、`/api/article/hot-feed`、`/api/article/follow-feed` 和 `/api/article/{id}/similar` 的既有语义；详情相关推荐仍由 ES `more_like_this` 提供。
- 仅登录用户调用新推荐接口；未登录推荐页和所有用户的最新页继续请求现有时间线。首页必须展示独立的“推荐”和“最新”入口。
- 所有行为消息必须在原业务动作成功后发送；消费失败走现有 RabbitMQ retry/DLQ，不能影响点赞、收藏、评论和关注主请求。
- 行为与曝光事实仅追加到新表，不能回填、修改或删除既有业务表历史数据；`recommendation.enabled` 只控制推荐结果，不停止行为采集，关闭后推荐页必须立即走时间线，最新页不受该开关影响。
- 模型排序启用条件固定为：当前用户最近 30 天至少 20 条去重有效行为，并且全站最近 90 天至少 500 条去重有效行为；两者缺一不可。
- 训练数据只来自真实推荐曝光及其后 7 天内的有效阅读、点赞、收藏、评论或关注后的内容互动。验证集不优于规则基线的模型不得发布。
- 本地端口继续使用既有隔离配置：后端 18080、Redis 16379、RabbitMQ 15673、Elasticsearch 19200；不得占用其他项目的默认端口。
- 按 TDD 实施：每个任务先写红测，确认失败，再写最小实现；任务结束运行对应测试、完整测试和格式检查后提交。

---

## File Structure

### Backend: create

- `src/main/java/cumt/zongzuo/community/recommendation/config/RecommendationProperties.java`：特性开关、会话 TTL、候选和分页上限配置。
- `src/main/java/cumt/zongzuo/community/recommendation/entity/UserArticleEvent.java`：可审计的行为事实实体。
- `src/main/java/cumt/zongzuo/community/recommendation/entity/RecommendationExposure.java`：推荐页投递的真实曝光及特征快照。
- `src/main/java/cumt/zongzuo/community/recommendation/entity/RecommendationEventType.java`：五类行为及初始权重。
- `src/main/java/cumt/zongzuo/community/recommendation/mapper/UserArticleEventMapper.java`：行为事实 MyBatis-Plus mapper。
- `src/main/java/cumt/zongzuo/community/recommendation/mapper/RecommendationExposureMapper.java`：曝光 MyBatis-Plus mapper。
- `src/main/java/cumt/zongzuo/community/recommendation/entity/RecommendationEventOutbox.java`：与业务事务共同提交的待投递事件。
- `src/main/java/cumt/zongzuo/community/recommendation/mapper/RecommendationEventOutboxMapper.java`：Outbox 持久化与状态更新 mapper。
- `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationEventCommand.java`：跨 RabbitMQ 的不可变事件消息。
- `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationItem.java`：文章和可解释原因。
- `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationFeedResponse.java`：稳定游标分页响应。
- `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationSession.java`：Redis 中的用户绑定、有序会话。
- `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationMode.java`：`COLD_START`、`PERSONALIZED`、`FALLBACK` 响应模式。
- `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationViewRequest.java`：可选曝光 ID 的有效阅读请求体。
- `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationEventOutboxService.java`：在当前业务事务内追加待投递事件。
- `src/main/java/cumt/zongzuo/community/recommendation/task/RecommendationOutboxDispatcher.java`：带 publisher confirm 的异步投递与退避重试。
- `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationProfileService.java`：画像写入、重建及时间衰减。
- `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationCandidateService.java`：四路候选召回、资格过滤、文章投影填充。
- `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationRankingService.java`：得分、去重和多样性重排。
- `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationFeedService.java`：会话、游标、降级和指标计数。
- `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationEligibilityService.java`：用户和全站双门槛判断。
- `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationExposureService.java`：在推荐页交付后写入曝光和特征快照。
- `src/main/java/cumt/zongzuo/community/recommendation/training/RecommendationFeatureVector.java`：训练和推理共用的特征向量。
- `src/main/java/cumt/zongzuo/community/recommendation/training/LogisticRegressionTrainer.java`：批量梯度下降与 L2 正则训练器。
- `src/main/java/cumt/zongzuo/community/recommendation/training/RecommendationModel.java`：版本、均值、标准差、系数和验证指标。
- `src/main/java/cumt/zongzuo/community/recommendation/training/RecommendationModelStore.java`：模型 JSON 原子保存和只读加载。
- `src/main/java/cumt/zongzuo/community/recommendation/training/RecommendationTrainingService.java`：时间切分、基线比较和模型发布。
- `src/main/java/cumt/zongzuo/community/recommendation/task/RecommendationTrainingTask.java`：每日离线训练调度。
- `src/main/java/cumt/zongzuo/community/recommendation/mq/RecommendationEventConsumer.java`：幂等落库和 Redis 画像更新。
- `src/main/java/cumt/zongzuo/community/recommendation/controller/RecommendationController.java`：认证推荐与有效阅读接口。
- `src/main/java/cumt/zongzuo/community/recommendation/task/RecommendationMetricsTask.java`：每日指标日志。
- `src/test/java/cumt/zongzuo/community/recommendation/RecommendationPolicyTest.java`：纯规则单测。
- `src/test/java/cumt/zongzuo/community/recommendation/RecommendationEventIntegrationTest.java`：MySQL、Redis、RabbitMQ 幂等消费集成测。
- `src/test/java/cumt/zongzuo/community/recommendation/RecommendationFeedIntegrationTest.java`：认证、游标、冷启动、开关和 Redis 降级 API 测。
- `src/test/java/cumt/zongzuo/community/recommendation/LogisticRegressionTrainerTest.java`：可分样本、标准化、概率与 L2 训练单测。
- `src/test/java/cumt/zongzuo/community/recommendation/RecommendationTrainingIntegrationTest.java`：真实曝光样本、时间切分、基线门槛、版本模型和推理回退集成测。

### Backend: modify

- `script.sql`：追加 `user_article_event`、`recommendation_exposure` 表及索引，供本地和 `IntegrationTestSupport` 初始化。
- `src/main/java/cumt/zongzuo/community/config/RabbitConfig.java`：声明 `recommendation.event.queue` 及其死信队列、绑定。
- `src/main/resources/application.yml`：加入 `recommendation.*`、训练和模型目录默认配置。
- `src/main/java/cumt/zongzuo/community/service/impl/LikeServiceImpl.java`：文章点赞成功时投递 `LIKE`。
- `src/main/java/cumt/zongzuo/community/service/impl/FavoriteServiceImpl.java`：新增收藏时投递 `COLLECT`，取消收藏不投递。
- `src/main/java/cumt/zongzuo/community/service/impl/CommentServiceImpl.java`：评论保存成功时投递 `COMMENT`。
- `src/main/java/cumt/zongzuo/community/service/impl/FollowServiceImpl.java`：首次关注成功时投递 `FOLLOW_AUTHOR`。
- `src/test/java/cumt/zongzuo/community/security/SecurityIntegrationTest.java`：扩展队列声明和新接口认证断言。

### Frontend: create

- `src/api/recommendation.js`：推荐流和有效阅读 API 封装。
- `src/utils/qualifiedArticleView.js`：累计文档可见时间，满 8 秒仅上报一次的纯状态工具。
- `src/utils/qualifiedArticleView.test.js`：可见、隐藏、路由切换与一次性上报单测。
- `src/api/recommendation.test.js`：推荐响应转换和降级数据形状测试。

### Frontend: modify

- `src/views/Home.vue`：增加独立的“推荐”“最新”入口、独立游标；登录推荐页接入模型或冷启动响应，最新页永远调用时间线。
- `src/views/ArticleDetail.vue`：详情页真实可见累计 8 秒后发送有效阅读事件，离开页面清理计时器。
- `README.md`：补充推荐模块、开关、数据边界、降级和后续机器学习演进说明。

## API and data contracts

```java
public record RecommendationEventCommand(
        Long userId,
        Long articleId,
        Long targetAuthorId,
        RecommendationEventType eventType,
        LocalDateTime occurredAt,
        String dedupeKey,
        String source) {}

public record RecommendationItem(Article article, String reason, String source, Long exposureId) {}

public record RecommendationFeedResponse(
        List<RecommendationItem> items,
        String nextCursor,
        RecommendationMode mode) {}
```

```sql
CREATE TABLE user_article_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    article_id BIGINT NULL,
    target_author_id BIGINT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME NOT NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    source VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_article_event_dedupe UNIQUE (dedupe_key),
    INDEX idx_user_event_time (user_id, occurred_at DESC),
    INDEX idx_article_event_time (article_id, occurred_at DESC)
) COMMENT='个性化推荐行为事实';

CREATE TABLE recommendation_exposure (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    article_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    tag_affinity DOUBLE NOT NULL,
    author_affinity DOUBLE NOT NULL,
    similar_score DOUBLE NOT NULL,
    heat_score DOUBLE NOT NULL,
    freshness_score DOUBLE NOT NULL,
    exposed_at DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_recommendation_exposure (user_id, article_id, session_id),
    INDEX idx_exposure_user_time (user_id, exposed_at DESC),
    INDEX idx_exposure_article_time (article_id, exposed_at DESC)
) COMMENT='推荐真实曝光和训练特征快照';

CREATE TABLE recommendation_event_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    article_id BIGINT NULL,
    target_author_id BIGINT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME NOT NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    source VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(500) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_time DATETIME NULL,
    UNIQUE KEY uk_recommendation_outbox_dedupe (dedupe_key),
    INDEX idx_recommendation_outbox_dispatch (status, next_attempt_at, id)
) COMMENT='推荐事件事务 Outbox';
```

```text
GET  /api/recommendations/feed?cursor=<opaque>&size=10
POST /api/recommendations/views/{articleId}
```

`GET /api/recommendations/feed` 只接受认证请求。`cursor` 为 Base64URL 编码的 `sessionId:offset`；服务端根据会话内的 userId 校验归属，客户端不得依赖其中内容。`mode=COLD_START` 或 `mode=FALLBACK` 都表示同一接口已返回既有时间线数据，前端仍按 `items[].article` 渲染；`mode=PERSONALIZED` 表示当前页由有效模型精排。每个成功返回给已登录用户的推荐页项目写一条 `recommendation_exposure`，并将自增 ID 放到 `items[].exposureId`；最新页不写曝光。有效阅读请求体为 `{ "exposureId": 123 }`，为空时表示非推荐入口的普通有效阅读。

### Task 1: 建立行为事实、配置与队列声明

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/recommendation/config/RecommendationProperties.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/entity/RecommendationEventType.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/entity/UserArticleEvent.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/mapper/UserArticleEventMapper.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationEventCommand.java`
- Modify: `script.sql`
- Modify: `src/main/java/cumt/zongzuo/community/config/RabbitConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `pom.xml`
- Test: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationPolicyTest.java`
- Test: `src/test/java/cumt/zongzuo/community/security/SecurityIntegrationTest.java`

**Interfaces:**
- Produces `RecommendationEventType.weight()` and `RecommendationEventCommand` for producer/consumer tasks.
- Produces durable queue name `recommendation.event.queue` with existing `community.dlx` dead-letter convention.

- [ ] **Step 1: Write failing policy and queue declaration tests**

```java
@Test
void eventTypesExposeTheApprovedInterestWeights() {
    assertThat(RecommendationEventType.VIEW.weight()).isEqualTo(1);
    assertThat(RecommendationEventType.LIKE.weight()).isEqualTo(4);
    assertThat(RecommendationEventType.COLLECT.weight()).isEqualTo(8);
    assertThat(RecommendationEventType.COMMENT.weight()).isEqualTo(6);
    assertThat(RecommendationEventType.FOLLOW_AUTHOR.weight()).isEqualTo(10);
}

@Test
void recommendationDeadLetterQueueIsDeclared() {
    assertThat(amqpAdmin.getQueueProperties("recommendation.event.queue.dlq")).isNotNull();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -Dtest=RecommendationPolicyTest,SecurityIntegrationTest#recommendationDeadLetterQueueIsDeclared test`

Expected: compilation failure because `RecommendationEventType` does not exist and queue assertion fails before declaration is added.

- [ ] **Step 3: Append the schema and create minimal domain types**

Append both SQL contracts above to `script.sql`. Add the enum and properties with explicit defaults:

```java
public enum RecommendationEventType {
    VIEW(1), LIKE(4), COLLECT(8), COMMENT(6), FOLLOW_AUTHOR(10);
    private final int weight;
    RecommendationEventType(int weight) { this.weight = weight; }
    public int weight() { return weight; }
}

@Data
@ConfigurationProperties(prefix = "recommendation")
public class RecommendationProperties {
    private boolean enabled = false;
    private int sessionTtlMinutes = 10;
    private int profileTtlDays = 35;
    private int profileWindowDays = 30;
    private int defaultPageSize = 10;
    private int maxPageSize = 20;
    private int minimumUserEvents = 20;
    private int minimumGlobalEvents = 500;
    private int modelWindowDays = 90;
    private int labelWindowDays = 7;
    private String modelDirectory = "data/recommendation-models";
}
```

Create `UserArticleEvent` with `@TableName("user_article_event")`, `IdType.AUTO`, `Long userId/articleId/targetAuthorId`, `String eventType/dedupeKey/source`, and `LocalDateTime occurredAt/createTime`. The mapper is exactly `interface UserArticleEventMapper extends BaseMapper<UserArticleEvent> {}`. Add `@EnableConfigurationProperties(RecommendationProperties.class)` to the properties class. Change `<java.version>` in `pom.xml` from `17` to `21`. Add `recommendation.event.queue` and its `.dlq` using the existing `workQueue`, `deadLetterQueue`, and `deadLetterBinding` helpers. Add:

```yaml
recommendation:
  enabled: ${RECOMMENDATION_ENABLED:false}
  session-ttl-minutes: ${RECOMMENDATION_SESSION_TTL_MINUTES:10}
  profile-ttl-days: ${RECOMMENDATION_PROFILE_TTL_DAYS:35}
  profile-window-days: ${RECOMMENDATION_PROFILE_WINDOW_DAYS:30}
  default-page-size: ${RECOMMENDATION_DEFAULT_PAGE_SIZE:10}
  max-page-size: ${RECOMMENDATION_MAX_PAGE_SIZE:20}
  minimum-user-events: ${RECOMMENDATION_MINIMUM_USER_EVENTS:20}
  minimum-global-events: ${RECOMMENDATION_MINIMUM_GLOBAL_EVENTS:500}
  model-window-days: ${RECOMMENDATION_MODEL_WINDOW_DAYS:90}
  label-window-days: ${RECOMMENDATION_LABEL_WINDOW_DAYS:7}
  model-directory: ${RECOMMENDATION_MODEL_DIRECTORY:data/recommendation-models}
```

- [ ] **Step 4: Run focused tests to verify they pass**

Run: `./mvnw -Dtest=RecommendationPolicyTest,SecurityIntegrationTest#recommendationDeadLetterQueueIsDeclared test`

Expected: PASS; Docker-backed test infrastructure initializes the appended schema and RabbitMQ sees the work/DLQ pair.

- [ ] **Step 5: Commit the independently usable foundation**

```bash
git add pom.xml script.sql src/main/java/cumt/zongzuo/community/config/RabbitConfig.java src/main/java/cumt/zongzuo/community/recommendation src/main/resources/application.yml src/test/java/cumt/zongzuo/community/recommendation src/test/java/cumt/zongzuo/community/security/SecurityIntegrationTest.java
git commit -m "feat: add recommendation event foundation"
```

### Task 2: 原业务完成后采集幂等行为事件

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/recommendation/entity/RecommendationEventOutbox.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/mapper/RecommendationEventOutboxMapper.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationEventOutboxService.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/task/RecommendationOutboxDispatcher.java`
- Delete: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationEventPublisher.java`
- Modify: `script.sql`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/cumt/zongzuo/community/mq/LikeConsumer.java`
- Modify: `src/main/java/cumt/zongzuo/community/service/impl/LikeServiceImpl.java`
- Modify: `src/main/java/cumt/zongzuo/community/service/impl/FavoriteServiceImpl.java`
- Modify: `src/main/java/cumt/zongzuo/community/service/impl/CommentServiceImpl.java`
- Modify: `src/main/java/cumt/zongzuo/community/service/impl/FollowServiceImpl.java`
- Delete: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationEventPublisherTest.java`
- Test: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationEventOutboxIntegrationTest.java`

**Interfaces:**
- Consumes `RecommendationEventCommand` and `recommendation.event.queue` from Task 1.
- Produces `RecommendationEventOutboxService.enqueue(RecommendationEventCommand command)` for transactional business actions and Task 5's valid-view endpoint.
- Produces `RecommendationOutboxDispatcher.dispatchPending()` that confirms RabbitMQ persistence before marking an Outbox row sent.

- [ ] **Step 1: Write failing Outbox, business-boundary and dispatch tests**

```java
@Test
void committedFavoritePersistsOutboxWithoutCallingRabbitInTheRequest() {
    favoriteService.toggleFavorite(7L, 21L, 3L);
    assertThat(outboxRows()).singleElement().satisfies(row -> {
        assertThat(row.getEventType()).isEqualTo("COLLECT");
        assertThat(row.getDedupeKey()).isEqualTo("collect:" + insertedFavoriteId);
        assertThat(row.getStatus()).isEqualTo("PENDING");
    });
    verifyNoInteractions(rabbitTemplate);
}

@Test
void likeEventExistsOnlyAfterArticleLikeRecordWasInserted() {
    likeService.like(7L, 21L, 1);
    assertThat(outboxRows()).isEmpty();
    likeConsumer.handle(articleLikeTask(7L, 21L));
    assertThat(outboxRows()).singleElement()
            .extracting(RecommendationEventOutbox::getDedupeKey)
            .isEqualTo("like:" + insertedLikeRecordId);
}

@Test
void confirmedDispatchMarksOutboxSentAndRedeliveryRemainsIdempotent() {
    long outboxId = insertPendingOutbox(command);
    dispatcher.dispatchPending();
    assertThat(outboxMapper.selectById(outboxId).getStatus()).isEqualTo("SENT");
    assertThat(queueMessage()).isEqualTo(command);
}

@Test
void failedDispatchLeavesBusinessCommittedAndSchedulesRetry() {
    long outboxId = insertPendingOutbox(command);
    broker.stop();
    dispatcher.dispatchPending();
    RecommendationEventOutbox row = outboxMapper.selectById(outboxId);
    assertThat(row.getStatus()).isEqualTo("PENDING");
    assertThat(row.getRetryCount()).isEqualTo(1);
    assertThat(row.getNextAttemptAt()).isAfter(row.getUpdateTime());
}
```

Use Testcontainers MySQL/RabbitMQ for the persisted Outbox and confirmed delivery path. For the broker-failure branch, prefer a dispatcher-focused test with an injected Rabbit sender that throws, so stopping the shared suite container cannot destabilize later tests. Add analogous business tests: comment insertion produces `COMMENT`; only first follow produces `FOLLOW_AUTHOR` with `articleId == null`; unfavorite, uncommented rollback, unfollow, article unlike, comment like and duplicate article-like delivery do not add Outbox rows. Verify `recommendation.enabled=false` does not stop event collection because the switch controls serving only.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -Dtest=RecommendationEventOutboxIntegrationTest test`

Expected: FAIL because Outbox entity, mapper, service and dispatcher do not exist, and LIKE is still emitted before persistent success.

- [ ] **Step 3: Implement transactional Outbox and precise producer hooks**

```java
@Service
@RequiredArgsConstructor
public class RecommendationEventOutboxService {
    private final RecommendationEventOutboxMapper mapper;

    public void enqueue(RecommendationEventCommand command) {
        mapper.insert(RecommendationEventOutbox.pending(command));
    }
}
```

Append the exact `recommendation_event_outbox` DDL from the data-contract section to `script.sql`. Enable correlated confirms and returned messages:

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
    publisher-returns: true
```

`RecommendationOutboxDispatcher` runs every second, selects up to 100 eligible `PENDING` rows, and claims each with a conditional update from `PENDING` to `SENDING`; only the caller whose update count is one sends it. It also returns `SENDING` rows older than five minutes to `PENDING` to recover process crashes. Send with `CorrelationData`, wait no more than five seconds for the confirm future, and mark `SENT` only for an ack. On nack, return, timeout or exception, restore `PENDING`, increment `retry_count`, save a 500-character error, and set `next_attempt_at` to `now + min(300, 2^retryCount)` seconds. A crash after broker ack but before `SENT` may resend; the Task 3 unique `dedupe_key` is the correctness boundary.

For favorite, comment and follow, call `enqueue` after the real insert while their existing database transaction is active. Narrow `FollowServiceImpl`'s duplicate-follow catch to `DuplicateKeyException`; an Outbox insert error must propagate and roll back the follow. Create keys `collect:{favoriteId}`, `comment:{commentId}`, and `follow:{followId}`.

Remove recommendation publication from `LikeServiceImpl`. In the existing transactional `LikeConsumer`, after a new `LikeRecord` has been inserted and only when `targetType == 1`, call `enqueue` with key `like:{likeRecordId}`. Duplicate delivery returns before enqueue because the unique `like_record` insert fails; comment likes and unlike messages never enqueue. Keep existing `like.task.queue`, notification queue and user-facing Redis toggle behavior unchanged.

Task 5 valid views also call `enqueue` inside a short transaction. Do not condition event collection on `recommendation.enabled`.

- [ ] **Step 4: Run focused tests to verify they pass**

Run: `./mvnw -Dtest=RecommendationEventOutboxIntegrationTest test`

Expected: PASS; business requests write only the Outbox, article LIKE follows actual persistence, confirms mark sent, failures retry, and inverse/duplicate actions produce no event.

- [ ] **Step 5: Run the complete Java 21 suite and commit the reliability correction**

```bash
./mvnw test
git diff --check
git add script.sql src/main/resources/application.yml src/main/java/cumt/zongzuo/community/mq/LikeConsumer.java src/main/java/cumt/zongzuo/community/service/impl/LikeServiceImpl.java src/main/java/cumt/zongzuo/community/service/impl/FavoriteServiceImpl.java src/main/java/cumt/zongzuo/community/service/impl/CommentServiceImpl.java src/main/java/cumt/zongzuo/community/service/impl/FollowServiceImpl.java src/main/java/cumt/zongzuo/community/recommendation src/test/java/cumt/zongzuo/community/recommendation
git commit -m "fix: deliver recommendation events through outbox"
```

### Task 3: 幂等消费、Redis 画像和可恢复性

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/recommendation/mq/RecommendationEventConsumer.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationProfileService.java`
- Test: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationEventIntegrationTest.java`

**Interfaces:**
- Consumes Task 2's queue message and persists only the first matching `dedupeKey`.
- Produces `RecommendationProfileService.rebuildProfile(Long userId)` and `profileTags(Long userId, int limit)` / `profileAuthors(Long userId, int limit)` for Task 4 recall.

- [ ] **Step 1: Write failing container-backed idempotency and profile tests**

```java
@Test
void duplicateRabbitDeliveryCreatesOneFactAndAddsProfileOnce() {
    RecommendationEventCommand event = view(1001L, articleId, "view:1001:" + articleId + ":2026-08-09");
    rabbitTemplate.convertAndSend(RecommendationOutboxDispatcher.EVENT_QUEUE, event);
    rabbitTemplate.convertAndSend(RecommendationOutboxDispatcher.EVENT_QUEUE, event);

    await().untilAsserted(() -> {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_article_event", Integer.class)).isEqualTo(1);
        assertThat(redisTemplate.opsForZSet().score("recommendation:tag:1001", "redis")).isEqualTo(1D);
    });
}

@Test
void rebuildUsesRecentMySqlFactsWhenRedisProfileHasExpired() {
    insertEvent(collect(1001L, articleId));
    profileService.rebuildProfile(1001L);
    assertThat(profileService.profileTags(1001L, 5)).containsEntry("redis", 8D);
}
```

Seed a published article, its author, a `tag` named `redis`, and its `article_tag` in the test database. Include a `FOLLOW_AUTHOR` test asserting author zset is updated despite `articleId == null`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -Dtest=RecommendationEventIntegrationTest test`

Expected: FAIL because no listener or profile service exists.

- [ ] **Step 3: Implement durable event consumption and profile updates**

Implement the listener with no swallowed exception so configured retry/DLQ remains effective:

```java
@RabbitListener(queues = RecommendationOutboxDispatcher.EVENT_QUEUE)
public void consume(RecommendationEventCommand command) {
    try {
        eventMapper.insert(toEntity(command));
    } catch (DuplicateKeyException duplicate) {
        log.debug("Recommendation fact already exists: {}", command.dedupeKey());
    }
    profileService.rebuildProfile(command.userId());
}
```

Expose Task 2's queue name as `public static final String EVENT_QUEUE = "recommendation.event.queue"` on `RecommendationOutboxDispatcher` so the producer and consumer cannot drift to different routing keys.

Use the table's unique key as the authority: catch only `DuplicateKeyException` around `insert`, then always rebuild that user's profile from MySQL facts. Any other database or Redis exception must propagate so RabbitMQ retries and ultimately routes to the DLQ. Rebuilding deletes both Redis profile keys, loads the user's facts from the last 30 days in ascending time order, and recalculates tag and author scores; this makes a retry safe even when a previous attempt inserted MySQL successfully but failed partway through Redis updates. For every tag/author score, use:

```java
double decay = Math.exp(-Math.log(2D) * daysBetween / 14D);
double delta = command.eventType().weight() * decay;
redisTemplate.opsForZSet().incrementScore(tagKey(userId), tagName, delta);
redisTemplate.expire(tagKey(userId), properties.getProfileTtlDays(), TimeUnit.DAYS);
```

Use the same 30-day window and formula in `rebuildProfile`; build replacement scores in temporary Redis sorted sets, set their TTL, then atomically rename them over `recommendation:tag:{userId}` and `recommendation:author:{userId}` only after the complete replay succeeds. `FOLLOW_AUTHOR` updates only the temporary author zset. Article-bearing events update both temporary tag and author zsets. This avoids exposing a half-rebuilt profile.

- [ ] **Step 4: Run focused integration tests to verify they pass**

Run: `./mvnw -Dtest=RecommendationEventIntegrationTest test`

Expected: PASS; only one event fact is inserted for duplicate deliveries, profiles have expected scores, and rebuilding from MySQL restores Redis.

- [ ] **Step 5: Commit the recoverable profile pipeline**

```bash
git add src/main/java/cumt/zongzuo/community/recommendation/mq src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationProfileService.java src/test/java/cumt/zongzuo/community/recommendation/RecommendationEventIntegrationTest.java
git commit -m "feat: build recommendation profiles from events"
```

### Task 4: 四路召回、规则精排和多样性重排

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationCandidateService.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationRankingService.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationCandidate.java`
- Modify: `src/main/java/cumt/zongzuo/community/mapper/ArticleMapper.java`
- Modify: `src/main/java/cumt/zongzuo/community/mapper/ArticleTagMapper.java`
- Modify: `src/main/java/cumt/zongzuo/community/mapper/TagMapper.java`
- Test: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationPolicyTest.java`

**Interfaces:**
- Consumes Task 3's profile reads and existing `ElasticsearchOperations`.
- Produces `List<RecommendationCandidate> recallAndRank(Long userId, Set<Long> shownArticleIds, int limit)` and `RecommendationCandidate assembleFeatures(Long userId, Article article)` for Task 5.

- [ ] **Step 1: Write failing pure recall/ranking tests**

```java
@Test
void rankPrefersTagAndAuthorAffinityButExcludesSelfReadAndInvisibleArticles() {
    List<RecommendationCandidate> ranked = rankingService.rank(userId, candidates, Set.of(readArticleId));
    assertThat(ranked).extracting(RecommendationCandidate::articleId)
            .doesNotContain(selfArticleId, readArticleId, deletedArticleId, draftArticleId)
            .startsWith(highAffinityArticleId);
}

@Test
void diversityAllowsAtMostTwoConsecutiveAuthorsAndFourTopTenSameTags() {
    List<RecommendationCandidate> ranked = rankingService.diversify(candidates, 10);
    assertThat(maxConsecutiveAuthorCount(ranked)).isLessThanOrEqualTo(2);
    assertThat(maxTagFrequency(ranked)).isLessThanOrEqualTo(4);
}
```

Add a recall fixture that asserts limits before de-duplication: follow 20, tag 40, ES 30 and hot/fresh 20, then assert final candidates are unique by article ID and never exceed 100.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -Dtest=RecommendationPolicyTest test`

Expected: FAIL because candidate and ranking services do not exist.

- [ ] **Step 3: Implement explicit recall sources and ranking policy**

Add mapper methods with parameterized SQL only:

```java
@Select("SELECT DISTINCT a.* FROM article a JOIN article_tag at ON at.article_id=a.id " +
        "WHERE at.tag_id IN (#{tagIds}) AND a.status=1 AND a.is_deleted=0 " +
        "ORDER BY a.create_time DESC LIMIT #{limit}")
List<Article> selectPublishedByTagIds(@Param("tagIds") Collection<Long> tagIds, @Param("limit") int limit);
```

If collection expansion is unsuitable for the annotation, put the equivalent `<foreach>` in a new `src/main/resources/mapper/RecommendationArticleMapper.xml`; never concatenate IDs into SQL. Implement candidate source labels `FOLLOW`, `TAG`, `SIMILAR`, `EXPLORE` and preserve every source that nominated a de-duplicated article.

Implement:

```java
score = tagAffinity * 3.0
      + authorAffinity * 2.5
      + similarSourceBoost * 2.0
      + normalizedHeat * 1.0
      + freshnessBoost * 1.0
      - readPenalty;
```

Normalize tag and author zset scores against the user's top profile score; calculate heat from `viewCount + likeCount * 3 + collectCount * 5 + commentCount * 4`; freshness is a 7-day linear decay clamped to `[0,1]`; `readPenalty` is `2.0` for an event in the past 30 days. Before score, enforce `status=1`, `isDeleted=0`, `authorId != userId`, no shown ID, and no event-derived read ID. Use the existing ES `more_like_this` field list for up to five recently viewed or collected seed articles, cap total ES outputs at 30, and make ES failure yield an empty source rather than fail the recommendation request.

按分数降序后，贪心选择候选：若会造成连续三篇来自同一作者，或前十篇中同一标签出现第五次，则跳过该候选。若跳过会使结果少于 `limit`，再按分数补回剩余候选，避免冷启动用户看到空流。原因只按真实获胜来源生成：FOLLOW 对应“来自你关注的作者”，TAG 对应“因为你常看 {tag}”，SIMILAR 对应“与你最近阅读的内容相似”，EXPLORE 对应“社区近期热议”。

- [ ] **Step 4: Run focused tests to verify they pass**

Run: `./mvnw -Dtest=RecommendationPolicyTest test`

Expected: PASS; weights, exclusions, caps, truthful reason selection and diversity limits are deterministic.

- [ ] **Step 5: Commit the candidate and ranker boundary**

```bash
git add src/main/java/cumt/zongzuo/community/recommendation/service src/main/java/cumt/zongzuo/community/recommendation/mapper src/main/java/cumt/zongzuo/community/mapper src/main/resources/mapper src/test/java/cumt/zongzuo/community/recommendation/RecommendationPolicyTest.java
git commit -m "feat: rank personalized recommendation candidates"
```

### Task 5: 认证推荐流、稳定会话、有效阅读与服务端降级

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationItem.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationFeedResponse.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationSession.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/dto/RecommendationViewRequest.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/entity/RecommendationExposure.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/mapper/RecommendationExposureMapper.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationExposureService.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationFeedService.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/controller/RecommendationController.java`
- Modify: `src/test/java/cumt/zongzuo/community/security/SecurityIntegrationTest.java`
- Test: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationFeedIntegrationTest.java`

**Interfaces:**
- Consumes Task 2 publisher, Task 1 properties, Task 4 candidate feature assembly and existing `ArticleService#getFeedArticles`; this task deliberately serves only cold-start chronology until Task 6 adds a validated model.
- Produces `GET /api/recommendations/feed` and `POST /api/recommendations/views/{articleId}` for the frontend task.

- [ ] **Step 1: Write failing API integration tests**

```java
@Test
void recommendationFeedRequiresBearerToken() {
    ResponseEntity<String> response = restTemplate.getForEntity(url("/api/recommendations/feed"), String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(401);
}

@Test
void cursorUsesOneUserBoundSessionWithoutDuplicates() {
    HttpHeaders headers = bearerHeaders(1001L);
    RecommendationFeedResponse first = getFeed(headers, null, 2);
    RecommendationFeedResponse second = getFeed(headers, first.nextCursor(), 2);
    assertThat(articleIds(first)).doesNotContainAnyElementsOf(articleIds(second));
    assertThat(getFeed(bearerHeaders(1002L), first.nextCursor(), 2)).isFallback();
}

@Test
void disabledOrRedisFailureFallsBackToChronologicalFeed() {
    properties.setEnabled(false);
    assertThat(getFeed(bearerHeaders(1001L), null, 10).mode()).isEqualTo(RecommendationMode.FALLBACK);
}
```

Also test valid view duplicate protection: two authenticated `POST /views/{articleId}` calls in one calendar day publish one `VIEW` fact. Add a cold-start test with fewer than 20 user facts and fewer than 500 global facts, asserting `mode=COLD_START`, chronological ordering and persisted exposure rows. Use the Testcontainers Redis stop/restart only for the dedicated failure case and ensure HTTP remains 200 with `mode=FALLBACK`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -Dtest=RecommendationFeedIntegrationTest,SecurityIntegrationTest#recommendationFeedRequiresAuthentication test`

Expected: FAIL with 404 before controller implementation; after adding a controller it must be 401 without a bearer token due to existing `/api/**` authentication rule.

- [ ] **Step 3: Implement session paging, validation and fallback**

Use JSON value storage under `recommendation:session:{uuid}` with `RecommendationSession(userId, List<RecommendationItem> items, RecommendationMode mode)`, expiry from `sessionTtlMinutes`, and a Base64URL cursor built as `uuid + ":" + offset`. On first page return a `COLD_START` chronological session from `ArticleService#getFeedArticles`; Task 6 replaces only this session-construction branch after it has a validated model. `RecommendationExposure` has `id`, `userId`, `articleId`, `sessionId`, `source`, the five numeric feature columns from the SQL contract, `exposedAt`, and `createTime`. Before a page is returned, use Task 4's feature assembly for each chronological article, call `RecommendationExposureService.record(sessionId, userId, candidate)`, persist its real current feature values with source `CHRONOLOGICAL`, and replace the item with the returned `exposureId`. On later pages, decode safely, reject malformed/missing/other-user sessions into chronological fallback, and slice without recomputing rank.

```java
public RecommendationFeedResponse feed(Long userId, String cursor, int requestedSize) {
    int size = Math.clamp(requestedSize, 1, properties.getMaxPageSize());
    if (!properties.isEnabled()) return fallback(cursor, size, RecommendationMode.FALLBACK);
    try {
        return cursor == null ? createSession(userId, size) : pageSession(userId, cursor, size);
    } catch (DataAccessException | RedisConnectionFailureException exception) {
        log.warn("Recommendation unavailable; serving chronological fallback", exception);
        return fallback(cursor, size, RecommendationMode.FALLBACK);
    }
}
```

`fallback` and `coldStart` must call `articleService.getFeedArticles(lastCreateTime)` and wrap each result as `new RecommendationItem(article, null, "CHRONOLOGICAL")`; preserve a time cursor encoded as `fallback:{lastCreateTime}`. The only distinction is the returned mode. Do not introduce a public security permit rule for `/api/recommendations/**`.

For effective views, validate the requested article exists, is public and not deleted, then publish exactly this daily key:

```java
"view:" + userId + ":article:" + articleId + ":" + LocalDate.now(ZoneId.of("Asia/Shanghai"))
```

When the optional request exposure ID is present, load it and require equal `userId` and `articleId`; otherwise reject the body with `400` and do not publish an event. The consumer's unique key handles rapid duplicate requests. Send source `recommendation:{exposureId}` when valid, otherwise `article_detail`; never treat a GET detail request alone as an effective recommendation event.

- [ ] **Step 4: Run focused integration tests to verify they pass**

Run: `./mvnw -Dtest=RecommendationFeedIntegrationTest,SecurityIntegrationTest test`

Expected: PASS; unauthenticated calls get 401, a cursor is user-bound and duplicate-free, valid views are daily idempotent, cold start writes real exposures, and disabled/Redis-unavailable paths return chronological data.

- [ ] **Step 5: Commit API delivery and safety boundaries**

```bash
git add src/main/java/cumt/zongzuo/community/recommendation src/test/java/cumt/zongzuo/community/recommendation src/test/java/cumt/zongzuo/community/security/SecurityIntegrationTest.java
git commit -m "feat: expose resilient personalized feed"
```

### Task 6: 真实曝光训练集与 Logistic Regression 模型

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationEligibilityService.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/training/RecommendationFeatureVector.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/training/TrainingExample.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/training/RecommendationModel.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/training/LogisticRegressionTrainer.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/training/RecommendationModelStore.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/training/RecommendationTrainingService.java`
- Create: `src/main/java/cumt/zongzuo/community/recommendation/task/RecommendationTrainingTask.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationRankingService.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationFeedService.java`
- Test: `src/test/java/cumt/zongzuo/community/recommendation/LogisticRegressionTrainerTest.java`
- Test: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationTrainingIntegrationTest.java`

**Interfaces:**
- Consumes Task 3's durable behavior facts, Task 4 `RecommendationCandidate` feature values and Task 5 `recommendation_exposure` writes.
- Produces `RecommendationEligibilityService.isEligible(Long userId)`, `RecommendationModelStore.loadActive()`, and `RecommendationModel.score(RecommendationFeatureVector)` for Task 6's own update of the cold-start feed.

- [ ] **Step 1: Write failing model and eligibility tests**

```java
@Test
void trainerLearnsHigherProbabilityForASeparablePositiveFeatureVector() {
    RecommendationModel model = trainer.train(List.of(
            example(1, 0.9, 0.8, 0.8, 0.7, 0.8),
            example(1, 0.8, 0.9, 0.7, 0.8, 0.7),
            example(0, 0.0, 0.1, 0.0, 0.2, 0.1),
            example(0, 0.1, 0.0, 0.1, 0.1, 0.0)));
    assertThat(model.score(vector(0.9, 0.8, 0.8, 0.7, 0.8)))
            .isGreaterThan(model.score(vector(0.0, 0.1, 0.0, 0.2, 0.1)));
}

@Test
void eligibilityRequiresBothUserAndGlobalThresholds() {
    when(eventMapper.selectCount(any())).thenReturn(20L, 499L);
    assertThat(eligibilityService.isEligible(1001L)).isFalse();
    when(eventMapper.selectCount(any())).thenReturn(20L, 500L);
    assertThat(eligibilityService.isEligible(1001L)).isTrue();
}

@Test
void trainingPublishesOnlyWhenValidationBeatsChronologicalBaseline() {
    trainingService.trainAndPublish();
    assertThat(modelStore.loadActive()).isPresent();
    assertThat(trainingService.lastValidationMetrics().auc()).isGreaterThan(0.5D);
}

@Test
void eligibleUserWithPublishedModelReceivesPersonalizedSession() {
    publishValidModel();
    seedUserEvents(1001L, 20);
    seedGlobalEvents(500);
    assertThat(getFeed(bearerHeaders(1001L), null, 10).mode())
            .isEqualTo(RecommendationMode.PERSONALIZED);
}
```

The integration fixture inserts only real-shaped rows into `recommendation_exposure` and `user_article_event`: 90-day-old training exposures, recent validation exposures, and later positive events inside the seven-day label window. Add the inverse fixture where candidate AUC is not greater than the 0.5 chronological baseline and assert no model file is published.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -Dtest=LogisticRegressionTrainerTest,RecommendationTrainingIntegrationTest test`

Expected: FAIL because the eligibility, exposure, trainer and model classes do not exist.

- [ ] **Step 3: Implement real sample construction, training and model storage**

Create `RecommendationFeatureVector` with these fixed, ordered doubles: `tagAffinity`, `authorAffinity`, `similarScore`, `heatScore`, `freshnessScore`, `sourceFollow`, `sourceTag`, `sourceSimilar`, and `sourceExplore`. `RecommendationExposureService.record` persists exactly those values from the served `RecommendationCandidate`, never inventing a feature later. Deduplicate by `(user_id, article_id, session_id)` and do not write exposures for the latest tab.

`RecommendationEligibilityService.isEligible` must count `user_article_event` by `user_id` and `occurred_at >= now - 30 days`, then all event facts by `occurred_at >= now - 90 days`; it returns true only at `>= 20` and `>= 500` respectively. It must not treat raw GET detail requests or duplicate event deliveries as behaviors.

Build labels with parameterized queries per exposure: `label=1` when a unique event for that user/article has `occurred_at >= exposed_at` and `< exposed_at + 7 days`, or a `FOLLOW_AUTHOR` event in the same window has `target_author_id` equal to the exposed article’s author. Otherwise use `label=0`. Split samples by exposure time: oldest 80 percent training, newest 20 percent validation. Reject training if either split lacks both labels. Standardize all numeric features using training means and standard deviations, using `1.0` for a zero standard deviation.

Implement batch gradient descent in Java with learning rate `0.05`, 300 iterations and L2 regularization `0.01`:

```java
double probability(double[] x, double[] weights, double bias) {
    double z = bias;
    for (int i = 0; i < x.length; i++) z += x[i] * weights[i];
    return 1D / (1D + Math.exp(-Math.max(-35D, Math.min(35D, z))));
}

weights[i] -= learningRate * ((gradient[i] / rows) + l2 * weights[i]);
bias -= learningRate * biasGradient / rows;
```

Evaluate validation AUC with rank ordering and compare it to the fixed chronological baseline AUC `0.5`. Publish only when model AUC is strictly greater than `0.5`, then write JSON to `${recommendation.model-directory}/recommendation-model-<UTC timestamp>.json` through a same-directory temporary file and `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`. Maintain `active-model.json` as an atomic copy of the selected version. The model record stores `version`, `trainedAt`, `featureNames`, means, standard deviations, weights, bias and validation AUC. `RecommendationModelStore.loadActive()` validates exactly the nine feature names and a finite coefficient for every field; invalid JSON returns `Optional.empty()` and logs a warning.

Modify `RecommendationRankingService`: calculate feature vectors from existing candidates and order by `model.score(vector) + freshnessScore * 0.05`, then retain all existing eligibility filters and diversity reordering. Modify `RecommendationFeedService.createSession`: call the ranker only when `RecommendationEligibilityService.isEligible(userId)` is true and `RecommendationModelStore.loadActive()` returns a valid model; return `PERSONALIZED` in that branch. All other branches retain Task 5's chronological `COLD_START` behavior. `RecommendationTrainingTask` runs daily at `0 15 2 * * ?`, logs published version/AUC or a precise non-publication reason, and catches/logs exceptions so scheduling stays alive.

- [ ] **Step 4: Run focused tests to verify they pass**

Run: `./mvnw -Dtest=LogisticRegressionTrainerTest,RecommendationTrainingIntegrationTest test`

Expected: PASS; training is deterministic on the fixture, only better-than-baseline models are published, invalid models are ignored, and both threshold counts are mandatory.

- [ ] **Step 5: Commit the machine-learning boundary**

```bash
git add src/main/java/cumt/zongzuo/community/recommendation/entity/RecommendationExposure.java src/main/java/cumt/zongzuo/community/recommendation/mapper/RecommendationExposureMapper.java src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationEligibilityService.java src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationExposureService.java src/main/java/cumt/zongzuo/community/recommendation/training src/main/java/cumt/zongzuo/community/recommendation/task/RecommendationTrainingTask.java src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationRankingService.java src/test/java/cumt/zongzuo/community/recommendation/LogisticRegressionTrainerTest.java src/test/java/cumt/zongzuo/community/recommendation/RecommendationTrainingIntegrationTest.java
git commit -m "feat: train recommendation ranking model"
```

### Task 7: 首页双入口接入和详情页有效阅读采集

**Files:**
- Create: `src/api/recommendation.js`
- Create: `src/utils/qualifiedArticleView.js`
- Create: `src/utils/qualifiedArticleView.test.js`
- Create: `src/api/recommendation.test.js`
- Modify: `src/views/Home.vue`
- Modify: `src/views/ArticleDetail.vue`

**Interfaces:**
- Consumes Task 6's `RecommendationFeedResponse` and authenticated `POST /api/recommendations/views/{articleId}`.
- Produces no new global state, route or mock data; all legacy Home tabs retain their current calls.

- [ ] **Step 1: Write failing frontend tests for visibility and response shaping**

```js
it('reports exactly once after eight cumulative visible seconds', () => {
  const tracker = createQualifiedArticleView({ thresholdMs: 8000, report })
  tracker.start()
  vi.advanceTimersByTime(5000)
  tracker.setVisible(false)
  vi.advanceTimersByTime(5000)
  tracker.setVisible(true)
  vi.advanceTimersByTime(3000)
  expect(report).toHaveBeenCalledTimes(1)
})

it('maps personalized items to cards while preserving reason', () => {
  expect(toFeedCards({ items: [{ article: { id: 1 }, reason: '因为你常看 Redis' }], mode: 'PERSONALIZED' }))
    .toEqual([{ id: 1, recommendationReason: '因为你常看 Redis' }])
})

it('keeps the latest tab on the chronological endpoint for signed-in users', async () => {
  await loadLatestPage()
  expect(request.get).toHaveBeenCalledWith('/api/article/feed', expect.any(Object))
  expect(getRecommendationFeed).not.toHaveBeenCalled()
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm run test -- --run src/utils/qualifiedArticleView.test.js src/api/recommendation.test.js`

Expected: FAIL because the tracker and recommendation API module do not exist.

- [ ] **Step 3: Implement the minimal frontend integration**

Create API wrappers:

```js
export const getRecommendationFeed = (cursor, size = 10) =>
  request.get('/api/recommendations/feed', { params: { cursor: cursor || undefined, size } })

export const reportQualifiedView = (articleId, exposureId) =>
  request.post(`/api/recommendations/views/${articleId}`, { exposureId })
```

在 `Home.vue` 中，将 `latest` 加入 `recommend`、`hot`、`follow`、`search` 状态，渲染“最新”导航项，并维护独立的 `recommendationCursor` 与 `latestCursor`。重置时只能清空当前页签的游标。`recommend` 分支中，没有 `user.value.id` 时保留现有 `/api/article/feed` 调用；登录后调用 `getRecommendationFeed(recommendationCursor.value)`，用 `res.data.nextCursor` 更新游标，将 `res.data.items` 映射成包含 `recommendationReason` 与 `recommendationExposureId` 的卡片，仅在下一游标为空时设置 `noMore`。`COLD_START` 和 `FALLBACK` 都是成功响应，只是不显示个性化原因。`latest` 分支始终以 `latestCursor` 调用现有 `/api/article/feed`，不受登录状态影响。打开推荐卡片时，将其 `recommendationExposureId` 放入路由 query 的 `exposureId`，其他入口不携带 query。仅当 `activeNav === 'recommend'`、`mode === 'PERSONALIZED'` 且原因非空时渲染原因。不要改变热榜、关注、搜索、导航、文章卡片或点击行为。

Implement `createQualifiedArticleView` with an injected `now`, `setTimeout` and `clearTimeout`, so its timer behavior is unit-testable. It must accumulate only while `document.visibilityState === 'visible'`, pause on `visibilitychange`, call `reportQualifiedView(articleId, exposureId)` once after 8,000 visible milliseconds, reset on article ID change, and call `dispose()` from `onUnmounted`. Pass `exposureId` only from a recommendation-card route query; latest, search, hot, follow and direct links pass `undefined`. In `ArticleDetail.vue`, begin tracking only after `loadDetail` succeeds and `article.value.id` is present. Reporting failure is `console.warn` only; it must never make the article page fail or display an error toast.

- [ ] **Step 4: Run focused frontend tests and production build**

Run: `npm run test -- --run src/utils/qualifiedArticleView.test.js src/api/recommendation.test.js`

Expected: PASS.

Run: `npm run build`

Expected: PASS; record any existing Vite chunk-size warning but do not mask a new build error.

- [ ] **Step 5: Commit frontend rollout**

```bash
git add src/api/recommendation.js src/api/recommendation.test.js src/utils/qualifiedArticleView.js src/utils/qualifiedArticleView.test.js src/views/Home.vue src/views/ArticleDetail.vue
git commit -m "feat: split personalized and latest home feeds"
```

### Task 8: 可观测性、文档和全链路验收

**Files:**
- Create: `src/main/java/cumt/zongzuo/community/recommendation/task/RecommendationMetricsTask.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/service/RecommendationFeedService.java`
- Modify: `src/main/java/cumt/zongzuo/community/recommendation/mq/RecommendationEventConsumer.java`
- Modify: `README.md`
- Test: `src/test/java/cumt/zongzuo/community/recommendation/RecommendationFeedIntegrationTest.java`

**Interfaces:**
- Consumes served `RecommendationItem.source` and persisted event types.
- Produces daily Redis counters and a scheduler log line; it does not introduce a public metrics API or a dashboard.

- [ ] **Step 1: Write failing metric tests**

```java
@Test
void servingFeedIncrementsDailySourceDeliveryCounters() {
    feedService.feed(1001L, null, 10);
    assertThat(redisTemplate.opsForValue().get("recommendation:metrics:2026-08-09:delivery:TAG"))
            .isEqualTo("1");
}

@Test
void consumedEventsIncrementDailyEngagementCounters() {
    consumer.consume(collect(1001L, articleId));
    assertThat(redisTemplate.opsForValue().get("recommendation:metrics:2026-08-09:event:COLLECT"))
            .isEqualTo("1");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -Dtest=RecommendationFeedIntegrationTest#servingFeedIncrementsDailySourceDeliveryCounters,RecommendationEventIntegrationTest#consumedEventsIncrementDailyEngagementCounters test`

Expected: FAIL because delivery and engagement metrics are not recorded.

- [ ] **Step 3: Implement bounded daily counters and documentation**

After a non-fallback response is built, increment `recommendation:metrics:{yyyy-MM-dd}:delivery:{source}` once per delivered item and expire each key after 40 days. After a new (not duplicate) event fact is inserted, increment `recommendation:metrics:{date}:event:{eventType}` with the same TTL. `RecommendationMetricsTask` runs at `0 5 0 * * ?`, reads yesterday's `delivery:*` and `event:*` keys for the four source labels and five types, and logs one structured summary. It must tolerate missing Redis keys and never throw from the scheduler.

Add README sections that state: “推荐” is authenticated and “最新” always uses chronology; `RECOMMENDATION_ENABLED` defaults to `false`; both user 30-day 20-event and global 90-day 500-event gates are required for model ranking; behavior and exposure facts are retained for offline Logistic Regression; Redis profile/session loss, an invalid/missing model, or disabled features degrade recommendation to chronology; and the API can later host a stronger model without changing `/api/recommendations/feed`.

- [ ] **Step 4: Run complete verification and an isolated local smoke test**

Run: `./mvnw test`

Expected: PASS, including existing Testcontainers integration tests and all recommendation tests.

Run: `git diff --check`

Expected: no whitespace errors.

Run from `/Users/yangyiming/Desktop/项目改进/metro-community-frontend`: `npm run test -- --run && npm run build`

Expected: all frontend tests pass and build succeeds; retain the known Vite chunk-size warning only if it is unchanged.

With the isolated local services already configured, start backend with `RECOMMENDATION_ENABLED=true` and frontend on 15173, authenticate with a real local account, open a published article for at least 8 seconds, return Home, and verify browser network shows `POST /api/recommendations/views/{id}` and authenticated `GET /api/recommendations/feed` returning `items`, `nextCursor`, and optional `reason`. Do not fabricate data or tokens when authentication is unavailable; record that specific limitation instead.

- [ ] **Step 5: Commit observability and documentation**

```bash
git add src/main/java/cumt/zongzuo/community/recommendation README.md src/test/java/cumt/zongzuo/community/recommendation
git commit -m "docs: document recommendation operations"
```
