package cumt.zongzuo.community.ai.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.DisabledAiChatGateway;
import cumt.zongzuo.community.dto.NotificationMsgDTO;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@ResourceLock("shared-rabbit-moderation-queues")
class ModerationFallbackIntegrationTest extends IntegrationTestSupport {

    private static final String LISTENER_ID = "legacyModerationSubmissionConsumer";
    private static final String AUDIT_QUEUE = "article.audit.queue";
    private static final String AUDIT_DLQ = "article.audit.queue.dlq";
    private static final String ES_QUEUE = "es.sync.queue";
    private static final String NOTIFICATION_QUEUE = "message.notify.queue";
    private static final long ARTICLE_ID = 8_600_001L;
    private static final long AUTHOR_ID = 8_600_101L;
    private static final long ADMIN_ID = 8_600_201L;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private ManualReviewRoutingService routingService;
    @Autowired
    private MetroAiProperties aiProperties;
    @Autowired
    private AiChatGateway aiChatGateway;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    void startOnlyModerationListener() {
        seedUsers();
        purgeQueues();
        MessageListenerContainer container = listenerRegistry.getListenerContainer(LISTENER_ID);
        assertThat(container).as("the unconditional manual-review listener").isNotNull();
        assertThat(listenerRegistry.getListenerContainers())
                .filteredOn(MessageListenerContainer::isRunning)
                .as("no listener auto-starts in integration tests")
                .isEmpty();
        container.start();
    }

    @AfterAll
    void stopModerationListener() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(LISTENER_ID);
        if (container != null) {
            container.stop();
        }
        purgeQueues();
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM article WHERE id BETWEEN ? AND ?", ARTICLE_ID, ARTICLE_ID + 999);
        purgeQueues();
    }

    @AfterEach
    void drainQueues() {
        purgeQueues();
    }

    @Test
    void allAiFlagsOffStillRoutesSubmittedArticleToTheExistingHumanPendingState() {
        assertThat(aiProperties.isEnabled()).isFalse();
        assertThat(aiProperties.getModeration().isEnabled()).isFalse();
        assertThat(aiProperties.getPlatform().getApiKey()).isBlank();
        assertThat(aiChatGateway).isInstanceOf(DisabledAiChatGateway.class);
        insertArticle(ARTICLE_ID, 2, 0, "2026-08-10 01:00:00");

        rabbitTemplate.convertAndSend(AUDIT_QUEUE, ARTICLE_ID);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(queueMessageCount(AUDIT_QUEUE)).isZero();
            assertThat(status(ARTICLE_ID)).isEqualTo(2);
        });
        assertNoDecisionMessages();
        assertThat(meterRegistry.find("moderation.fallback.count").meters()).isNotEmpty();
        assertThat(meterRegistry.find("moderation.pending.age").meters()).isNotEmpty();
        assertModerationMetersHaveOnlyLowCardinalityTags();
    }

    @Test
    void revisionAdminDecisionIsTypedModeDisabledProblemInLegacy() throws Exception {
        HttpHeaders headers = bearerHeaders(ADMIN_ID);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/admin/moderation/jobs/999999/approve"),
                new HttpEntity<>("""
                        {"revisionId":1,"expectedJobVersion":0,"expectedArticleVersion":0,"reason":"mode"}
                        """, headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(objectMapper.readTree(response.getBody()).path("code").asText())
                .isEqualTo("REVISION_MODERATION_DISABLED");
    }

    @Test
    void duplicateDeletedAndNonPendingDeliveriesAreAcknowledgedAsIdempotentNoOps() {
        double fallbackCountBefore = fallbackCount();
        insertArticle(ARTICLE_ID + 1, 2, 0, "2026-08-10 01:00:00");
        insertArticle(ARTICLE_ID + 2, 2, 1, "2026-08-10 01:01:00");
        insertArticle(ARTICLE_ID + 3, 0, 0, "2026-08-10 01:02:00");
        insertArticle(ARTICLE_ID + 4, 1, 0, "2026-08-10 01:03:00");
        insertArticle(ARTICLE_ID + 5, 3, 0, "2026-08-10 01:04:00");

        rabbitTemplate.convertAndSend(AUDIT_QUEUE, ARTICLE_ID + 1);
        rabbitTemplate.convertAndSend(AUDIT_QUEUE, ARTICLE_ID + 1);
        for (long id = ARTICLE_ID + 2; id <= ARTICLE_ID + 5; id++) {
            rabbitTemplate.convertAndSend(AUDIT_QUEUE, id);
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(queueMessageCount(AUDIT_QUEUE)).isZero();
            assertThat(fallbackCount()).isEqualTo(fallbackCountBefore + 6D);
        });
        assertThat(queueMessageCount(AUDIT_DLQ)).isZero();
        assertThat(status(ARTICLE_ID + 1)).isEqualTo(2);
        assertThat(status(ARTICLE_ID + 2)).isEqualTo(2);
        assertThat(status(ARTICLE_ID + 3)).isZero();
        assertThat(status(ARTICLE_ID + 4)).isEqualTo(1);
        assertThat(status(ARTICLE_ID + 5)).isEqualTo(3);
        assertNoDecisionMessages();
    }

    @Test
    void invalidIdsFailAndMissingRowExhaustsBoundedRetryIntoTheRealDlq() {
        assertThatThrownBy(() -> routingService.routeLegacyArticle(null, "AI_FOUNDATION_MANUAL_ONLY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routingService.routeLegacyArticle(0L, "AI_FOUNDATION_MANUAL_ONLY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> routingService.routeLegacyArticle(-1L, "AI_FOUNDATION_MANUAL_ONLY"))
                .isInstanceOf(IllegalArgumentException.class);

        long missingId = ARTICLE_ID + 90;
        rabbitTemplate.convertAndSend(AUDIT_QUEUE, missingId);

        await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> {
            assertThat(queueMessageCount(AUDIT_QUEUE)).isZero();
            assertThat(queueMessageCount(AUDIT_DLQ)).isEqualTo(1);
        });
        assertThat(rabbitTemplate.receiveAndConvert(AUDIT_DLQ, 2_000)).isEqualTo(missingId);
        assertNoDecisionMessages();
    }

    @Test
    void pendingEndpointExcludesDeletedRowsAndUsesStableOldestFirstOrder() throws Exception {
        insertArticle(ARTICLE_ID + 10, 2, 0, "2000-01-01 00:00:03");
        insertArticle(ARTICLE_ID + 11, 2, 0, "2000-01-01 00:00:01");
        insertArticle(ARTICLE_ID + 12, 2, 0, "2000-01-01 00:00:01");
        insertArticle(ARTICLE_ID + 13, 2, 1, "1999-01-01 00:00:00");
        insertArticle(ARTICLE_ID + 14, 1, 0, "1998-01-01 00:00:00");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/article/admin/pending?page=1&size=100"), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ADMIN_ID)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode records = objectMapper.readTree(response.getBody()).path("data").path("records");
        List<Long> taskIds = new ArrayList<>();
        records.forEach(record -> {
            long id = record.path("id").asLong();
            if (id >= ARTICLE_ID && id <= ARTICLE_ID + 999) {
                taskIds.add(id);
            }
        });
        assertThat(taskIds).containsExactly(ARTICLE_ID + 11, ARTICLE_ID + 12, ARTICLE_ID + 10);
        assertThat(taskIds).doesNotContain(ARTICLE_ID + 13, ARTICLE_ID + 14);
    }

    @Test
    void repeatedApprovalIsIdempotentAndPublishesOneDecisionSetFromTheRealAdmin() {
        long id = ARTICLE_ID + 20;
        insertArticle(id, 2, 0, "2026-08-10 02:00:00");

        ResponseEntity<String> first = audit(id, true, "ok");
        ResponseEntity<String> repeated = audit(id, true, "ok");

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(repeated.getStatusCode().value()).isEqualTo(200);
        assertThat(status(id)).isEqualTo(1);
        assertThat(rabbitTemplate.receiveAndConvert(ES_QUEUE, 2_000)).isEqualTo(id);
        assertThat(rabbitTemplate.receive(ES_QUEUE, 100)).isNull();
        NotificationMsgDTO notification = (NotificationMsgDTO) rabbitTemplate.receiveAndConvert(
                NOTIFICATION_QUEUE, 2_000);
        assertThat(notification).isNotNull();
        assertThat(notification.getFromId()).isEqualTo(ADMIN_ID);
        assertThat(notification.getToId()).isEqualTo(AUTHOR_ID);
        assertThat(notification.getTargetId()).isEqualTo(id);
        assertThat(rabbitTemplate.receive(NOTIFICATION_QUEUE, 100)).isNull();
    }

    @Test
    void repeatedRejectionIsIdempotentAndPublishesOneNotificationOnly() {
        long id = ARTICLE_ID + 21;
        insertArticle(id, 2, 0, "2026-08-10 02:01:00");

        ResponseEntity<String> first = audit(id, false, "policy");
        ResponseEntity<String> repeated = audit(id, false, "policy");

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(repeated.getStatusCode().value()).isEqualTo(200);
        assertThat(status(id)).isEqualTo(3);
        assertThat(rabbitTemplate.receive(ES_QUEUE, 100)).isNull();
        NotificationMsgDTO notification = (NotificationMsgDTO) rabbitTemplate.receiveAndConvert(
                NOTIFICATION_QUEUE, 2_000);
        assertThat(notification).isNotNull();
        assertThat(notification.getFromId()).isEqualTo(ADMIN_ID);
        assertThat(notification.getContent()).contains("policy");
        assertThat(rabbitTemplate.receive(NOTIFICATION_QUEUE, 100)).isNull();
    }

    @Test
    void missingDeletedOppositeAndOtherStatesReturnLegacyHttp409WithoutSideEffects() {
        long deleted = ARTICLE_ID + 30;
        long draft = ARTICLE_ID + 31;
        long rejected = ARTICLE_ID + 32;
        long published = ARTICLE_ID + 33;
        insertArticle(deleted, 2, 1, "2026-08-10 03:00:00");
        insertArticle(draft, 0, 0, "2026-08-10 03:01:00");
        insertArticle(rejected, 3, 0, "2026-08-10 03:02:00");
        insertArticle(published, 1, 0, "2026-08-10 03:03:00");

        assertLegacyConflict(audit(ARTICLE_ID + 99, true, "missing"));
        assertLegacyConflict(audit(deleted, true, "deleted"));
        assertLegacyConflict(audit(draft, true, "draft"));
        assertLegacyConflict(audit(rejected, true, "opposite"));
        assertLegacyConflict(audit(published, false, "opposite"));
        assertNoDecisionMessages();
    }

    @Test
    void concurrentOppositeDecisionsHaveOneWinnerOneConflictAndOneDecisionSet() throws Exception {
        long id = ARTICLE_ID + 40;
        insertArticle(id, 2, 0, "2026-08-10 04:00:00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> approve = executor.submit(() -> auditAfterBarrier(id, true, ready, start));
            Future<ResponseEntity<String>> reject = executor.submit(() -> auditAfterBarrier(id, false, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = new ArrayList<>(List.of(
                    approve.get(10, TimeUnit.SECONDS).getStatusCode().value(),
                    reject.get(10, TimeUnit.SECONDS).getStatusCode().value()));
            Collections.sort(statuses);
            assertThat(statuses).containsExactly(200, 409);
        } finally {
            executor.shutdownNow();
        }

        int finalStatus = status(id);
        assertThat(finalStatus).isIn(1, 3);
        NotificationMsgDTO notification = (NotificationMsgDTO) rabbitTemplate.receiveAndConvert(
                NOTIFICATION_QUEUE, 2_000);
        assertThat(notification).isNotNull();
        assertThat(notification.getFromId()).isEqualTo(ADMIN_ID);
        assertThat(rabbitTemplate.receive(NOTIFICATION_QUEUE, 100)).isNull();
        if (finalStatus == 1) {
            assertThat(rabbitTemplate.receiveAndConvert(ES_QUEUE, 2_000)).isEqualTo(id);
        }
        assertThat(rabbitTemplate.receive(ES_QUEUE, 100)).isNull();
    }

    private ResponseEntity<String> auditAfterBarrier(long id, boolean pass,
                                                     CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return audit(id, pass, pass ? "approve" : "reject");
    }

    private ResponseEntity<String> audit(long id, boolean pass, String reason) {
        HttpHeaders headers = bearerHeaders(ADMIN_ID);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"id\":" + id + ",\"pass\":" + pass + ",\"reason\":\"" + reason + "\"}";
        return restTemplate.postForEntity(url("/api/article/admin/audit"), new HttpEntity<>(body, headers), String.class);
    }

    private void assertLegacyConflict(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("\"code\":409", "\"msg\"");
    }

    private HttpHeaders bearerHeaders(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(userId));
        return headers;
    }

    private void insertArticle(long id, int status, int deleted, String createdAt) {
        jdbcTemplate.update("""
                INSERT INTO article
                    (id, title, summary, content, author_id, status, is_deleted, create_time, update_time)
                VALUES (?, ?, 'summary', 'body', ?, ?, ?, ?, ?)
                """, id, "moderation-" + id, AUTHOR_ID, status, deleted, createdAt, createdAt);
    }

    private int status(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id = ?", Integer.class, id);
    }

    private void assertNoDecisionMessages() {
        assertThat(rabbitTemplate.receive(ES_QUEUE, 100)).isNull();
        assertThat(rabbitTemplate.receive(NOTIFICATION_QUEUE, 100)).isNull();
    }

    private void assertModerationMetersHaveOnlyLowCardinalityTags() {
        for (Meter meter : meterRegistry.getMeters()) {
            if (!meter.getId().getName().startsWith("moderation.")) {
                continue;
            }
            assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getKey()).isIn("outcome", "route"));
            assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getValue())
                            .doesNotContain(String.valueOf(ARTICLE_ID), "manual review", "body"));
        }
    }

    private double fallbackCount() {
        return meterRegistry.find("moderation.fallback.count").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private void seedUsers() {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, role, status)
                VALUES (?, 'moderation-author', 'unused', 'moderation-author@example.com', 0, 0)
                ON DUPLICATE KEY UPDATE role = VALUES(role), status = VALUES(status)
                """, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, role, status)
                VALUES (?, 'moderation-admin', 'unused', 'moderation-admin@example.com', 1, 0)
                ON DUPLICATE KEY UPDATE role = VALUES(role), status = VALUES(status)
                """, ADMIN_ID);
    }

    private int queueMessageCount(String queue) {
        Object value = amqpAdmin.getQueueProperties(queue).get("QUEUE_MESSAGE_COUNT");
        return ((Number) value).intValue();
    }

    private void purgeQueues() {
        purge(AUDIT_QUEUE);
        purge(AUDIT_DLQ);
        purge(ES_QUEUE);
        purge(NOTIFICATION_QUEUE);
    }

    private void purge(String queue) {
        if (amqpAdmin.getQueueProperties(queue) != null) {
            amqpAdmin.purgeQueue(queue, true);
        }
    }
}
