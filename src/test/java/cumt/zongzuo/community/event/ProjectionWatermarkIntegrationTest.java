package cumt.zongzuo.community.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import cumt.zongzuo.community.event.projection.ProjectionLeaseService;
import cumt.zongzuo.community.event.projection.ProjectionRepairLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionWatermarkIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private ProjectionLeaseService projectionLeaseService;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS projection_effect_probe (
                  effect_key VARCHAR(64) PRIMARY KEY,
                  result_hash CHAR(64) NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbcTemplate.update("DELETE FROM projection_effect_probe");
        jdbcTemplate.update("DELETE FROM consumer_inbox");
        jdbcTemplate.update("DELETE FROM projection_watermark");
    }

    @Test
    void duplicateOlderLifecycleAndSameLifecycleWatermarkAreDistinctNonRunnableDecisions() {
        DomainEvent applied = event(UUID.randomUUID(), 5L, 2L);
        ProjectionLease lease = acquire(applied);
        projectionLeaseService.complete(lease, applied, false, hash("applied"));

        assertThat(acquire(applied).decision()).isEqualTo(ProjectionLease.Decision.DUPLICATE);
        assertThat(acquire(event(UUID.randomUUID(), 99L, 1L)).decision())
                .isEqualTo(ProjectionLease.Decision.STALE);
        assertThat(acquire(event(UUID.randomUUID(), 5L, 2L)).decision())
                .isEqualTo(ProjectionLease.Decision.STALE);
        assertThat(acquire(event(UUID.randomUUID(), 4L, 2L)).decision())
                .isEqualTo(ProjectionLease.Decision.STALE);
    }

    @Test
    void activeAggregateLeaseReturnsBusyButHigherLifecycleCanRunAfterCompletion() {
        DomainEvent first = event(UUID.randomUUID(), 5L, 2L);
        ProjectionLease active = acquire(first);

        assertThat(acquire(event(UUID.randomUUID(), 6L, 2L)).decision())
                .isEqualTo(ProjectionLease.Decision.BUSY);

        projectionLeaseService.complete(active, first, false, hash("first"));
        DomainEvent newLifecycle = event(UUID.randomUUID(), 1L, 3L);
        ProjectionLease restarted = acquire(newLifecycle);
        assertThat(restarted.acquired()).isTrue();
        projectionLeaseService.complete(restarted, newLifecycle, false, hash("new-lifecycle"));

        assertWatermark(1L, 3L, false);
    }

    @Test
    void sameLifecycleHigherVersionClearsTombstoneAndDelayedDeleteVersionsStayStale() {
        AtomicInteger callbacks = new AtomicInteger();
        DomainEvent deleteV5 = event(UUID.randomUUID(), 5L, 4L);
        ProjectionLease deleteLease = acquire(deleteV5);
        callbacks.incrementAndGet();
        projectionLeaseService.complete(deleteLease, deleteV5, true, hash("delete-v5"));
        assertWatermark(5L, 4L, true);

        DomainEvent restoreV6 = event(UUID.randomUUID(), 6L, 4L);
        ProjectionLease restoreLease = acquire(restoreV6);
        assertThat(restoreLease.acquired()).isTrue();
        callbacks.incrementAndGet();
        projectionLeaseService.complete(restoreLease, restoreV6, false, hash("restore-v6"));
        assertWatermark(6L, 4L, false);

        assertThat(acquire(event(UUID.randomUUID(), 5L, 4L)).decision())
                .isEqualTo(ProjectionLease.Decision.STALE);
        assertThat(acquire(event(UUID.randomUUID(), 4L, 4L)).decision())
                .isEqualTo(ProjectionLease.Decision.STALE);
        assertThat(callbacks).hasValue(2);
    }

    @Test
    void lostOldOwnerCompletionWritesNeitherInboxNorWatermark() {
        DomainEvent event = event(UUID.randomUUID(), 7L, 2L);
        ProjectionLease ownerA = acquire(event);
        expireProjectionLease();
        ProjectionLease ownerB = acquire(event);
        assertThat(ownerB.leaseOwner()).isNotEqualTo(ownerA.leaseOwner());

        assertThatThrownBy(() -> projectionLeaseService.complete(
                ownerA, event, false, hash("old-owner")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost");
        assertThat(inboxCount()).isZero();
        assertWatermark(0L, 0L, false);

        projectionLeaseService.complete(ownerB, event, false, hash("new-owner"));
        assertThat(inboxCount()).isEqualTo(1L);
        assertWatermark(7L, 2L, false);
    }

    @Test
    void crashAfterIdempotentEffectReplaysCallbackBeforeInboxAndCompletesExactlyOnce() {
        DomainEvent event = event(UUID.randomUUID(), 8L, 2L);
        AtomicInteger callbacks = new AtomicInteger();
        ProjectionLease first = acquire(event);

        runIdempotentEffect(callbacks, "article-41", hash("effect"));
        assertThat(inboxCount()).isZero();
        assertWatermark(0L, 0L, false);
        assertThat(effectRowCount()).isEqualTo(1L);

        expireProjectionLease();
        ProjectionLease recovered = acquire(event);
        assertThat(recovered.acquired()).isTrue();
        runIdempotentEffect(callbacks, "article-41", hash("effect"));
        projectionLeaseService.complete(recovered, event, false, hash("effect"));

        assertThat(callbacks).hasValue(2);
        assertThat(effectRowCount()).isEqualTo(1L);
        assertThat(inboxCount()).isEqualTo(1L);
        assertWatermark(8L, 2L, false);
        assertThat(first.leaseOwner()).isNotEqualTo(recovered.leaseOwner());
    }

    @Test
    void renewalAndOwnershipAssertionUseDatabaseTimeAndRejectAnExpiredOwner() {
        DomainEvent event = event(UUID.randomUUID(), 9L, 3L);
        ProjectionLease lease = projectionLeaseService.acquire(
                "article-search", event, Duration.ofMillis(250));

        projectionLeaseService.renew(lease, Duration.ofSeconds(30));
        projectionLeaseService.assertOwned(lease);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lease_until > TIMESTAMPADD(SECOND, 20, CURRENT_TIMESTAMP(6))
                FROM projection_watermark
                WHERE consumer_name = 'article-search' AND aggregate_type = 'ARTICLE'
                  AND aggregate_id = 41
                """, Boolean.class)).isTrue();

        expireProjectionLease();
        assertThatThrownBy(() -> projectionLeaseService.assertOwned(lease))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost");
        assertThatThrownBy(() -> projectionLeaseService.renew(lease, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost");
    }

    @Test
    void equalWatermarkRepairUsesTheAggregateLeaseWithoutAdvancingWatermarkOrInbox() {
        DomainEvent applied = event(UUID.randomUUID(), 12L, 4L);
        ProjectionLease eventLease = acquire(applied);
        projectionLeaseService.complete(eventLease, applied, false, hash("applied"));

        ProjectionRepairLease repair = projectionLeaseService.acquireRepair(
                "article-search", "ARTICLE", 41L, 12L, 4L, Duration.ofSeconds(30));
        assertThat(repair.acquired()).isTrue();
        assertThat(projectionLeaseService.acquireRepair(
                "article-search", "ARTICLE", 41L, 12L, 4L, Duration.ofSeconds(30)).decision())
                .isEqualTo(ProjectionRepairLease.Decision.BUSY);

        projectionLeaseService.assertOwned(repair);
        projectionLeaseService.completeRepair(repair);

        assertWatermark(12L, 4L, false);
        assertThat(inboxCount()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT lease_owner, lease_until FROM projection_watermark
                WHERE consumer_name = 'article-search' AND aggregate_type = 'ARTICLE'
                  AND aggregate_id = 41
                """)).containsEntry("lease_owner", null).containsEntry("lease_until", null);
    }

    @Test
    void repairCannotUseAnOlderTupleOrCompleteAfterAReplacementOwnerAdvancesTruth() {
        DomainEvent applied = event(UUID.randomUUID(), 15L, 5L);
        ProjectionLease appliedLease = acquire(applied);
        projectionLeaseService.complete(appliedLease, applied, false, hash("applied"));

        assertThat(projectionLeaseService.acquireRepair(
                "article-search", "ARTICLE", 41L, 14L, 5L, Duration.ofSeconds(30)).decision())
                .isEqualTo(ProjectionRepairLease.Decision.STALE);

        ProjectionRepairLease oldRepair = projectionLeaseService.acquireRepair(
                "article-search", "ARTICLE", 41L, 15L, 5L, Duration.ofSeconds(30));
        expireProjectionLease();
        DomainEvent newer = event(UUID.randomUUID(), 16L, 5L);
        ProjectionLease newOwner = acquire(newer);
        projectionLeaseService.complete(newOwner, newer, false, hash("newer"));

        assertThatThrownBy(() -> projectionLeaseService.completeRepair(oldRepair))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lost");
        assertWatermark(16L, 5L, false);
        assertThat(inboxCount()).isEqualTo(2L);
    }

    private ProjectionLease acquire(DomainEvent event) {
        return projectionLeaseService.acquire("article-search", event, Duration.ofSeconds(30));
    }

    private DomainEvent event(UUID eventId, long version, long lifecycle) {
        return new DomainEvent(eventId, "ARTICLE", 41L, version, lifecycle,
                DomainEventType.ARTICLE_REVISION_PUBLISHED, 1,
                objectMapper.createObjectNode().put("revisionId", version), Instant.now());
    }

    private void runIdempotentEffect(AtomicInteger callbacks, String key, String resultHash) {
        callbacks.incrementAndGet();
        jdbcTemplate.update("""
                INSERT INTO projection_effect_probe (effect_key, result_hash)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE result_hash = VALUES(result_hash)
                """, key, resultHash);
    }

    private void expireProjectionLease() {
        jdbcTemplate.update("""
                UPDATE projection_watermark
                SET lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
                WHERE consumer_name = 'article-search' AND aggregate_type = 'ARTICLE'
                  AND aggregate_id = 41
                """);
    }

    private void assertWatermark(long version, long lifecycle, boolean tombstone) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT last_applied_version, lifecycle_epoch, tombstone
                FROM projection_watermark
                WHERE consumer_name = 'article-search' AND aggregate_type = 'ARTICLE'
                  AND aggregate_id = 41
                """))
                .containsEntry("last_applied_version", version)
                .containsEntry("lifecycle_epoch", lifecycle)
                .containsEntry("tombstone", tombstone);
    }

    private long inboxCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumer_inbox", Long.class);
    }

    private long effectRowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM projection_effect_probe", Long.class);
    }

    private String hash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
