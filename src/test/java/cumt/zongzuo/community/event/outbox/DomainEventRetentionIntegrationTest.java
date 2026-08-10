package cumt.zongzuo.community.event.outbox;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.article.projection.ArticleProjectionConsumers;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.UUID;
import java.util.TimeZone;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventRetentionIntegrationTest extends IntegrationTestSupport {

    private static final Instant RUN_AT = Instant.parse("2026-08-10T12:00:00Z");

    @Autowired
    private DomainEventRetentionTask retention;
    @Autowired
    private DomainEventDeadLetterOperator deadLetterOperator;
    @Autowired
    private DomainEventOutboxMapper outboxMapper;
    @Autowired
    private DomainEventRetentionMapper retentionMapper;
    @Autowired
    private DomainEventRetentionMetrics retentionMetrics;
    @Autowired
    private DomainEventRetentionBacklogObserver backlogObserver;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void cleanRetentionState() {
        jdbcTemplate.update("DELETE FROM consumer_inbox");
        jdbcTemplate.update("DELETE FROM projection_watermark");
        jdbcTemplate.update("DELETE FROM domain_event_outbox");
        jdbcTemplate.update("DELETE FROM article_revision_migration_issue");
        jdbcTemplate.update("DELETE FROM article WHERE id BETWEEN 9800 AND 9899");
    }

    @Test
    void strictCutoffsLeasesResolutionAndSearchTombstoneWatermarksGateDeletion() {
        insertOutbox(101, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 101, 1, 1,
                daysBefore(8), null, null, null, null, null, null);
        insertOutbox(102, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 102, 1, 1,
                daysBefore(7), null, null, null, null, null, null);
        insertOutbox(103, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 103, 1, 1,
                daysBefore(8), null, null, null, "leased", null, null);
        insertOutbox(104, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 104, 1, 1,
                daysBefore(8), null, null, null, null, RUN_AT.minusSeconds(1), null);

        insertOutbox(201, "PUBLISHED", "ARTICLE_DELETED", 201, 5, 3,
                daysBefore(8), null, null, null, null, null, null);
        watermark(201, 1, 4, false);
        insertOutbox(202, "PUBLISHED", "ARTICLE_UNPUBLISHED", 202, 5, 3,
                daysBefore(8), null, null, null, null, null, null);
        watermark(202, 6, 3, false);
        insertOutbox(203, "PUBLISHED", "ARTICLE_DELETED", 203, 5, 3,
                daysBefore(8), null, null, null, null, null, null);
        watermark(203, 5, 3, true);
        insertOutbox(204, "PUBLISHED", "ARTICLE_UNPUBLISHED", 204, 5, 3,
                daysBefore(8), null, null, null, null, null, null);
        watermark(204, 5, 3, false);
        insertOutbox(205, "PUBLISHED", "ARTICLE_DELETED", 205, 5, 3,
                daysBefore(8), null, null, null, null, null, null);
        watermark(205, 99, 2, true);

        insertOutbox(301, "DEAD", "ARTICLE_REVISION_PUBLISHED", 301, 1, 1,
                null, null, null, null, null, null, daysBefore(100));
        insertOutbox(302, "DEAD", "ARTICLE_REVISION_PUBLISHED", 302, 1, 1,
                null, daysBefore(91), "operator", "ACKNOWLEDGED", null, null,
                daysBefore(100));
        insertOutbox(303, "DEAD", "ARTICLE_REVISION_PUBLISHED", 303, 1, 1,
                null, daysBefore(90), "operator", "ACKNOWLEDGED", null, null,
                daysBefore(100));

        UUID oldInbox = UUID.randomUUID();
        UUID boundaryInbox = UUID.randomUUID();
        insertInbox("retention-consumer", oldInbox, daysBefore(31));
        insertInbox("retention-consumer", boundaryInbox, daysBefore(30));

        jdbcTemplate.update("INSERT INTO article(id,title,author_id) VALUES (9801,'retention',9800)");
        insertMigrationIssue(9801, "OLD_RESOLVED", daysBefore(91));
        insertMigrationIssue(9801, "BOUNDARY_RESOLVED", daysBefore(90));
        insertMigrationIssue(9801, "UNRESOLVED", null);

        DomainEventRetentionResult result = retention.runOnceAt(utc(RUN_AT));

        assertThat(result.publishedDeleted()).isEqualTo(4);
        assertThat(result.resolvedDeadDeleted()).isOne();
        assertThat(result.inboxDeleted()).isOne();
        assertThat(result.resolvedMigrationIssueDeleted()).isOne();
        assertThat(outboxIds()).containsExactlyInAnyOrder(102L, 103L, 104L, 204L, 205L, 301L, 303L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM consumer_inbox
                WHERE event_id=UUID_TO_BIN(?)
                """, Long.class, boundaryInbox.toString())).isOne();
        assertThat(jdbcTemplate.queryForList("""
                SELECT issue_code FROM article_revision_migration_issue ORDER BY issue_code
                """, String.class)).containsExactly("BOUNDARY_RESOLVED", "UNRESOLVED");
    }

    @Test
    void deadOperatorFactsAreSingleUseAndRequeuedPublicationsWaitNinetyDays() {
        insertOutbox(401, "DEAD", "ARTICLE_REVISION_PUBLISHED", 401, 1, 1,
                null, null, null, null, null, null, daysBefore(5));

        deadLetterOperator.requeueDead(401, "operator-a");

        assertThat(jdbcTemplate.queryForMap("""
                SELECT state,retry_count,failed_at,lease_owner,lease_until,
                       dead_resolved_by,dead_resolution
                FROM domain_event_outbox WHERE id=401
                """))
                .containsEntry("state", "PENDING")
                .containsEntry("retry_count", 0)
                .containsEntry("failed_at", null)
                .containsEntry("lease_owner", null)
                .containsEntry("lease_until", null)
                .containsEntry("dead_resolved_by", "operator-a")
                .containsEntry("dead_resolution", "REQUEUED");
        assertThatThrownBy(() -> deadLetterOperator.requeueDead(401, "operator-a"))
                .hasMessageContaining("CAS");

        jdbcTemplate.update("""
                UPDATE domain_event_outbox
                SET state='PUBLISHED',published_at=?,next_attempt_at=?,lease_owner=NULL,lease_until=NULL
                WHERE id=401
                """, utc(daysBefore(8)), utc(daysBefore(8)));
        assertThat(retention.runOnceAt(utc(RUN_AT)).publishedDeleted()).isZero();
        assertThat(outboxIds()).contains(401L);

        jdbcTemplate.update("UPDATE domain_event_outbox SET dead_resolved_at=? WHERE id=401",
                utc(daysBefore(91)));
        DomainEventRetentionResult oldRequeue = retention.runOnceAt(utc(RUN_AT));
        assertThat(oldRequeue.requeuedPublishedDeleted()).isOne();
        assertThat(outboxIds()).doesNotContain(401L);

        insertOutbox(404, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 404, 1, 1,
                daysBefore(1), daysBefore(91), "operator-d", "REQUEUED",
                null, null, null);
        assertThat(retention.runOnceAt(utc(RUN_AT)).requeuedPublishedDeleted()).isZero();
        assertThat(outboxIds()).contains(404L);
        jdbcTemplate.update("UPDATE domain_event_outbox SET published_at=? WHERE id=404",
                utc(daysBefore(8)));
        assertThat(retention.runOnceAt(utc(RUN_AT)).requeuedPublishedDeleted()).isOne();
        assertThat(outboxIds()).doesNotContain(404L);

        insertOutbox(402, "DEAD", "ARTICLE_REVISION_PUBLISHED", 402, 1, 1,
                null, null, null, null, null, null, daysBefore(5));
        deadLetterOperator.acknowledgeDead(402, "operator-b");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state,dead_resolved_at,dead_resolved_by,dead_resolution
                FROM domain_event_outbox WHERE id=402
                """))
                .containsEntry("state", "DEAD")
                .containsEntry("dead_resolved_by", "operator-b")
                .containsEntry("dead_resolution", "ACKNOWLEDGED");
        assertThatThrownBy(() -> deadLetterOperator.acknowledgeDead(402, "operator-b"))
                .hasMessageContaining("CAS");

        insertOutbox(403, "DEAD", "ARTICLE_REVISION_PUBLISHED", 403, 1, 1,
                null, null, null, null, null, null, daysBefore(5));
        deadLetterOperator.requeueDead(403, "operator-c");
        jdbcTemplate.update("""
                UPDATE domain_event_outbox
                SET state='IN_FLIGHT',retry_count=12,lease_owner='dispatcher',lease_until=?
                WHERE id=403
                """, utc(RUN_AT.plusSeconds(30)));
        assertThat(outboxMapper.markDead(
                403, "dispatcher", 12, "failed again", RUN_AT)).isOne();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state,dead_resolved_at,dead_resolved_by,dead_resolution
                FROM domain_event_outbox WHERE id=403
                """))
                .containsEntry("state", "DEAD")
                .containsEntry("dead_resolved_at", null)
                .containsEntry("dead_resolved_by", null)
                .containsEntry("dead_resolution", null);
    }

    @Test
    void boundedBatchesUseKeysetOrderAcrossSparseIds() {
        for (long id : new long[]{501, 900, 2_001, 9_001, 90_001}) {
            insertOutbox(id, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", id, 1, 1,
                    daysBefore(8), null, null, null, null, null, null);
        }
        DomainEventRetentionTask bounded = retentionTask(2, 2);

        assertThat(bounded.runOnceAt(utc(RUN_AT)).publishedDeleted()).isEqualTo(4);
        assertThat(outboxIds()).containsExactly(90_001L);

        assertThat(bounded.runOnceAt(utc(RUN_AT)).publishedDeleted()).isOne();
        assertThat(outboxIds()).isEmpty();
    }

    @Test
    void lockedCandidateIsSkippedAndAnUnlockedIdBehindItStillDeletes() throws Exception {
        insertOutbox(601, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 601, 1, 1,
                daysBefore(8), null, null, null, null, null, null);
        insertOutbox(602, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 602, 1, 1,
                daysBefore(8), null, null, null, null, null, null);

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "SELECT id FROM domain_event_outbox WHERE id=601 FOR UPDATE");
                 var locked = statement.executeQuery()) {
                assertThat(locked.next()).isTrue();
                assertThat(retentionTask(1, 1).runOnceAt(utc(RUN_AT)).publishedDeleted()).isOne();
                assertThat(outboxIds()).containsExactly(601L);
            } finally {
                blocker.rollback();
            }
        }

        assertThat(retentionTask(1, 1).runOnceAt(utc(RUN_AT)).publishedDeleted()).isOne();
        assertThat(outboxIds()).isEmpty();
    }

    @Test
    void productionRunUsesDatabaseLocalTimeAcrossNonUtcJvmAndSession() throws Exception {
        TimeZone previousJvmZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")));
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbcTemplate.execute("SET SESSION time_zone='+08:00'");
                        try {
                            LocalDateTime databaseNow = outboxMapper.selectDatabaseLocalNow();
                            insertOutbox(611, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 611, 1, 1,
                                    null, null, null, null, null, null, null);
                            insertOutbox(612, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 612, 1, 1,
                                    null, null, null, null, null, null, null);
                            jdbcTemplate.update(
                                    "UPDATE domain_event_outbox SET published_at=? WHERE id=611",
                                    databaseNow.minusDays(7).minusHours(1));
                            jdbcTemplate.update(
                                    "UPDATE domain_event_outbox SET published_at=? WHERE id=612",
                                    databaseNow.minusDays(7).plusHours(1));

                            assertThat(retention.runOnce().publishedDeleted()).isOne();
                            assertThat(outboxIds()).containsExactly(612L);
                        } finally {
                            jdbcTemplate.execute("SET SESSION time_zone='+00:00'");
                        }
                    });
        } finally {
            TimeZone.setDefault(previousJvmZone);
        }
    }

    @Test
    void batchDeletesRecheckStatusLeaseCutoffsAndTombstoneWatermark() {
        insertOutbox(801, "PENDING", "ARTICLE_REVISION_PUBLISHED", 801, 1, 1,
                daysBefore(8), null, null, null, null, null, null);
        insertOutbox(802, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 802, 1, 1,
                daysBefore(8), null, null, null, "lease", null, null);
        insertOutbox(803, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 803, 1, 1,
                daysBefore(7), null, null, null, null, null, null);
        insertOutbox(804, "PUBLISHED", "ARTICLE_DELETED", 804, 2, 3,
                daysBefore(8), null, null, null, null, null, null);
        insertOutbox(805, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 805, 1, 1,
                daysBefore(8), null, null, null, null, null, null);
        LocalDateTime cutoff = utc(daysBefore(7));

        assertThat(retentionMapper.deletePublishedBatchExact(
                java.util.List.of(801L, 802L, 803L, 804L, 805L), cutoff,
                ArticleProjectionConsumers.SEARCH_CURRENT_POINTER)).isOne();
        assertThat(outboxIds()).containsExactly(801L, 802L, 803L, 804L);

        insertOutbox(806, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 806, 1, 1,
                daysBefore(1), daysBefore(91), "operator", "REQUEUED", null, null, null);
        insertOutbox(807, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 807, 1, 1,
                daysBefore(8), daysBefore(90), "operator", "REQUEUED", null, null, null);
        insertOutbox(808, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 808, 1, 1,
                daysBefore(8), daysBefore(91), "operator", "REQUEUED", null, null, null);
        assertThat(retentionMapper.deleteRequeuedPublishedBatchExact(
                java.util.List.of(806L, 807L, 808L), utc(daysBefore(90)), cutoff,
                ArticleProjectionConsumers.SEARCH_CURRENT_POINTER)).isOne();
        assertThat(outboxIds()).contains(806L, 807L).doesNotContain(808L);

        insertOutbox(809, "DEAD", "ARTICLE_REVISION_PUBLISHED", 809, 1, 1,
                null, daysBefore(91), "operator", "ACKNOWLEDGED", "lease", null,
                daysBefore(100));
        insertOutbox(810, "DEAD", "ARTICLE_REVISION_PUBLISHED", 810, 1, 1,
                null, null, null, null, null, null, daysBefore(100));
        insertOutbox(811, "DEAD", "ARTICLE_REVISION_PUBLISHED", 811, 1, 1,
                null, daysBefore(91), "operator", "ACKNOWLEDGED", null, null,
                daysBefore(100));
        assertThat(retentionMapper.deleteResolvedDeadBatchExact(
                java.util.List.of(809L, 810L, 811L), utc(daysBefore(90)),
                ArticleProjectionConsumers.SEARCH_CURRENT_POINTER)).isOne();
        assertThat(outboxIds()).contains(809L, 810L).doesNotContain(811L);

        UUID oldInbox = UUID.randomUUID();
        UUID boundaryInbox = UUID.randomUUID();
        insertInbox("batch", oldInbox, daysBefore(31));
        insertInbox("batch", boundaryInbox, daysBefore(30));
        ConsumerInboxRetentionKey oldKey = new ConsumerInboxRetentionKey();
        oldKey.setConsumerName("batch");
        oldKey.setEventId(oldInbox);
        ConsumerInboxRetentionKey boundaryKey = new ConsumerInboxRetentionKey();
        boundaryKey.setConsumerName("batch");
        boundaryKey.setEventId(boundaryInbox);
        assertThat(retentionMapper.deleteInboxBatchExact(
                java.util.List.of(oldKey, boundaryKey), utc(daysBefore(30)))).isOne();

        jdbcTemplate.update("INSERT INTO article(id,title,author_id) VALUES (9803,'batch',9800)");
        insertMigrationIssue(9803, "BATCH_OLD", daysBefore(91));
        insertMigrationIssue(9803, "BATCH_BOUNDARY", daysBefore(90));
        java.util.List<Long> issueIds = jdbcTemplate.queryForList("""
                SELECT id FROM article_revision_migration_issue
                WHERE article_id=9803 ORDER BY id
                """, Long.class);
        assertThat(retentionMapper.deleteResolvedMigrationIssueBatchExact(
                issueIds, utc(daysBefore(90)))).isOne();
        assertThat(jdbcTemplate.queryForList("""
                SELECT issue_code FROM article_revision_migration_issue WHERE article_id=9803
                """, String.class)).containsExactly("BATCH_BOUNDARY");
    }

    @Test
    void acknowledgedDeadTombstonesWaitForTheSearchWatermarkBeforeRetention() {
        insertOutbox(820, "DEAD", "ARTICLE_DELETED", 820, 5, 3,
                null, daysBefore(91), "operator", "ACKNOWLEDGED",
                null, null, daysBefore(100));
        insertOutbox(821, "DEAD", "ARTICLE_DELETED", 821, 5, 3,
                null, daysBefore(91), "operator", "ACKNOWLEDGED",
                null, null, daysBefore(100));
        watermark(821, 4, 3, false);
        insertOutbox(822, "DEAD", "ARTICLE_UNPUBLISHED", 822, 5, 3,
                null, daysBefore(91), "operator", "ACKNOWLEDGED",
                null, null, daysBefore(100));
        watermark(822, 5, 3, false);
        insertOutbox(823, "DEAD", "ARTICLE_DELETED", 823, 5, 3,
                null, daysBefore(91), "operator", "ACKNOWLEDGED",
                null, null, daysBefore(100));
        watermark(823, 5, 3, true);
        insertOutbox(824, "DEAD", "ARTICLE_UNPUBLISHED", 824, 5, 3,
                null, daysBefore(91), "operator", "ACKNOWLEDGED",
                null, null, daysBefore(100));
        watermark(824, 1, 4, false);
        LocalDateTime cutoff = utc(daysBefore(90));

        assertThat(retentionMapper.selectResolvedDeadForRetention(
                cutoff, ArticleProjectionConsumers.SEARCH_CURRENT_POINTER, 20))
                .containsExactly(823L, 824L);
        assertThat(retentionMapper.deleteResolvedDeadBatchExact(
                java.util.List.of(820L, 821L, 822L, 823L, 824L), cutoff,
                ArticleProjectionConsumers.SEARCH_CURRENT_POINTER)).isEqualTo(2);
        assertThat(outboxIds()).containsExactly(820L, 821L, 822L);
    }

    @Test
    void activeOrCorruptLeaseCannotBeOperatorResolved() {
        insertOutbox(701, "DEAD", "ARTICLE_REVISION_PUBLISHED", 701, 1, 1,
                null, null, null, null, "dispatcher", null, daysBefore(100));
        insertOutbox(702, "DEAD", "ARTICLE_REVISION_PUBLISHED", 702, 1, 1,
                null, null, null, null, null, RUN_AT.plusSeconds(30), daysBefore(100));

        assertThatThrownBy(() -> deadLetterOperator.acknowledgeDead(701, "operator"))
                .hasMessageContaining("CAS");
        assertThatThrownBy(() -> deadLetterOperator.requeueDead(702, "operator"))
                .hasMessageContaining("CAS");
        assertThatThrownBy(() -> deadLetterOperator.acknowledgeDead(701, "operator with spaces"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForList("""
                SELECT id FROM domain_event_outbox
                WHERE state='DEAD' AND dead_resolved_at IS NULL
                  AND dead_resolved_by IS NULL AND dead_resolution IS NULL
                ORDER BY id
                """, Long.class)).containsExactly(701L, 702L);
    }

    @Test
    void retentionQueriesUseTheFourPurposeBuiltIndexes() {
        assertExplainUses("""
                SELECT id FROM domain_event_outbox
                    FORCE INDEX (idx_domain_outbox_published_retention)
                WHERE state='PUBLISHED' AND published_at<'2026-08-03 12:00:00'
                  AND lease_owner IS NULL AND lease_until IS NULL
                  AND dead_resolved_at IS NULL AND dead_resolved_by IS NULL
                  AND dead_resolution IS NULL
                  AND (event_type NOT IN ('ARTICLE_DELETED','ARTICLE_UNPUBLISHED') OR EXISTS (
                    SELECT 1 FROM projection_watermark w
                    WHERE w.consumer_name='article-search-current-pointer'
                      AND w.aggregate_type=domain_event_outbox.aggregate_type
                      AND w.aggregate_id=domain_event_outbox.aggregate_id
                      AND (w.lifecycle_epoch>domain_event_outbox.lifecycle_epoch
                        OR (w.lifecycle_epoch=domain_event_outbox.lifecycle_epoch
                            AND w.last_applied_version>domain_event_outbox.aggregate_version)
                        OR (w.lifecycle_epoch=domain_event_outbox.lifecycle_epoch
                            AND w.last_applied_version=domain_event_outbox.aggregate_version
                            AND w.tombstone=1)))
                  )
                ORDER BY published_at,id LIMIT 200
                """, "domain_event_outbox", "idx_domain_outbox_published_retention");
        assertExplainUses("""
                SELECT id FROM domain_event_outbox FORCE INDEX (idx_domain_outbox_dead_retention)
                WHERE state='PUBLISHED' AND dead_resolved_at<'2026-05-12 12:00:00'
                  AND published_at<'2026-08-03 12:00:00'
                  AND dead_resolved_by IS NOT NULL AND dead_resolution='REQUEUED'
                  AND lease_owner IS NULL AND lease_until IS NULL
                ORDER BY dead_resolved_at,id LIMIT 200
                """, "domain_event_outbox", "idx_domain_outbox_dead_retention");
        assertExplainUses("""
                SELECT id FROM domain_event_outbox FORCE INDEX (idx_domain_outbox_dead_retention)
                WHERE state='DEAD' AND dead_resolved_at<'2026-05-12 12:00:00'
                  AND dead_resolved_by IS NOT NULL AND dead_resolution='ACKNOWLEDGED'
                  AND lease_owner IS NULL AND lease_until IS NULL
                  AND (event_type NOT IN ('ARTICLE_DELETED','ARTICLE_UNPUBLISHED') OR EXISTS (
                    SELECT 1 FROM projection_watermark w
                    WHERE w.consumer_name='article-search-current-pointer'
                      AND w.aggregate_type=domain_event_outbox.aggregate_type
                      AND w.aggregate_id=domain_event_outbox.aggregate_id
                      AND (w.lifecycle_epoch>domain_event_outbox.lifecycle_epoch
                        OR (w.lifecycle_epoch=domain_event_outbox.lifecycle_epoch
                            AND w.last_applied_version>domain_event_outbox.aggregate_version)
                        OR (w.lifecycle_epoch=domain_event_outbox.lifecycle_epoch
                            AND w.last_applied_version=domain_event_outbox.aggregate_version
                            AND w.tombstone=1)))
                  )
                ORDER BY dead_resolved_at,id LIMIT 200
                """, "domain_event_outbox", "idx_domain_outbox_dead_retention");
        assertExplainUses("""
                SELECT consumer_name,event_id FROM consumer_inbox
                    FORCE INDEX (idx_consumer_inbox_retention)
                WHERE processed_at<'2026-07-11 12:00:00'
                ORDER BY processed_at,consumer_name,event_id LIMIT 200
                """, "consumer_inbox", "idx_consumer_inbox_retention");
        assertExplainUses("""
                SELECT id FROM article_revision_migration_issue
                    FORCE INDEX (idx_revision_migration_retention)
                WHERE resolved_at<'2026-05-12 12:00:00'
                ORDER BY resolved_at,id LIMIT 200
                """, "article_revision_migration_issue", "idx_revision_migration_retention");
    }

    @Test
    void deletionMetricsUseOnlyFiveFixedKindTags() {
        java.util.Map<String, Double> before = java.util.Map.of(
                "published", metric("published"),
                "requeued_published", metric("requeued_published"),
                "resolved_dead", metric("resolved_dead"),
                "inbox", metric("inbox"),
                "migration_issue", metric("migration_issue"));
        insertOutbox(901, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 901, 1, 1,
                daysBefore(8), null, null, null, null, null, null);
        insertOutbox(902, "PUBLISHED", "ARTICLE_REVISION_PUBLISHED", 902, 1, 1,
                daysBefore(8), daysBefore(91), "operator", "REQUEUED", null, null, null);
        insertOutbox(903, "DEAD", "ARTICLE_REVISION_PUBLISHED", 903, 1, 1,
                null, daysBefore(91), "operator", "ACKNOWLEDGED", null, null, daysBefore(100));
        insertInbox("metrics-consumer", UUID.randomUUID(), daysBefore(31));
        jdbcTemplate.update("INSERT INTO article(id,title,author_id) VALUES (9802,'metrics',9800)");
        insertMigrationIssue(9802, "METRICS_RESOLVED", daysBefore(91));

        retention.runOnceAt(utc(RUN_AT));

        before.forEach((kind, count) -> assertThat(metric(kind)).isEqualTo(count + 1.0));
        assertThat(meterRegistry.find("domain.event.retention.deleted").counters().stream()
                .map(counter -> counter.getId().getTag("kind"))
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("published", "requeued_published", "resolved_dead",
                        "inbox", "migration_issue");
    }

    @Test
    void backlogGaugesExposeUnresolvedDeadCountAndOldestPendingAgeWithoutIdTags() {
        insertOutbox(911, "DEAD", "ARTICLE_REVISION_PUBLISHED", 911, 1, 1,
                null, null, null, null, null, null, daysBefore(100));
        insertOutbox(912, "PENDING", "ARTICLE_REVISION_PUBLISHED", 912, 1, 1,
                null, null, null, null, null, null, null);
        LocalDateTime databaseNow = outboxMapper.selectDatabaseLocalNow();
        jdbcTemplate.update("UPDATE domain_event_outbox SET created_at=? WHERE id=912",
                databaseNow.minusDays(120));

        backlogObserver.observe();

        assertThat(meterRegistry.get("domain.event.retention.unresolved.dead.count")
                .gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("domain.event.outbox.oldest.pending.age.seconds")
                .gauge().value()).isBetween(120 * 86_400.0, 120 * 86_400.0 + 2.0);
        assertThat(meterRegistry.find("domain.event.retention.unresolved.dead.count")
                .gauges()).allSatisfy(gauge -> assertThat(gauge.getId().getTags()).isEmpty());
        assertThat(meterRegistry.find("domain.event.outbox.oldest.pending.age.seconds")
                .gauges()).allSatisfy(gauge -> assertThat(gauge.getId().getTags()).isEmpty());
        assertThat(outboxIds()).containsExactly(911L, 912L);
    }

    private void insertOutbox(long id, String state, String eventType,
                              long aggregateId, long version, long epoch,
                              Instant publishedAt, Instant deadResolvedAt,
                              String deadResolvedBy, String deadResolution,
                              String leaseOwner, Instant leaseUntil, Instant failedAt) {
        jdbcTemplate.update("""
                INSERT INTO domain_event_outbox
                    (id,event_id,aggregate_type,aggregate_id,aggregate_version,lifecycle_epoch,
                     event_type,payload_version,payload_json,dedupe_key,occurred_at,state,
                     retry_count,next_attempt_at,lease_owner,lease_until,last_error,created_at,
                     published_at,failed_at,dead_resolved_at,dead_resolved_by,dead_resolution)
                VALUES (?,UUID_TO_BIN(?),'ARTICLE',?,?,?,?,1,JSON_OBJECT(),?,?,?,12,?,?,?,?,?,?,?,?,?,?)
                """, id, UUID.randomUUID().toString(), aggregateId, version, epoch, eventType,
                "retention-" + id, utc(daysBefore(120)), state, utc(daysBefore(120)),
                leaseOwner, utc(leaseUntil), state.equals("DEAD") ? "failed" : null,
                utc(daysBefore(120)), utc(publishedAt), utc(failedAt), utc(deadResolvedAt),
                deadResolvedBy, deadResolution);
    }

    private void watermark(long aggregateId, long version, long epoch, boolean tombstone) {
        jdbcTemplate.update("""
                INSERT INTO projection_watermark
                    (consumer_name,aggregate_type,aggregate_id,last_applied_version,
                     lifecycle_epoch,tombstone,updated_at)
                VALUES (?,'ARTICLE',?,?,?,?,?)
                """, ArticleProjectionConsumers.SEARCH_CURRENT_POINTER, aggregateId, version, epoch,
                tombstone, utc(RUN_AT));
    }

    private void insertInbox(String consumer, UUID eventId, Instant processedAt) {
        jdbcTemplate.update("""
                INSERT INTO consumer_inbox(consumer_name,event_id,processed_at,result_hash)
                VALUES (?,UUID_TO_BIN(?),?,REPEAT('a',64))
                """, consumer, eventId.toString(), utc(processedAt));
    }

    private void insertMigrationIssue(long articleId, String code, Instant resolvedAt) {
        jdbcTemplate.update("""
                INSERT INTO article_revision_migration_issue
                    (article_id,issue_code,details_json,detected_at,resolved_at)
                VALUES (?,?,JSON_OBJECT(),?,?)
                """, articleId, code, utc(daysBefore(120)), utc(resolvedAt));
    }

    private java.util.List<Long> outboxIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM domain_event_outbox ORDER BY id", Long.class);
    }

    private DomainEventRetentionTask retentionTask(int batchSize, int maxBatches) {
        return new DomainEventRetentionTask(retentionMapper, outboxMapper, transactionManager,
                retentionMetrics, batchSize, maxBatches);
    }

    private void assertExplainUses(String sql, String table, String index) {
        java.util.Map<String, Object> tablePlan = jdbcTemplate.queryForList("EXPLAIN " + sql)
                .stream()
                .filter(row -> table.equals(row.get("table")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing EXPLAIN row for " + table));
        assertThat(tablePlan.get("key")).isEqualTo(index);
    }

    private double metric(String kind) {
        return meterRegistry.get("domain.event.retention.deleted")
                .tag("kind", kind).counter().count();
    }

    private static Instant daysBefore(long days) {
        return RUN_AT.minusSeconds(days * 86_400L);
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
