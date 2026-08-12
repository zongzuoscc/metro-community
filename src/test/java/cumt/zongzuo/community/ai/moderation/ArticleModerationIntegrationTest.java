package cumt.zongzuo.community.ai.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationWorker;
import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationRecovery;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.utils.SensitiveUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ResourceLock("shared-article-moderation")
class ArticleModerationIntegrationTest extends IntegrationTestSupport {

    private static final long ARTICLE_ID = 8_770_001L;
    private static final long REVISION_ID = 8_770_101L;
    private static final long JOB_ID = 8_770_201L;
    private static final long AUTHOR_ID = 8_770_301L;
    private static final String MODEL = "shadow-moderator";
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final BlockingQueue<StubResponse> RESPONSES = new LinkedBlockingQueue<>();
    private static final BlockingQueue<String> REQUEST_BODIES = new LinkedBlockingQueue<>();
    private static final HttpServer PROVIDER = providerStub();

    @Autowired
    private ArticleModerationWorker worker;
    @Autowired
    private ArticleContentCanonicalizer canonicalizer;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired
    private MetroAiProperties aiProperties;
    @Autowired
    private ArticleModerationRecovery recovery;
    @MockitoBean
    private SensitiveUtils sensitiveUtils;

    @DynamicPropertySource
    static void moderationProperties(DynamicPropertyRegistry registry) {
        registry.add("metro.ai.enabled", () -> "true");
        registry.add("metro.ai.moderation.enabled", () -> "true");
        registry.add("metro.ai.deep-seek.api-key", () -> "integration-key");
        registry.add("metro.ai.deep-seek.model", () -> MODEL);
        registry.add("metro.ai.deep-seek.base-url",
                () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
        registry.add("metro.ai.moderation.timeout", () -> "PT1S");
        registry.add("metro.ai.moderation.task-timeout", () -> "PT3S");
        registry.add("metro.ai.moderation.lease-duration", () -> "PT5S");
        registry.add("metro.ai.moderation.max-chunk-tokens", () -> "800");
        registry.add("metro.ai.moderation.overlap-tokens", () -> "50");
        registry.add("metro.ai.moderation.max-output-tokens", () -> "80");
        registry.add("metro.ai.moderation.max-estimated-tokens", () -> "20000");
        registry.add("metro.ai.runtime.provider-connect-timeout", () -> "PT0.2S");
        registry.add("metro.ai.runtime.provider-timeout-margin", () -> "PT0.1S");
        registry.add("metro.ai.runtime.retry-delay", () -> "PT0.01S");
        registry.add("metro.ai.runtime.circuit-minimum-calls", () -> "100");
        registry.add("metro.ai.runtime.circuit-sliding-window-size", () -> "100");
        registry.add("metro.ai.moderation.recovery-initial-delay-ms", () -> "3600000");
        registry.add("metro.ai.moderation.recovery-enabled", () -> "true");
    }

    @BeforeEach
    void resetFixture() {
        cleanup();
        REQUESTS.set(0);
        RESPONSES.clear();
        REQUEST_BODIES.clear();
        when(sensitiveUtils.isReady()).thenReturn(true);
        when(sensitiveUtils.findFirst(anyString())).thenReturn(Optional.empty());
        aiProperties.getModeration().setEnabled(true);
        aiProperties.getModeration().setTaskTimeout(Duration.ofSeconds(3));
        aiProperties.getDeepSeek().setApiKey("integration-key");
        purge(RabbitConfig.ARTICLE_MODERATION_QUEUE);
        purge(RabbitConfig.ARTICLE_MODERATION_RETRY_QUEUE);
        purge(RabbitConfig.ARTICLE_MODERATION_QUEUE + ".dlq");
    }

    @AfterAll
    void stopProvider() {
        cleanup();
        PROVIDER.stop(0);
    }

    @Test
    void validModelEvidenceIsAppendOnlyAndStillEndsHumanPendingWithoutPublishing() {
        Fixture fixture = insertPendingFixture("# Heading\nA calm immutable revision body.");

        worker.process(event(fixture));

        assertThat(REQUESTS).hasValue(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                .isEqualTo("HUMAN_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT model_decision FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                .isEqualTo("PASS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_moderation_attempt WHERE job_id=?", Long.class, JOB_ID))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_revision_id FROM article WHERE id=?", Long.class, ARTICLE_ID)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id=?", Integer.class, ARTICLE_ID)).isEqualTo(2);
    }

    @Test
    void reviewDecisionIsEvidenceOnlyAndStillRequiresHumanPending() {
        Fixture fixture = insertPendingFixture("A model review decision remains shadow-only.");
        RESPONSES.add(new StubResponse(200, successBody("REVIEW"), null, null));

        worker.process(event(fixture));

        assertThat(jobState()).isEqualTo("HUMAN_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT model_decision FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                .isEqualTo("REVIEW");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_revision_id FROM article WHERE id=?", Long.class, ARTICLE_ID)).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void retryableProviderFailuresAuditEveryActualProviderAttempt(int status) {
        Fixture fixture = insertPendingFixture("Provider retries are bounded by the shared runtime.");
        for (int attempt = 0; attempt < 3; attempt++) {
            RESPONSES.add(new StubResponse(status, "{\"error\":{\"message\":\"unavailable\"}}",
                    null, null));
        }

        worker.process(event(fixture));

        assertThat(REQUESTS).hasValue(3);
        assertThat(jobState()).isEqualTo("HUMAN_PENDING");
        assertThat(attemptCount()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
                SELECT attempt_no FROM article_moderation_attempt
                WHERE job_id=? ORDER BY attempt_no
                """, Integer.class, JOB_ID)).containsExactly(1, 2, 3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                .startsWith("PROVIDER_");
    }

    @Test
    void absoluteTaskDeadlineRejectsLateProviderResponseWithoutPersistingLateEvidence()
            throws Exception {
        Fixture fixture = insertPendingFixture("A response arrives after the whole-task deadline.");
        // 任务截止时间从领取审核任务时开始计算，还覆盖数据库绑定校验和正文分块。
        // 这里给请求进入 Provider 留出稳定余量，同时仍早于 Stub 的 2 秒阻塞结束，
        // 因此测试的核心仍然是：绝对截止后的迟到模型证据不能持久化。
        aiProperties.getModeration().setTaskTimeout(Duration.ofMillis(750));
        CountDownLatch neverReleasedDuringCall = new CountDownLatch(1);
        RESPONSES.add(new StubResponse(200, successBody("PASS"), null, neverReleasedDuringCall));
        try {
            worker.process(event(fixture));

            assertThat(REQUESTS).hasValue(1);
            assertThat(jobState()).isEqualTo("HUMAN_PENDING");
            assertThat(attemptCount()).isOne();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT error_code FROM article_moderation_attempt
                    WHERE job_id=? AND attempt_no=1
                    """, String.class, JOB_ID)).isEqualTo("TASK_DEADLINE_EXCEEDED");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT structured_output_json FROM article_moderation_attempt
                    WHERE job_id=? AND attempt_no=1
                    """, String.class, JOB_ID)).doesNotContain("modelOutput");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT last_error FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                    .isEqualTo("TASK_DEADLINE_EXCEEDED");
        }
        finally {
            neverReleasedDuringCall.countDown();
            aiProperties.getModeration().setTaskTimeout(Duration.ofSeconds(3));
        }
        assertStays(() -> attemptCount() == 1, 250);
    }

    @Test
    void malformedStructuredOutputFailsClosedWithSanitizedAttempt() {
        Fixture fixture = insertPendingFixture("Malformed output cannot become moderation authority.");
        RESPONSES.add(new StubResponse(200, providerBody("{}", "stop"), null, null));

        worker.process(event(fixture));

        assertThat(jobState()).isEqualTo("HUMAN_PENDING");
        assertThat(attemptCount()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                .isEqualTo("INVALID_MODEL_OUTPUT");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT structured_output_json FROM article_moderation_attempt
                WHERE job_id=? AND attempt_no=1
                """, String.class, JOB_ID)).doesNotContain("Malformed output");
    }

    @Test
    void missingProviderKeyFailsClosedBeforeClaimAndWithoutHttp() {
        Fixture fixture = insertPendingFixture("The provider key disappeared after submission.");
        aiProperties.getDeepSeek().setApiKey(" ");
        try {
            worker.process(event(fixture));

            assertThat(REQUESTS).hasValue(0);
            assertThat(jobState()).isEqualTo("HUMAN_PENDING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT last_error FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                    .isEqualTo("AI_UNAVAILABLE");
        }
        finally {
            aiProperties.getDeepSeek().setApiKey("integration-key");
        }
    }

    @Test
    void disabledModerationSupersedesAStaleSubmissionInsteadOfCreatingHumanWork() {
        Fixture fixture = insertPendingFixture("This submitted revision has already become stale.");
        DomainEvent stale = event(fixture);
        jdbcTemplate.update("""
                UPDATE article SET pending_revision_id=NULL,lock_version=lock_version+1 WHERE id=?
                """, ARTICLE_ID);
        aiProperties.getModeration().setEnabled(false);
        try {
            worker.process(stale);

            assertThat(REQUESTS).hasValue(0);
            assertThat(jobState()).isEqualTo("SUPERSEDED");
            assertThat(attemptCount()).isZero();
        }
        finally {
            aiProperties.getModeration().setEnabled(true);
        }
    }

    @Test
    void providerReceivesTheCompleteFrozenRevisionCorpusIncludingSummaryTagsAndTail() {
        Fixture fixture = insertPendingFixture("Unique immutable title", "summary-only-policy-marker",
                "# Heading\nbody start\nbody-tail-policy-marker", List.of("unique-tag-marker"));

        worker.process(event(fixture));

        assertThat(REQUESTS).hasValue(1);
        assertThat(REQUEST_BODIES).singleElement().asString()
                .contains("Unique immutable title")
                .contains("summary-only-policy-marker")
                .contains("body-tail-policy-marker")
                .contains("unique-tag-marker");
        assertThat(jobState()).isEqualTo("HUMAN_PENDING");
    }

    @Test
    void emptyOrUnavailableDeterministicRulesFailClosedWithoutProviderTraffic() {
        Fixture fixture = insertPendingFixture("The deterministic dictionary is unavailable.");
        when(sensitiveUtils.isReady()).thenReturn(false);

        worker.process(event(fixture));

        assertThat(REQUESTS).hasValue(0);
        assertThat(jobState()).isEqualTo("HUMAN_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                .isEqualTo("DETERMINISTIC_RULE_FAILURE");
        assertThat(attemptCount()).isZero();
    }

    @Test
    void multiChunkReviewAggregatesHighestRiskAndPersistsGlobalEvidenceOffsets() {
        Fixture fixture = insertPendingFixture("# Long article\n" + "ordinary-word ".repeat(900));
        RESPONSES.add(new StubResponse(200, successBody("PASS"), null, null));
        RESPONSES.add(new StubResponse(200, successBody("REJECT", 0, 4), null, null));

        worker.process(event(fixture));

        assertThat(REQUESTS.get()).isGreaterThan(1);
        assertThat(attemptCount()).isEqualTo(REQUESTS.get());
        assertThat(jdbcTemplate.queryForList("""
                SELECT attempt_no FROM article_moderation_attempt
                WHERE job_id=? ORDER BY attempt_no
                """, Integer.class, JOB_ID))
                .containsExactlyElementsOf(java.util.stream.IntStream
                        .rangeClosed(1, REQUESTS.get()).boxed().toList());
        assertThat(jobState()).isEqualTo("HUMAN_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT model_decision FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                .isEqualTo("REJECT");
        Integer sourceStart = jdbcTemplate.queryForObject("""
                SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(structured_output_json,
                    '$.chunk.sourceStart')) AS SIGNED)
                FROM article_moderation_attempt WHERE job_id=? AND attempt_no=2
                """, Integer.class, JOB_ID);
        Integer evidenceStart = jdbcTemplate.queryForObject("""
                SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(structured_output_json,
                    '$.modelOutput.evidenceOffsets[0].start')) AS SIGNED)
                FROM article_moderation_attempt WHERE job_id=? AND attempt_no=2
                """, Integer.class, JOB_ID);
        Integer evidenceEnd = jdbcTemplate.queryForObject("""
                SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(structured_output_json,
                    '$.modelOutput.evidenceOffsets[0].end')) AS SIGNED)
                FROM article_moderation_attempt WHERE job_id=? AND attempt_no=2
                """, Integer.class, JOB_ID);
        assertThat(evidenceStart).isEqualTo(sourceStart);
        assertThat(evidenceEnd).isEqualTo(sourceStart + 4);
    }

    @Test
    void secondChunkPromptFailureUsesTheCurrentLeaseAndNeverLeavesRunning() throws Exception {
        Fixture fixture = insertPendingFixture("# Long article\n" + "ordinary-word ".repeat(900));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RESPONSES.add(new StubResponse(200, successBody("PASS"), entered, release));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> review = caller.submit(() -> worker.process(event(fixture)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            aiProperties.getDeepSeek().setModel(" ");
            release.countDown();
            review.get(3, TimeUnit.SECONDS);

            assertThat(REQUESTS).hasValue(1);
            assertThat(attemptCount()).isOne();
            assertThat(jobState()).isEqualTo("HUMAN_PENDING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT last_error FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                    .isEqualTo("INVALID_MODEL_OUTPUT");
        }
        finally {
            release.countDown();
            aiProperties.getDeepSeek().setModel(MODEL);
            caller.shutdownNow();
        }
    }

    @Test
    void aggregateFailureAfterSuccessfulAttemptNeverLeavesRunning() throws Exception {
        Fixture fixture = insertPendingFixture("A final aggregation failure remains fail closed.");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RESPONSES.add(new StubResponse(200, successBody("PASS"), entered, release));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> review = caller.submit(() -> worker.process(event(fixture)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            aiProperties.getModeration().setMinimumConfidence(null);
            release.countDown();
            review.get(3, TimeUnit.SECONDS);

            assertThat(REQUESTS).hasValue(1);
            assertThat(attemptCount()).isOne();
            assertThat(jobState()).isEqualTo("HUMAN_PENDING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT last_error FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                    .isEqualTo("INTERNAL_ERROR");
        }
        finally {
            release.countDown();
            aiProperties.getModeration().setMinimumConfidence(new java.math.BigDecimal("0.80"));
            caller.shutdownNow();
        }
    }

    @Test
    void realRabbitDeliveryIsIdempotentAndMalformedEventIsDeadLettered() throws Exception {
        Fixture fixture = insertPendingFixture("A real Rabbit moderation delivery.");
        var container = listenerRegistry.getListenerContainer("articleModerationEventConsumer");
        assertThat(container).isNotNull();
        container.start();
        try {
            DomainEvent submitted = event(fixture);
            rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_MODERATION_QUEUE, submitted);
            await(() -> "HUMAN_PENDING".equals(jobState()), 5_000);
            assertThat(REQUESTS).hasValue(1);

            rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_MODERATION_QUEUE, submitted);
            await(() -> queueMessageCount(RabbitConfig.ARTICLE_MODERATION_QUEUE) == 0, 2_000);
            assertStays(() -> REQUESTS.get() == 1, 400);
            assertThat(REQUESTS).hasValue(1);
            assertThat(attemptCount()).isOne();

            ObjectNode poisonPayload = objectMapper.createObjectNode();
            poisonPayload.put("articleId", ARTICLE_ID);
            DomainEvent poison = new DomainEvent(UUID.randomUUID(), "ARTICLE", ARTICLE_ID,
                    7, 1, DomainEventType.ARTICLE_REVISION_SUBMITTED, 1,
                    poisonPayload, Instant.now());
            rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_MODERATION_QUEUE, poison);
            await(() -> rabbitTemplate.receive(
                    RabbitConfig.ARTICLE_MODERATION_QUEUE + ".dlq", 50) != null, 6_000);
        }
        finally {
            container.stop();
        }
    }

    @Test
    void realRabbitDeliveryWhileModerationIsDisabledFailsClosedWithoutHttp() throws Exception {
        Fixture fixture = insertPendingFixture("Moderation is disabled after submission.");
        var container = listenerRegistry.getListenerContainer("articleModerationEventConsumer");
        assertThat(container).isNotNull();
        aiProperties.getModeration().setEnabled(false);
        container.start();
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_MODERATION_QUEUE, event(fixture));
            await(() -> "HUMAN_PENDING".equals(jobState()), 5_000);
            assertThat(REQUESTS).hasValue(0);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT last_error FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                    .isEqualTo("AI_UNAVAILABLE");
        }
        finally {
            container.stop();
            aiProperties.getModeration().setEnabled(true);
        }
    }

    @Test
    void realRabbitLostDeliveryIsRecoveredAfterTheClaimLeaseExpires() throws Exception {
        Fixture fixture = insertPendingFixture("A process died after Rabbit delivery and claim.");
        jdbcTemplate.update("""
                UPDATE article_moderation_job
                SET state='RUNNING',attempt_count=0,lease_owner='dead-process',
                    lease_until=TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP(6)),lock_version=1
                WHERE id=?
                """, JOB_ID);
        var container = listenerRegistry.getListenerContainer("articleModerationEventConsumer");
        assertThat(container).isNotNull();
        container.start();
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.ARTICLE_MODERATION_QUEUE, event(fixture));
            assertStays(() -> REQUESTS.get() == 0, 4_000);
            await(() -> "HUMAN_PENDING".equals(jobState()), 12_000);
            assertThat(REQUESTS).hasValue(1);
            assertThat(jobState()).isEqualTo("HUMAN_PENDING");
            assertThat(attemptCount()).isOne();
        }
        finally {
            container.stop();
        }
    }

    @Test
    void expiredOwnerCannotSupersedeOrAppendAndReplayFailsClosedWithoutPayingAgain() throws Exception {
        Fixture fixture = insertPendingFixture("An immutable revision that remains safe.");
        DomainEvent event = event(fixture);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RESPONSES.add(new StubResponse(200, successBody("PASS"), entered, release));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = caller.submit(() -> worker.process(event));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            jdbcTemplate.update("""
                    UPDATE article_moderation_job
                    SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))
                    WHERE id=?
                    """, JOB_ID);
            release.countDown();
            first.get(3, TimeUnit.SECONDS);

            assertThat(jobState()).isEqualTo("RUNNING");
            worker.process(event);

            assertThat(jobState()).isEqualTo("HUMAN_PENDING");
            assertThat(REQUESTS).hasValue(1);
            assertThat(attemptCount()).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT last_error FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                    .isEqualTo("ATTEMPT_BUDGET_EXHAUSTED");
        }
        finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void expiredRunWithAReservedInvocationFailsClosedWithoutPayingProviderAgain() {
        Fixture fixture = insertPendingFixture("A revision whose prior worker crashed.");
        jdbcTemplate.update("""
                UPDATE article_moderation_job
                SET state='RUNNING',attempt_count=1,lease_owner='dead-worker',
                    lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),lock_version=1
                WHERE id=?
                """, JOB_ID);

        worker.process(event(fixture));

        assertThat(jobState()).isEqualTo("HUMAN_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error FROM article_moderation_job WHERE id=?", String.class, JOB_ID))
                .isEqualTo("ATTEMPT_BUDGET_EXHAUSTED");
        assertThat(REQUESTS).hasValue(0);
        assertThat(attemptCount()).isZero();
    }

    @Test
    void recoveryScannerDurablyRepublishesWithoutRunningProviderOnSchedulerThread() throws Exception {
        insertPendingFixture("A worker crashed immediately after claiming the job.");
        jdbcTemplate.update("""
                UPDATE article_moderation_job
                SET state='RUNNING',attempt_count=0,lease_owner='dead-worker',
                    lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),lock_version=1
                WHERE id=?
                """, JOB_ID);

        var container = listenerRegistry.getListenerContainer("articleModerationEventConsumer");
        assertThat(container).isNotNull();
        recovery.recoverDueJobs();

        assertThat(REQUESTS).hasValue(0);
        assertThat(queueMessageCount(RabbitConfig.ARTICLE_MODERATION_QUEUE)).isOne();
        assertThat(jobState()).isEqualTo("RUNNING");
        container.start();
        try {
            await(() -> "HUMAN_PENDING".equals(jobState()), 8_000);
            assertThat(REQUESTS).hasValue(1);
            assertThat(attemptCount()).isOne();
        }
        finally {
            container.stop();
        }
    }

    @Test
    void articleVersionCommitBetweenPostCheckAndAttemptWriteCannotCrossFinalFence() throws Exception {
        Fixture fixture = insertPendingFixture("A response racing a committed article mutation.");
        DomainEvent event = event(fixture);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RESPONSES.add(new StubResponse(200, successBody("PASS"), entered, release));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            Future<?> review = caller.submit(() -> worker.process(event));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try (PreparedStatement mutation = blocker.prepareStatement("""
                    UPDATE article SET lock_version=lock_version+1 WHERE id=?
                    """)) {
                mutation.setLong(1, ARTICLE_ID);
                assertThat(mutation.executeUpdate()).isOne();
            }
            release.countDown();
            assertStays(() -> !review.isDone() && attemptCount() == 0, 250);
            blocker.commit();
            review.get(3, TimeUnit.SECONDS);

            assertThat(jobState()).isEqualTo("SUPERSEDED");
            assertThat(attemptCount()).isOne();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT error_code FROM article_moderation_attempt
                    WHERE job_id=? AND attempt_no=1
                    """, String.class, JOB_ID)).isEqualTo("STALE_REVISION_BINDING");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT structured_output_json FROM article_moderation_attempt
                    WHERE job_id=? AND attempt_no=1
                    """, String.class, JOB_ID)).doesNotContain("modelOutput");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT published_revision_id FROM article WHERE id=?", Long.class, ARTICLE_ID)).isNull();
        }
        finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void bindingCommittedStaleBeforeProviderReturnsStillAuditsTheRealHttpAttempt()
            throws Exception {
        Fixture fixture = insertPendingFixture("A response arriving after a committed pointer change.");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RESPONSES.add(new StubResponse(200, successBody("PASS"), entered, release));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> review = caller.submit(() -> worker.process(event(fixture)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbcTemplate.update("""
                    UPDATE article SET lock_version=lock_version+1 WHERE id=?
                    """, ARTICLE_ID)).isOne();
            release.countDown();
            review.get(3, TimeUnit.SECONDS);

            assertThat(REQUESTS).hasValue(1);
            assertThat(jobState()).isEqualTo("SUPERSEDED");
            assertThat(attemptCount()).isOne();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT error_code FROM article_moderation_attempt
                    WHERE job_id=? AND attempt_no=1
                    """, String.class, JOB_ID)).isEqualTo("STALE_REVISION_BINDING");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT structured_output_json FROM article_moderation_attempt
                    WHERE job_id=? AND attempt_no=1
                    """, String.class, JOB_ID)).doesNotContain("modelOutput");
        }
        finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void claimWaitsForArticleSubmissionFenceAndNeverStartsProviderForStalePointer() throws Exception {
        Fixture fixture = insertPendingFixture("A revision invalidated while submission owns the article lock.");
        DomainEvent event = event(fixture);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (Connection submission = dataSource.getConnection()) {
            submission.setAutoCommit(false);
            try (PreparedStatement mutation = submission.prepareStatement("""
                    UPDATE article
                    SET pending_revision_id=NULL,lock_version=lock_version+1
                    WHERE id=?
                    """)) {
                mutation.setLong(1, ARTICLE_ID);
                assertThat(mutation.executeUpdate()).isOne();
            }

            Future<?> review = caller.submit(() -> worker.process(event));
            assertStays(() -> !review.isDone() && REQUESTS.get() == 0, 250);
            assertThat(REQUESTS)
                    .as("claim must wait behind the article submission fence")
                    .hasValue(0);

            submission.commit();
            review.get(3, TimeUnit.SECONDS);

            assertThat(REQUESTS).hasValue(0);
            assertThat(jobState()).isEqualTo("SUPERSEDED");
            assertThat(attemptCount()).isZero();
        }
        finally {
            caller.shutdownNow();
        }
    }

    private Fixture insertPendingFixture(String body) {
        return insertPendingFixture("Immutable title", "summary", body, List.of("java"));
    }

    private Fixture insertPendingFixture(String title, String summary, String body,
                                         List<String> tags) {
        ArticleContentSnapshot snapshot = canonicalizer.canonicalize(
                title, summary, body, null, tags);
        jdbcTemplate.update("""
                INSERT INTO article(id,title,summary,content,author_id,status,is_deleted,
                    visibility_state,review_state,lifecycle_epoch,lock_version,create_time,update_time)
                VALUES (?,?,?,?,?,2,0,'PRIVATE','PENDING',1,7,NOW(6),NOW(6))
                """, ARTICLE_ID, snapshot.title(), snapshot.summary(), snapshot.bodyMarkdown(), AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision(id,article_id,revision_no,title,summary,body_markdown,
                    body_plain,cover,tags_json,content_hash,source_draft_version,created_by,created_at)
                VALUES (?,?,1,?,?,?,?,?,?,?,?,?,NOW(6))
                """, REVISION_ID, ARTICLE_ID, snapshot.title(), snapshot.summary(), snapshot.bodyMarkdown(),
                snapshot.bodyPlain(), snapshot.cover(), snapshot.tagsJson(), snapshot.contentHash(), 1L, AUTHOR_ID);
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,pending_revision_id=? WHERE id=?
                """, REVISION_ID, REVISION_ID, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article_moderation_job(id,article_id,revision_id,content_hash,state,
                    attempt_count,created_at,updated_at,lock_version)
                VALUES (?,?,?,?,'PENDING',0,NOW(6),NOW(6),0)
                """, JOB_ID, ARTICLE_ID, REVISION_ID, snapshot.contentHash());
        return new Fixture(snapshot.contentHash());
    }

    private DomainEvent event(Fixture fixture) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("articleId", ARTICLE_ID);
        payload.put("revisionId", REVISION_ID);
        payload.put("revisionNo", 1);
        payload.put("moderationJobId", JOB_ID);
        payload.put("contentHash", fixture.contentHash());
        payload.put("sourceDraftVersion", 1);
        return new DomainEvent(UUID.randomUUID(), "ARTICLE", ARTICLE_ID, 7, 1,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, 1, payload, Instant.now());
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM article_moderation_attempt WHERE job_id=?", JOB_ID);
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE id=?", JOB_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL," +
                "published_revision_id=NULL WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE id=?", REVISION_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
    }

    private void purge(String queue) {
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(queue);
            return null;
        });
    }

    private long queueMessageCount(String queue) {
        return rabbitTemplate.execute(channel -> channel.queueDeclarePassive(queue)
                .getMessageCount());
    }

    private static void await(java.util.concurrent.Callable<Boolean> condition,
                              long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        throw new AssertionError("condition was not met before timeout");
    }

    private static void assertStays(java.util.concurrent.Callable<Boolean> condition,
                                    long durationMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
        while (System.nanoTime() < deadline) {
            if (!condition.call()) {
                throw new AssertionError("condition did not remain stable");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
    }

    private static HttpServer providerStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                REQUESTS.incrementAndGet();
                REQUEST_BODIES.add(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                StubResponse plan = RESPONSES.poll();
                if (plan == null) {
                    plan = new StubResponse(200, successBody("PASS"), null, null);
                }
                if (plan.entered() != null) {
                    plan.entered().countDown();
                }
                if (plan.release() != null) {
                    try {
                        plan.release().await(2, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }
                respond(exchange, plan.status(), plan.body());
            });
            server.start();
            return server;
        }
        catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String jobState() {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM article_moderation_job WHERE id=?", String.class, JOB_ID);
    }

    private long attemptCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_moderation_attempt WHERE job_id=?", Long.class, JOB_ID);
    }

    private static String successBody(String decision) {
        return successBody(decision, -1, -1);
    }

    private static String successBody(String decision, int evidenceStart, int evidenceEnd) {
        int severity = "REJECT".equals(decision) ? 4 : "REVIEW".equals(decision) ? 2 : 0;
        String evidence = evidenceStart >= 0
                ? "[{\\\"start\\\":" + evidenceStart + ",\\\"end\\\":" + evidenceEnd + "}]"
                : "[]";
        return """
                {"id":"shadow-1","created":1,"model":"shadow-moderator","choices":[{
                  "index":0,"message":{"role":"assistant","content":"{\\"decision\\":\\"%s\\",\\"categories\\":[],\\"severity\\":%d,\\"confidence\\":0.99,\\"evidenceOffsets\\":%s,\\"reason\\":\\"bounded evidence\\",\\"model\\":\\"shadow-moderator\\",\\"promptVersion\\":\\"moderation-v1\\"}"},
                  "finish_reason":"stop"}],"usage":{"prompt_tokens":42,"completion_tokens":20,"total_tokens":62}}
                """.formatted(decision, severity, evidence);
    }

    private static String providerBody(String content, String finishReason) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {"id":"shadow-1","created":1,"model":"shadow-moderator","choices":[{
                  "index":0,"message":{"role":"assistant","content":"%s"},
                  "finish_reason":"%s"}],"usage":{"prompt_tokens":42,"completion_tokens":20,
                  "total_tokens":62}}
                """.formatted(escaped, finishReason);
    }

    private record Fixture(String contentHash) {
    }

    private record StubResponse(int status, String body, CountDownLatch entered,
                                CountDownLatch release) {
    }
}
