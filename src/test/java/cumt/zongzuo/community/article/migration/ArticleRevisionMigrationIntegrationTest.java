package cumt.zongzuo.community.article.migration;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;
import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.repository.ArticleRepository;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "metro.article.revision-mode=VERIFY_FENCE",
        "metro.migration.stage-b.verification-page-size=2",
        "metro.migration.stage-b.elasticsearch-read-alias=article-read"
})
class ArticleRevisionMigrationIntegrationTest extends IntegrationTestSupport {

    private static final long AUTHOR_ID = 94_000L;
    private static final long FIRST_ARTICLE_ID = 94_100L;

    @Autowired
    private StageBArticleMigrationService migrationService;

    @Autowired
    private ArticleContentCanonicalizer canonicalizer;

    @Autowired
    private StageBArticleMigrationVerifier verifier;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @BeforeEach
    void resetFixture() {
        articleRepository.deleteAll();
        ensureArticleReadAlias();
        refreshArticleIndex();
        // The production verifier intentionally reconciles the complete dataset. Keep this
        // fixture equally global so rows intentionally left by unrelated full-suite tests do
        // not become accidental inputs to these focused verifier assertions.
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE'");
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL");
        jdbcTemplate.update("DELETE FROM article_moderation_attempt");
        jdbcTemplate.update("DELETE FROM article_moderation_job");
        jdbcTemplate.update("DELETE FROM article_revision_migration_issue");
        jdbcTemplate.update("DELETE FROM article_revision");
        jdbcTemplate.update("DELETE FROM article_draft");
        jdbcTemplate.update("DELETE FROM article_tag");
        jdbcTemplate.update("DELETE FROM article");
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status)
                VALUES(?, 'migration-author', 'unused', 'migration-author@example.com', 0, 0)
                ON DUPLICATE KEY UPDATE status=0
                """, AUTHOR_ID);
    }

    @Test
    void mapsEveryValidLegacyStateAndRetainsStatusPointersForDeletedRows() {
        seedArticle(94_101L, 0, 0, "draft");
        seedArticle(94_102L, 1, 0, "public");
        seedArticle(94_103L, 2, 0, "pending");
        seedArticle(94_104L, 3, 0, "rejected");
        seedArticle(94_105L, 1, 1, "recycled-public");
        seedArticle(94_106L, 2, 1, "recycled-pending");

        MigrationBatchResult result = migrationService.backfillAfter(FIRST_ARTICLE_ID - 1, 100);

        assertThat(result.scanned()).isGreaterThanOrEqualTo(6);
        assertState(94_101L, "PRIVATE", "NOT_SUBMITTED", null, null, null);
        long publicRevision = revisionId(94_102L, 1);
        assertState(94_102L, "PUBLIC", "APPROVED", publicRevision, null, publicRevision);
        long pendingRevision = revisionId(94_103L, 1);
        assertState(94_103L, "PRIVATE", "AUTO_PENDING", pendingRevision, pendingRevision, null);
        long rejectedRevision = revisionId(94_104L, 1);
        assertState(94_104L, "PRIVATE", "REJECTED", rejectedRevision, null, null);
        long recycledPublic = revisionId(94_105L, 1);
        assertState(94_105L, "RECYCLED", "APPROVED", recycledPublic, null, recycledPublic);
        long recycledPending = revisionId(94_106L, 1);
        assertState(94_106L, "RECYCLED", "AUTO_PENDING", recycledPending, recycledPending, null);

        Map<String, Object> job = jdbcTemplate.queryForMap("""
                SELECT revision_id,content_hash,state,last_error FROM article_moderation_job
                WHERE article_id=94103
                """);
        assertThat(((Number) job.get("revision_id")).longValue()).isEqualTo(pendingRevision);
        assertThat(job.get("content_hash")).isEqualTo(contentHash(pendingRevision));
        assertThat(job.get("state")).isEqualTo("HUMAN_PENDING");
        assertThat(job.get("last_error")).isEqualTo("LEGACY_BACKFILL_MANUAL");
    }

    @Test
    void invalidLegacyFlagsAndExistingShadowMismatchCreateStableIssuesWithoutGuessingOrOverwriting() {
        seedArticle(94_111L, 9, 0, "invalid-status-secret-body");
        seedArticle(94_112L, 1, 2, "invalid-delete-secret-body");
        seedArticle(94_113L, 1, 0, "legacy-current");
        insertMismatchedDraft(94_113L);

        migrationService.backfillAfter(FIRST_ARTICLE_ID - 1, 100);

        assertThat(issueCodes(94_111L)).containsExactly("INVALID_LEGACY_STATUS");
        assertThat(issueCodes(94_112L)).containsExactly("INVALID_DELETE_FLAG");
        assertThat(issueCodes(94_113L)).containsExactly("BACKFILL_MISMATCH");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT details_json FROM article_revision_migration_issue WHERE article_id=94111",
                String.class)).doesNotContain("invalid-status-secret-body");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id IN (94111,94112,94113)",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=94113", String.class))
                .isEqualTo("mismatched-shadow");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT latest_revision_id,pending_revision_id,published_revision_id,
                       visibility_state,review_state FROM article WHERE id=94113
                """).values()).containsOnlyNulls();
    }

    @Test
    void existingLegacyPendingJobWithChangedStateOrReasonBecomesAnIssueAndIsNeverOverwritten() {
        seedArticle(94_114L, 2, 0, "pending-job");
        migrationService.backfillAfter(FIRST_ARTICLE_ID - 1, 100);
        jdbcTemplate.update("""
                UPDATE article_moderation_job
                SET state='FAILED',last_error='UNEXPECTED_REASON',attempt_count=2,lock_version=3
                WHERE article_id=94114
                """);

        migrationService.backfillAfter(FIRST_ARTICLE_ID - 1, 100);
        StageBMigrationReport report = verifier.verifyAll();

        assertThat(issueCodes(94_114L)).containsExactly("BACKFILL_MISMATCH");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state,last_error,attempt_count,lock_version
                FROM article_moderation_job WHERE article_id=94114
                """))
                .containsEntry("state", "FAILED")
                .containsEntry("last_error", "UNEXPECTED_REASON")
                .containsEntry("attempt_count", 2)
                .containsEntry("lock_version", 3L);
        assertThat(report.mismatches()).extracting(StageBMigrationMismatch::code)
                .contains("MODERATION_JOB_STATE_MISMATCH", "MODERATION_JOB_REASON_MISMATCH",
                        "MODERATION_JOB_FROZEN_FIELDS_MISMATCH");
    }

    @Test
    void taskThreeShadowPendingMarkerRemainsValidAndByteStableDuringUpgrade() {
        seedArticle(94_115L, 2, 0, "deployed-shadow-job");
        migrationService.backfillAfter(FIRST_ARTICLE_ID - 1, 100);
        jdbcTemplate.update("""
                UPDATE article_moderation_job SET last_error='LEGACY_SHADOW_MANUAL'
                WHERE article_id=94115
                """);
        Map<String, Object> before = jdbcTemplate.queryForMap("""
                SELECT id,revision_id,content_hash,state,last_error,attempt_count,
                       created_at,updated_at,lock_version
                FROM article_moderation_job WHERE article_id=94115
                """);

        migrationService.backfillAfter(FIRST_ARTICLE_ID - 1, 100);
        StageBMigrationReport report = verifier.verifyAll();

        assertThat(issueCodes(94_115L)).isEmpty();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT id,revision_id,content_hash,state,last_error,attempt_count,
                       created_at,updated_at,lock_version
                FROM article_moderation_job WHERE article_id=94115
                """)).isEqualTo(before);
        assertThat(report.passed()).as(report.toString()).isTrue();
    }

    @Test
    void rerunPreservesEveryGeneratedIdHashCountAndTimestamp() {
        seedArticle(94_121L, 0, 0, "stable-draft");
        seedArticle(94_122L, 1, 0, "stable-public");
        seedArticle(94_123L, 2, 0, "stable-pending");

        migrationService.backfillAfter(FIRST_ARTICLE_ID - 1, 100);
        Map<String, Object> first = stableFingerprint();

        migrationService.backfillAfter(FIRST_ARTICLE_ID - 1, 100);

        assertThat(stableFingerprint()).isEqualTo(first);
    }

    @Test
    void statusZeroBaselineIsImmutableAndMayDifferFromCurrentLegacyDraft() {
        seedArticle(94_131L, 0, 0, "pre-mutation");
        migrationService.backfillAfter(FIRST_ARTICLE_ID - 1, 100);
        long baselineId = revisionId(94_131L, 1);
        String baselineHash = contentHash(baselineId);

        ArticleContentSnapshot current = canonicalizer.canonicalize(
                "article-94131", "summary", "post-mutation", "cover", List.of());
        jdbcTemplate.update("""
                UPDATE article SET content=?,update_time=NOW(6) WHERE id=94131
                """, current.bodyMarkdown());
        jdbcTemplate.update("""
                UPDATE article_draft SET draft_version=draft_version+1,body_markdown=?,body_plain=?,
                    content_hash=?,updated_at=NOW(6),lock_version=lock_version+1 WHERE article_id=94131
                """, current.bodyMarkdown(), current.bodyPlain(), current.contentHash());

        migrationService.backfillAfter(FIRST_ARTICLE_ID - 1, 100);

        assertThat(revisionId(94_131L, 1)).isEqualTo(baselineId);
        assertThat(contentHash(baselineId)).isEqualTo(baselineHash).isNotEqualTo(current.contentHash());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_hash FROM article_draft WHERE article_id=94131", String.class))
                .isEqualTo(current.contentHash());
        assertThat(issueCodes(94_131L)).isEmpty();
        assertState(94_131L, "PRIVATE", "NOT_SUBMITTED", null, null, null);
    }

    @Test
    void verifierUsesFullPagedMysqlAndPitEsReconciliationWithStableFenceFingerprints() {
        for (long id = 94_141L; id <= 94_145L; id++) {
            seedArticle(id, 1, 0, "public-" + id);
        }
        seedArticle(94_146L, 0, 0, "private");
        migrationService.backfillAfter(94_139L, 100);
        for (long id = 94_141L; id <= 94_145L; id++) {
            saveMatchingDocument(id);
        }
        refreshArticleIndex();

        StageBMigrationReport report = verifier.verifyAll();

        assertThat(report.passed()).as(report.toString()).isTrue();
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.startFingerprint()).isEqualTo(report.endFingerprint());
        assertThat(report.databaseFinishedAt()).isAfterOrEqualTo(report.databaseStartedAt());
        assertThat(report.expectedPublicDocumentCount()).isEqualTo(5);
        assertThat(report.actualPublicDocumentCount()).isEqualTo(5);
        assertThat(report.elasticsearchPages()).isGreaterThanOrEqualTo(3);
        assertThat(report.maximumElasticsearchLookupBatchSize()).isLessThanOrEqualTo(2);
    }

    @Test
    void verifierFailsClosedForUnresolvedIssuesAndMysqlJobHashMismatch() {
        seedArticle(94_151L, 9, 0, "invalid");
        seedArticle(94_152L, 2, 0, "pending");
        migrationService.backfillAfter(94_149L, 100);
        jdbcTemplate.update("""
                UPDATE article_moderation_job SET content_hash=REPEAT('f',64) WHERE article_id=94152
                """);

        StageBMigrationReport report = verifier.verifyAll();

        assertThat(report.passed()).isFalse();
        assertThat(report.unresolvedIssueArticleCount()).isEqualTo(1);
        assertThat(report.mismatches()).as(report.toString()).extracting(StageBMigrationMismatch::code)
                .contains("UNRESOLVED_MIGRATION_ISSUE", "MODERATION_JOB_HASH_MISMATCH");
    }

    @Test
    void verifierFailsClosedForMissingExtraAndStaleElasticsearchDocuments() {
        seedArticle(94_161L, 1, 0, "mysql-public-one");
        seedArticle(94_162L, 1, 0, "mysql-public-two");
        migrationService.backfillAfter(94_159L, 100);
        ArticleDoc stale = matchingDocument(94_161L);
        stale.setContent("stale-es-content");
        articleRepository.save(stale);
        ArticleDoc extra = new ArticleDoc();
        extra.setId(94999L);
        extra.setTitle("extra");
        extra.setContent("extra");
        extra.setSummary("extra");
        extra.setCover("");
        extra.setAuthorId(AUTHOR_ID);
        extra.setViewCount(0);
        extra.setLikeCount(0);
        extra.setCommentCount(0);
        extra.setCollectCount(0);
        extra.setCreateTime(java.time.LocalDateTime.now());
        articleRepository.save(extra);
        refreshArticleIndex();

        StageBMigrationReport report = verifier.verifyAll();

        assertThat(report.passed()).isFalse();
        assertThat(report.mismatches()).as(report.toString()).extracting(StageBMigrationMismatch::code)
                .contains("ELASTICSEARCH_DOCUMENT_MISMATCH", "ELASTICSEARCH_DOCUMENT_MISSING",
                        "ELASTICSEARCH_DOCUMENT_EXTRA");
    }

    @Test
    void verifierFailsClosedWhenEsContentMatchesButPublishedIdentityDoesNot() {
        long articleId = 94_171L;
        seedArticle(articleId, 1, 0, "identity-content");
        migrationService.backfillAfter(articleId - 1, 100);
        ArticleDoc wrongIdentity = matchingDocument(articleId);
        wrongIdentity.setRevisionId(wrongIdentity.getRevisionId() + 99);
        wrongIdentity.setContentHash("f".repeat(64));
        articleRepository.save(wrongIdentity);
        refreshArticleIndex();

        StageBMigrationReport report = verifier.verifyAll();

        assertThat(report.passed()).isFalse();
        assertThat(report.mismatches()).extracting(StageBMigrationMismatch::code)
                .contains("ELASTICSEARCH_DOCUMENT_MISMATCH");
    }

    @Test
    void verifierRejectsAnAliasThatFansOutToMoreThanOneConcreteIndex() {
        String secondIndex = "article-migration-second";
        deleteIndexIfPresent(secondIndex);
        try {
            elasticsearchClient.indices().create(request -> request.index(secondIndex));
            elasticsearchClient.indices().putAlias(request ->
                    request.index(secondIndex).name("article-read"));

            StageBMigrationReport report = verifier.verifyAll();

            assertThat(report.passed()).isFalse();
            assertThat(report.mismatches()).extracting(StageBMigrationMismatch::code)
                    .contains("ELASTICSEARCH_ALIAS_TARGET_COUNT");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        } finally {
            deleteIndexIfPresent(secondIndex);
        }
    }

    private void seedArticle(long id, int status, int deleted, String body) {
        jdbcTemplate.update("""
                INSERT INTO article(id,title,content,summary,cover,author_id,view_count,like_count,
                    comment_count,collect_count,create_time,update_time,status,is_deleted,delete_time,
                    lifecycle_epoch,lock_version)
                VALUES(?, ?, ?, 'summary', 'cover', ?, 3, 4, 5, 6, NOW(6), NOW(6), ?, ?,
                    IF(?=1,NOW(6),NULL),1,0)
                """, id, "article-" + id, body, AUTHOR_ID, status, deleted, deleted);
    }

    private void saveMatchingDocument(long articleId) {
        articleRepository.save(matchingDocument(articleId));
    }

    private ArticleDoc matchingDocument(long articleId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT a.id,a.author_id,a.view_count,a.like_count,a.comment_count,a.collect_count,
                       a.create_time,r.id AS revision_id,r.content_hash,
                       r.title,r.body_markdown,r.summary,r.cover
                FROM article a
                JOIN article_revision r ON r.id=a.published_revision_id AND r.article_id=a.id
                WHERE a.id=?
                """, articleId);
        ArticleDoc document = new ArticleDoc();
        document.setId(((Number) row.get("id")).longValue());
        document.setRevisionId(((Number) row.get("revision_id")).longValue());
        document.setContentHash((String) row.get("content_hash"));
        document.setAuthorId(((Number) row.get("author_id")).longValue());
        document.setViewCount(((Number) row.get("view_count")).intValue());
        document.setLikeCount(((Number) row.get("like_count")).intValue());
        document.setCommentCount(((Number) row.get("comment_count")).intValue());
        document.setCollectCount(((Number) row.get("collect_count")).intValue());
        Object createTime = row.get("create_time");
        document.setCreateTime(createTime instanceof Timestamp timestamp
                ? timestamp.toLocalDateTime() : (java.time.LocalDateTime) createTime);
        document.setTitle((String) row.get("title"));
        document.setContent((String) row.get("body_markdown"));
        document.setSummary((String) row.get("summary"));
        document.setCover((String) row.get("cover"));
        return document;
    }

    private void refreshArticleIndex() {
        try {
            elasticsearchClient.indices().refresh(request -> request.index("article"));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void ensureArticleReadAlias() {
        try {
            elasticsearchClient.indices().putAlias(request ->
                    request.index("article").name("article-read"));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void deleteIndexIfPresent(String index) {
        try {
            if (elasticsearchClient.indices().exists(request -> request.index(index)).value()) {
                elasticsearchClient.indices().delete(request -> request.index(index));
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void insertMismatchedDraft(long articleId) {
        jdbcTemplate.update("""
                INSERT INTO article_draft(article_id,user_id,draft_version,title,summary,body_markdown,
                    body_plain,cover,tags_json,content_hash,created_at,updated_at,lock_version)
                VALUES(?,?,0,'wrong','wrong','mismatched-shadow','mismatched-shadow','wrong',JSON_ARRAY(),
                    REPEAT('0',64),NOW(6),NOW(6),0)
                """, articleId, AUTHOR_ID);
    }

    private long revisionId(long articleId, long revisionNo) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=? AND revision_no=?",
                Long.class, articleId, revisionNo);
    }

    private String contentHash(long revisionId) {
        return jdbcTemplate.queryForObject(
                "SELECT content_hash FROM article_revision WHERE id=?", String.class, revisionId);
    }

    private List<String> issueCodes(long articleId) {
        return jdbcTemplate.queryForList("""
                SELECT issue_code FROM article_revision_migration_issue
                WHERE article_id=? AND resolved_at IS NULL ORDER BY issue_code
                """, String.class, articleId);
    }

    private void assertState(long articleId, String visibility, String review,
                             Long latest, Long pending, Long published) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT visibility_state,review_state,latest_revision_id,pending_revision_id,published_revision_id
                FROM article WHERE id=?
                """, articleId);
        assertThat(row.get("visibility_state")).isEqualTo(visibility);
        assertThat(row.get("review_state")).isEqualTo(review);
        assertThat(asLong(row.get("latest_revision_id"))).isEqualTo(latest);
        assertThat(asLong(row.get("pending_revision_id"))).isEqualTo(pending);
        assertThat(asLong(row.get("published_revision_id"))).isEqualTo(published);
    }

    private static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private Map<String, Object> stableFingerprint() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("drafts", jdbcTemplate.queryForList("""
                SELECT article_id,draft_version,content_hash,created_at,updated_at,lock_version
                FROM article_draft WHERE article_id BETWEEN 94121 AND 94123 ORDER BY article_id
                """));
        result.put("revisions", jdbcTemplate.queryForList("""
                SELECT id,article_id,revision_no,content_hash,created_at FROM article_revision
                WHERE article_id BETWEEN 94121 AND 94123 ORDER BY article_id,revision_no
                """));
        result.put("jobs", jdbcTemplate.queryForList("""
                SELECT id,article_id,revision_id,content_hash,state,last_error,created_at,updated_at
                FROM article_moderation_job WHERE article_id BETWEEN 94121 AND 94123 ORDER BY id
                """));
        result.put("articles", jdbcTemplate.queryForList("""
                SELECT id,latest_revision_id,pending_revision_id,published_revision_id,
                    visibility_state,review_state,lock_version FROM article
                WHERE id BETWEEN 94121 AND 94123 ORDER BY id
                """));
        return result;
    }
}
