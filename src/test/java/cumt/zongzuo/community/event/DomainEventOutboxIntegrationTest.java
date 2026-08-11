package cumt.zongzuo.community.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.outbox.DomainEventOutbox;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxClaimer;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxDispatcher;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxMapper;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxService;
import cumt.zongzuo.community.event.outbox.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventOutboxIntegrationTest extends IntegrationTestSupport {

    private static final List<String> EVENT_QUEUES = List.of(
            RabbitConfig.ARTICLE_MODERATION_QUEUE,
            RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE,
            RabbitConfig.ARTICLE_CHUNK_FACT_QUEUE,
            RabbitConfig.ARTICLE_CHUNK_ELASTICSEARCH_QUEUE,
            RabbitConfig.ARTICLE_CHUNK_MILVUS_QUEUE,
            RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE);

    @Autowired
    private DomainEventOutboxService outboxService;
    @Autowired
    private DomainEventOutboxMapper outboxMapper;
    @Autowired
    private DomainEventOutboxClaimer claimer;
    @Autowired
    private DomainEventOutboxDispatcher dispatcher;
    @Autowired
    private DomainEventPublisher publisher;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @BeforeEach
    void cleanState() {
        var moderation = listenerRegistry.getListenerContainer("articleModerationEventConsumer");
        if (moderation != null && moderation.isRunning()) {
            moderation.stop();
        }
        jdbcTemplate.update("DELETE FROM domain_event_outbox");
        EVENT_QUEUES.forEach(this::purge);
        EVENT_QUEUES.stream().map(name -> name + ".dlq").forEach(this::purge);
    }

    @Test
    void sourceTransactionRollbackLeavesNeitherOutboxRowNorRabbitMessage() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored -> {
            append(41L, 1L, 1L, DomainEventType.ARTICLE_REVISION_SUBMITTED, "rollback");
            throw new IllegalStateException("force source rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countRows()).isZero();
        assertThat(rabbitTemplate.receive(RabbitConfig.ARTICLE_MODERATION_QUEUE, 100)).isNull();
    }

    @Test
    void uuidUsesCanonicalSixteenBytesAndDedupeRejectsDifferentEventContent() {
        UUID eventId = append(41L, 1L, 1L,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, "same");
        UUID repeated = outboxService.append("ARTICLE", 41L, 1L, 1L,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, 1,
                payload(41L, "same"), dedupe(41L, 1L, 1L,
                        DomainEventType.ARTICLE_REVISION_SUBMITTED, "same"));

        assertThat(repeated).isEqualTo(eventId);
        assertThat(countRows()).isEqualTo(1L);

        assertThatThrownBy(() -> outboxService.append("ARTICLE", 42L, 1L, 1L,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, 1,
                payload(42L, "invalid-key"), "caller:chosen:key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT HEX(event_id) FROM domain_event_outbox", String.class))
                .isEqualTo(eventId.toString().replace("-", "").toUpperCase());

        assertThatThrownBy(() -> outboxService.append("ARTICLE", 41L, 1L, 1L,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, 1,
                payload(41L, "different"), dedupe(41L, 1L, 1L,
                        DomainEventType.ARTICLE_REVISION_SUBMITTED, "same")))
                .hasMessageContaining("dedupe");
        assertThat(countRows()).isEqualTo(1L);
    }

    @Test
    void twoShortClaimTransactionsUseStableAscendingIdsWithoutOverlap() throws Exception {
        for (long version = 1; version <= 8; version++) {
            append(50L, version, 1L, DomainEventType.ARTICLE_REVISION_SUBMITTED,
                    "claim-" + version);
        }

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<DomainEventOutbox>> first = executor.submit(() -> claimAfter(barrier, 4));
            Future<List<DomainEventOutbox>> second = executor.submit(() -> claimAfter(barrier, 4));
            List<DomainEventOutbox> firstRows = first.get();
            List<DomainEventOutbox> secondRows = second.get();

            assertThat(firstRows).hasSize(4).extracting(DomainEventOutbox::getId).isSorted();
            assertThat(secondRows).hasSize(4).extracting(DomainEventOutbox::getId).isSorted();
            Set<Long> allIds = new HashSet<>();
            firstRows.forEach(row -> assertThat(allIds.add(row.getId())).isTrue());
            secondRows.forEach(row -> assertThat(allIds.add(row.getId())).isTrue());
            assertThat(allIds).hasSize(8);
            assertThat(firstRows).allSatisfy(row -> {
                assertThat(row.getRetryCount()).isEqualTo(1);
                assertThat(row.getLeaseOwner()).isNotBlank();
            });
            assertThat(secondRows).allSatisfy(row -> assertThat(row.getRetryCount()).isEqualTo(1));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void twoDispatcherInstancesPublishDisjointBatchesAfterTheirClaimsCommit() throws Exception {
        for (long version = 1; version <= 8; version++) {
            append(55L, version, 1L, DomainEventType.ARTICLE_REVISION_SUBMITTED,
                    "dispatcher-" + version);
        }
        Set<UUID> firstPublished = ConcurrentHashMap.newKeySet();
        Set<UUID> secondPublished = ConcurrentHashMap.newKeySet();
        DomainEventOutboxDispatcher firstDispatcher = dispatcherWith(
                (event, owner) -> assertCommittedClaim(event, owner, firstPublished));
        DomainEventOutboxDispatcher secondDispatcher = dispatcherWith(
                (event, owner) -> assertCommittedClaim(event, owner, secondPublished));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> dispatchAfter(barrier, firstDispatcher));
            Future<?> second = executor.submit(() -> dispatchAfter(barrier, secondDispatcher));
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(firstPublished).hasSize(4);
        assertThat(secondPublished).hasSize(4);
        assertThat(firstPublished).doesNotContainAnyElementsOf(secondPublished);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE state='PUBLISHED'", Long.class))
                .isEqualTo(8L);
    }

    @Test
    void slowConfirmsCannotExpireUnpublishedTailRowsIntoAnotherDispatcher() throws Exception {
        for (long version = 1; version <= 6; version++) {
            append(56L, version, 1L, DomainEventType.ARTICLE_REVISION_SUBMITTED,
                    "slow-confirm-" + version);
        }
        ConcurrentHashMap<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
        AtomicInteger firstDispatcherStarts = new AtomicInteger();
        DomainEventOutboxDispatcher firstDispatcher = dispatcherWith(
                (event, owner) -> {
                    attempts.computeIfAbsent(event.eventId(), ignored -> new AtomicInteger())
                            .incrementAndGet();
                    firstDispatcherStarts.incrementAndGet();
                    Thread.sleep(150);
                }, 6, Duration.ofMillis(400));
        DomainEventOutboxDispatcher secondDispatcher = dispatcherWith(
                (event, owner) -> attempts.computeIfAbsent(event.eventId(),
                        ignored -> new AtomicInteger()).incrementAndGet(),
                6, Duration.ofMillis(400));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(firstDispatcher::dispatchPending);
            await().atMost(Duration.ofSeconds(2))
                    .until(() -> firstDispatcherStarts.get() >= 4);
            secondDispatcher.dispatchPending();
            first.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(attempts).hasSize(6);
        assertThat(attempts.values()).allSatisfy(count -> assertThat(count).hasValue(1));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE state = 'PUBLISHED' AND retry_count = 1
                """, Long.class)).isEqualTo(6L);
    }

    @Test
    void realConfirmPublishesOnceAndExactRoutesFanOutTheOriginalEventId() {
        UUID submitted = append(61L, 1L, 1L,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, "routes-submitted");
        UUID published = append(61L, 2L, 1L,
                DomainEventType.ARTICLE_REVISION_PUBLISHED, "routes-published");
        UUID rejected = append(61L, 3L, 1L,
                DomainEventType.ARTICLE_REVISION_REJECTED, "routes-rejected");
        UUID superseded = append(61L, 4L, 1L,
                DomainEventType.ARTICLE_REVISION_SUPERSEDED, "routes-superseded");
        UUID unpublished = append(61L, 5L, 1L,
                DomainEventType.ARTICLE_UNPUBLISHED, "routes-unpublished");
        UUID deleted = append(61L, 6L, 1L,
                DomainEventType.ARTICLE_DELETED, "routes-deleted");
        UUID chunkReindex = outboxService.append("ARTICLE_CHUNK_SET", 61L, 1L, 1L,
                DomainEventType.ARTICLE_CHUNK_REINDEX_REQUESTED, 1,
                objectMapper.createObjectNode().put("articleId", 61L),
                "ARTICLE_CHUNK_SET:61:1:1:ARTICLE_CHUNK_REINDEX_REQUESTED");

        dispatcher.dispatchPending();

        assertThat(eventIds(RabbitConfig.ARTICLE_MODERATION_QUEUE, 1)).containsExactly(submitted);
        assertThat(eventIds(RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE, 5))
                .containsExactly(published, rejected, superseded, unpublished, deleted);
        assertThat(eventIds(RabbitConfig.ARTICLE_CHUNK_FACT_QUEUE, 5))
                .containsExactly(published, rejected, superseded, unpublished, deleted);
        assertThat(eventIds(RabbitConfig.ARTICLE_CHUNK_ELASTICSEARCH_QUEUE, 1))
                .containsExactly(chunkReindex);
        assertThat(eventIds(RabbitConfig.ARTICLE_CHUNK_MILVUS_QUEUE, 1))
                .containsExactly(chunkReindex);
        assertThat(eventIds(RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE, 2))
                .containsExactly(published, rejected);
        EVENT_QUEUES.forEach(queue -> assertThat(rabbitTemplate.receive(queue, 100)).isNull());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE state='PUBLISHED'", Long.class))
                .isEqualTo(7L);
    }

    @Test
    void mandatoryPublishWithNoBindingIsAckedAndReturnedButNeverMarkedPublished() {
        UUID eventId = append(71L, 1L, 1L,
                DomainEventType.AGENT_TURN_REQUESTED, "unbound");

        dispatcher.dispatchPending();

        DomainEventOutbox row = outboxMapper.selectByEventId(eventId);
        assertThat(row.getState()).isEqualTo("PENDING");
        assertThat(row.getRetryCount()).isEqualTo(1);
        assertThat(row.getLastError()).contains("returned");
        assertThat(row.getPublishedAt()).isNull();
    }

    @Test
    void confirmedPublishFollowedByProcessCrashIsSafelyPublishedAgainAfterLeaseRecovery() throws Exception {
        UUID eventId = append(81L, 1L, 1L,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, "confirm-crash");
        DomainEventOutbox firstClaim = claimer.claimBatch(1, Duration.ofSeconds(30)).getFirst();

        publisher.publish(firstClaim.toEvent(objectMapper), firstClaim.getLeaseOwner());
        DomainEvent firstDelivery = receiveEvent(RabbitConfig.ARTICLE_MODERATION_QUEUE);
        assertThat(firstDelivery.eventId()).isEqualTo(eventId);
        assertThat(outboxMapper.selectByEventId(eventId).getState()).isEqualTo("IN_FLIGHT");

        expireOutboxLease(eventId);
        dispatcher.dispatchPending();

        DomainEvent duplicate = receiveEvent(RabbitConfig.ARTICLE_MODERATION_QUEUE);
        assertThat(duplicate.eventId()).isEqualTo(eventId);
        assertThat(outboxMapper.selectByEventId(eventId).getState()).isEqualTo("PUBLISHED");
        assertThat(outboxMapper.selectByEventId(eventId).getRetryCount()).isEqualTo(2);
    }

    @Test
    void recoveredOwnerCannotBeOverwrittenByOldOwnersLateAckNackOrDeadCompletion() {
        UUID eventId = append(91L, 1L, 1L,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, "lease-race");
        DomainEventOutbox ownerA = claimer.claimBatch(1, Duration.ofSeconds(30)).getFirst();
        expireOutboxLease(eventId);
        DomainEventOutbox ownerB = claimer.claimBatch(1, Duration.ofSeconds(30)).getFirst();

        assertThat(ownerB.getLeaseOwner()).isNotEqualTo(ownerA.getLeaseOwner());
        assertThat(ownerB.getRetryCount()).isEqualTo(2);
        assertThat(outboxMapper.markPublished(ownerA.getId(), ownerA.getLeaseOwner(), Instant.now())).isZero();
        assertThat(outboxMapper.markRetry(ownerA.getId(), ownerA.getLeaseOwner(), 1,
                Instant.now().plusSeconds(30), "old nack")).isZero();
        assertThat(outboxMapper.markDead(ownerA.getId(), ownerA.getLeaseOwner(), 1,
                "old dead", Instant.now())).isZero();

        DomainEventOutbox unchanged = outboxMapper.selectByEventId(eventId);
        assertThat(unchanged.getState()).isEqualTo("IN_FLIGHT");
        assertThat(unchanged.getLeaseOwner()).isEqualTo(ownerB.getLeaseOwner());
        assertThat(unchanged.getRetryCount()).isEqualTo(2);
        assertThat(unchanged.getLastError()).isNull();
        assertThat(unchanged.getPublishedAt()).isNull();
        assertThat(unchanged.getFailedAt()).isNull();
    }

    @Test
    void failuresConsumeAttemptsAtClaimAndTwelfthAttemptBecomesDeadWithSanitizedError() {
        UUID eventId = append(101L, 1L, 1L,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, "retry-bound");
        String unsafe = "broker\n\r\u0000" + "x".repeat(700);
        DomainEventOutboxDispatcher failing = new DomainEventOutboxDispatcher(
                claimer, outboxMapper, objectMapper,
                (event, owner) -> { throw new IllegalStateException(unsafe); },
                100, Duration.ofSeconds(30), 12);

        List<Long> observedDelays = new ArrayList<>();
        for (int attempt = 1; attempt <= 12; attempt++) {
            failing.dispatchPending();
            DomainEventOutbox row = outboxMapper.selectByEventId(eventId);
            assertThat(row.getRetryCount()).isEqualTo(attempt);
            if (attempt < 12) {
                assertThat(row.getState()).isEqualTo("PENDING");
                Long delay = jdbcTemplate.queryForObject("""
                        SELECT TIMESTAMPDIFF(SECOND, CURRENT_TIMESTAMP(6), next_attempt_at)
                        FROM domain_event_outbox WHERE event_id = ?
                        """, Long.class, uuidBytes(eventId));
                observedDelays.add(delay);
                assertThat(delay).isBetween(0L, 300L);
                jdbcTemplate.update("""
                        UPDATE domain_event_outbox
                        SET next_attempt_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                        WHERE event_id = ?
                        """, uuidBytes(eventId));
            }
        }

        DomainEventOutbox dead = outboxMapper.selectByEventId(eventId);
        assertThat(dead.getState()).isEqualTo("DEAD");
        assertThat(dead.getFailedAt()).isNotNull();
        assertThat(dead.getLastError()).hasSizeLessThanOrEqualTo(500)
                .doesNotContain("\n", "\r", "\u0000")
                .startsWith("IllegalStateException:");
        assertThat(observedDelays.getLast()).isLessThanOrEqualTo(300L);
    }

    private List<DomainEventOutbox> claimAfter(CyclicBarrier barrier, int limit) throws Exception {
        barrier.await();
        return claimer.claimBatch(limit, Duration.ofSeconds(30));
    }

    private void dispatchAfter(CyclicBarrier barrier, DomainEventOutboxDispatcher target) {
        try {
            barrier.await();
            target.dispatchPending();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private DomainEventOutboxDispatcher dispatcherWith(DomainEventPublisher targetPublisher) {
        return dispatcherWith(targetPublisher, 4, Duration.ofSeconds(30));
    }

    private DomainEventOutboxDispatcher dispatcherWith(DomainEventPublisher targetPublisher,
                                                         int batchSize,
                                                         Duration leaseDuration) {
        return new DomainEventOutboxDispatcher(claimer, outboxMapper, objectMapper,
                targetPublisher, batchSize, leaseDuration, 12);
    }

    private void assertCommittedClaim(DomainEvent event, String owner, Set<UUID> published) {
        DomainEventOutbox visible = outboxMapper.selectByEventId(event.eventId());
        assertThat(visible.getState()).isEqualTo("IN_FLIGHT");
        assertThat(visible.getLeaseOwner()).isEqualTo(owner);
        assertThat(published.add(event.eventId())).isTrue();
    }

    private UUID append(long articleId, long version, long lifecycle,
                        DomainEventType type, String suffix) {
        return outboxService.append("ARTICLE", articleId, version, lifecycle, type, 1,
                payload(articleId, suffix), dedupe(articleId, version, lifecycle, type, suffix));
    }

    private JsonNode payload(long articleId, String suffix) {
        return objectMapper.createObjectNode().put("articleId", articleId).put("marker", suffix);
    }

    private String dedupe(long articleId, long version, long lifecycle,
                          DomainEventType type, String suffix) {
        return "ARTICLE:" + articleId + ":" + lifecycle + ":" + version + ":" + type;
    }

    private long countRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM domain_event_outbox", Long.class);
    }

    private List<UUID> eventIds(String queue, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ids.add(receiveEvent(queue).eventId());
        }
        return ids;
    }

    private DomainEvent receiveEvent(String queue) {
        Object converted = rabbitTemplate.receiveAndConvert(queue, 3_000);
        assertThat(converted).as("message from " + queue).isInstanceOf(DomainEvent.class);
        return (DomainEvent) converted;
    }

    private void expireOutboxLease(UUID eventId) {
        jdbcTemplate.update("""
                UPDATE domain_event_outbox
                SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE event_id = ?
                """, uuidBytes(eventId));
    }

    private byte[] uuidBytes(UUID uuid) {
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(16);
        bytes.putLong(uuid.getMostSignificantBits());
        bytes.putLong(uuid.getLeastSignificantBits());
        return bytes.array();
    }

    private void purge(String queue) {
        if (amqpAdmin.getQueueProperties(queue) != null) {
            amqpAdmin.purgeQueue(queue, true);
        }
    }
}
