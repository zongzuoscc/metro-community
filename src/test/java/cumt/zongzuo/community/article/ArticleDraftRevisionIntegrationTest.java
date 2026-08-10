package cumt.zongzuo.community.article;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionProperties;
import cumt.zongzuo.community.article.config.ConfiguredArticleRevisionModeResolver;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;
import cumt.zongzuo.community.article.service.ArticleMutationFacade;
import cumt.zongzuo.community.article.web.SaveArticleDraftCommand;
import cumt.zongzuo.community.article.web.SubmissionResult;
import cumt.zongzuo.community.article.web.SubmitArticleRevisionCommand;
import cumt.zongzuo.community.event.outbox.DomainEventConflictException;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.ReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "metro.article.revision-mode=SHADOW")
class ArticleDraftRevisionIntegrationTest extends IntegrationTestSupport {

    private static final long AUTHOR_ID = 91_001L;

    @Autowired
    private ArticleMutationFacade mutationFacade;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ArticleContentCanonicalizer canonicalizer;

    @BeforeEach
    void cleanArticleRevisionFixture() {
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type = 'ARTICLE' AND aggregate_id >= 91000");
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL
                WHERE id >= 91000
                """);
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE article_id >= 91000");
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id >= 91000");
        jdbcTemplate.update("DELETE FROM article_draft WHERE article_id >= 91000");
        jdbcTemplate.update("DELETE FROM article_tag WHERE article_id >= 91000");
        jdbcTemplate.update("DELETE FROM article WHERE id >= 91000");
        jdbcTemplate.update("DELETE FROM report WHERE id >= 91000");
        jdbcTemplate.update("""
                INSERT INTO sys_user (id,username,password,email,role,status)
                VALUES (?, 'revision-author', 'unused', 'revision-author@example.com', 0, 0)
                ON DUPLICATE KEY UPDATE role=VALUES(role),status=0
                """, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user (id,username,password,email,role,status)
                VALUES (91002, 'revision-admin', 'unused', 'revision-admin@example.com', 1, 0)
                ON DUPLICATE KEY UPDATE role=1,status=0
                """);
        for (String queue : List.of("article.audit.queue", "es.sync.queue", "message.notify.queue")) {
            rabbitAdmin.purgeQueue(queue, true);
        }
    }

    @Test
    void revisionModeDefaultsToLegacyAndDefinesTheSingleRolloutStateMachine() {
        ArticleRevisionProperties properties = new ArticleRevisionProperties();

        assertThat(properties.getRevisionMode()).isEqualTo(ArticleRevisionMode.LEGACY);
        assertThat(ArticleRevisionMode.values()).containsExactly(
                ArticleRevisionMode.LEGACY,
                ArticleRevisionMode.SHADOW,
                ArticleRevisionMode.VERIFY_FENCE,
                ArticleRevisionMode.POINTER_READ,
                ArticleRevisionMode.CUTOVER);
    }

    @Test
    void configuredModeIsFrozenAtStartupInsteadOfFollowingMutablePropertyChanges() {
        ArticleRevisionProperties properties = new ArticleRevisionProperties();
        properties.setRevisionMode(ArticleRevisionMode.SHADOW);
        ConfiguredArticleRevisionModeResolver resolver = new ConfiguredArticleRevisionModeResolver(properties);

        properties.setRevisionMode(ArticleRevisionMode.CUTOVER);

        assertThat(resolver.current()).isEqualTo(ArticleRevisionMode.SHADOW);
    }

    @Test
    void canonicalHashUsesLfUtf8ByteLengthsAndCanonicalUnicodeTags() {
        ArticleContentCanonicalizer canonicalizer = new ArticleContentCanonicalizer(new ObjectMapper());

        ArticleContentSnapshot crlf = canonicalizer.canonicalize(
                "题\r\nA", null, "a\rb\r\nc\n", "封",
                List.of(" beta ", "", "阿", "alpha", "beta", "😀", "alpha"));
        ArticleContentSnapshot lf = canonicalizer.canonicalize(
                "题\nA", "", "a\nb\nc\n", "封",
                List.of("😀", "阿", "beta", "alpha"));

        assertThat(crlf.title()).isEqualTo("题\nA");
        assertThat(crlf.bodyMarkdown()).isEqualTo("a\nb\nc\n");
        assertThat(crlf.tags()).containsExactly("alpha", "beta", "阿", "😀");
        assertThat(crlf.tagsJson()).isEqualTo("[\"alpha\",\"beta\",\"阿\",\"😀\"]");
        assertThat(crlf.contentHash()).isEqualTo(lf.contentHash())
                .isEqualTo("febf5587a8a0652cfa9bbd77b6c319da1735864400335dcad110e9ba9a52d4e2")
                .matches("[0-9a-f]{64}");
    }

    @Test
    void shadowDraftSaveCreatesOwnerScopedMirrorAndRejectsStaleVersion() throws Exception {
        long articleId = insertLegacyArticle(91_101L, 0, "old", "old-body");
        jdbcTemplate.update("""
                INSERT INTO tag (name,article_count,create_time) VALUES ('shadow-old',1,NOW(6))
                ON DUPLICATE KEY UPDATE article_count=article_count
                """);
        Long oldTagId = jdbcTemplate.queryForObject(
                "SELECT id FROM tag WHERE name='shadow-old'", Long.class);
        jdbcTemplate.update("INSERT INTO article_tag (article_id,tag_id) VALUES (?,?)", articleId, oldTagId);

        var saved = mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 0, "new", "summary", "new-body", "cover", List.of("java", " AI ")),
                AUTHOR_ID);

        assertThat(saved.getDraftVersion()).isEqualTo(1L);
        assertThat(saved.getUserId()).isEqualTo(AUTHOR_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM article WHERE id=?", String.class, articleId)).isEqualTo("new-body");
        assertThat(new ObjectMapper().readTree(jdbcTemplate.queryForObject(
                "SELECT tags_json FROM article_draft WHERE article_id=?", String.class, articleId)))
                .isEqualTo(new ObjectMapper().readTree("[\"AI\",\"java\"]"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.name FROM tag t JOIN article_tag at ON at.tag_id=t.id
                WHERE at.article_id=? ORDER BY t.name
                """, String.class, articleId)).containsExactly("shadow-old");

        assertThatThrownBy(() -> mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 0, "stale", "", "stale-body", "", List.of()), AUTHOR_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=?", String.class, articleId))
                .isEqualTo("new-body");

        mutationFacade.submit(new SubmitArticleRevisionCommand(articleId, AUTHOR_ID, 1));
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.name FROM tag t JOIN article_tag at ON at.tag_id=t.id
                WHERE at.article_id=? ORDER BY t.name
                """, String.class, articleId)).containsExactly("AI", "java");
    }

    @Test
    void shadowRejectsPublishedEditingBeforeCreatingAnyLazyMirrorRows() {
        long articleId = insertLegacyArticle(91_111L, 1, "published", "published-body");

        assertThatThrownBy(() -> mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 0, "changed", "", "changed-body", "", List.of()), AUTHOR_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_draft WHERE article_id=?", Integer.class, articleId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id=?", Integer.class, articleId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM article WHERE id=?", String.class, articleId)).isEqualTo("published-body");
    }

    @Test
    void shadowRejectsMoreThanFiveCanonicalTagsWithoutChangingLegacyOrDraft() {
        long articleId = insertLegacyArticle(91_113L, 0, "old", "old-body");

        assertThatThrownBy(() -> mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 0, "changed", "", "changed-body", "",
                List.of("one", "two", "three", "four", "five", "six")), AUTHOR_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode().value()).isEqualTo(400);
                    assertThat(response.getReason()).isEqualTo("ARTICLE_TAG_LIMIT_EXCEEDED");
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM article WHERE id=?", String.class, articleId)).isEqualTo("old-body");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_draft WHERE article_id=?", Integer.class, articleId)).isZero();
    }

    @Test
    void submissionRejectsAStoredDraftWhoseCanonicalHashWasTampered() {
        long articleId = insertLegacyArticle(91_112L, 0, "old", "old-body");
        mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 0, "hash", "", "hash-body", "", List.of("hash")), AUTHOR_ID);
        jdbcTemplate.update("UPDATE article_draft SET content_hash=REPEAT('0',64) WHERE article_id=?", articleId);

        assertThatThrownBy(() -> mutationFacade.submit(
                new SubmitArticleRevisionCommand(articleId, AUTHOR_ID, 1)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode().value()).isEqualTo(409);
                    assertThat(response.getReason()).isEqualTo("DRAFT_HASH_MISMATCH");
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_moderation_job WHERE article_id=?", Integer.class, articleId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?", Integer.class, articleId))
                .isZero();
    }

    @Test
    void submissionFreezesDraftCreatesHumanJobPointersAndTransactionalOutbox() {
        long articleId = insertLegacyArticle(91_102L, 0, "old", "old-body");
        mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 0, "frozen-title", "frozen-summary", "frozen-body", "frozen-cover",
                List.of("java", "agent")), AUTHOR_ID);

        SubmissionResult submitted = mutationFacade.submit(
                new SubmitArticleRevisionCommand(articleId, AUTHOR_ID, 1));

        assertThat(submitted.revisionNo()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT title,body_markdown,cover,tags_json,content_hash,source_draft_version
                FROM article_revision WHERE id=?
                """, submitted.revisionId()))
                .satisfies(row -> {
                    assertThat(row.get("title")).isEqualTo("frozen-title");
                    assertThat(row.get("body_markdown")).isEqualTo("frozen-body");
                    assertThat(row.get("cover")).isEqualTo("frozen-cover");
                    assertThat(row.get("content_hash")).isEqualTo(submitted.contentHash());
                    assertThat(((Number) row.get("source_draft_version")).longValue()).isEqualTo(1L);
                });
        assertThat(jdbcTemplate.queryForMap("""
                SELECT revision_id,content_hash,state FROM article_moderation_job WHERE id=?
                """, submitted.moderationJobId()))
                .containsEntry("revision_id", submitted.revisionId())
                .containsEntry("content_hash", submitted.contentHash())
                .containsEntry("state", "HUMAN_PENDING");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,latest_revision_id,pending_revision_id,published_revision_id,
                       visibility_state,review_state
                FROM article WHERE id=?
                """, articleId))
                .containsEntry("status", 2)
                .containsEntry("latest_revision_id", submitted.revisionId())
                .containsEntry("pending_revision_id", submitted.revisionId())
                .containsEntry("published_revision_id", null)
                .containsEntry("visibility_state", "PRIVATE")
                .containsEntry("review_state", "HUMAN_PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_type='ARTICLE' AND aggregate_id=?
                  AND event_type='ARTICLE_REVISION_SUBMITTED'
                """, Integer.class, articleId)).isEqualTo(1);
        assertThat(rabbitTemplate.receive("article.audit.queue", 100)).isNull();

        mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 1, "changed-title", "", "changed-after-submit", "", List.of("changed")),
                AUTHOR_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_revision WHERE id=?", String.class, submitted.revisionId()))
                .isEqualTo("frozen-body");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_hash FROM article_moderation_job WHERE id=?", String.class,
                submitted.moderationJobId())).isEqualTo(submitted.contentHash());
    }

    @Test
    void resubmissionSupersedesEveryNonTerminalJobWithOneOrderedBatchEvent() throws Exception {
        long articleId = insertLegacyArticle(91_103L, 0, "old", "old-body");
        mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 0, "v1", "", "body-v1", "", List.of("one")), AUTHOR_ID);
        SubmissionResult first = mutationFacade.submit(
                new SubmitArticleRevisionCommand(articleId, AUTHOR_ID, 1));

        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                     content_hash,source_draft_version,created_by,created_at)
                SELECT article_id,3,title,summary,body_markdown,body_plain,cover,tags_json,
                       content_hash,source_draft_version,created_by,NOW(6)
                FROM article_revision WHERE id=?
                """, first.revisionId());
        long secondOldRevision = jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=? AND revision_no=3",
                Long.class, articleId);
        jdbcTemplate.update("""
                INSERT INTO article_moderation_job
                    (article_id,revision_id,content_hash,state,attempt_count,created_at,updated_at,lock_version)
                VALUES (?,?,?,'PENDING',0,NOW(6),NOW(6),0)
                """, articleId, secondOldRevision, first.contentHash());
        long secondOldJob = jdbcTemplate.queryForObject(
                "SELECT id FROM article_moderation_job WHERE article_id=? AND revision_id=?",
                Long.class, articleId, secondOldRevision);

        mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 1, "v3", "", "body-v3", "", List.of("three")), AUTHOR_ID);
        SubmissionResult replacement = mutationFacade.submit(
                new SubmitArticleRevisionCommand(articleId, AUTHOR_ID, 2));

        assertThat(jdbcTemplate.queryForList("""
                SELECT id FROM article_moderation_job
                WHERE article_id=? AND state='SUPERSEDED' ORDER BY id
                """, Long.class, articleId)).containsExactly(first.moderationJobId(), secondOldJob);
        List<Map<String, Object>> supersededEvents = jdbcTemplate.queryForList("""
                SELECT aggregate_version,payload_json FROM domain_event_outbox
                WHERE aggregate_type='ARTICLE' AND aggregate_id=?
                  AND event_type='ARTICLE_REVISION_SUPERSEDED'
                """, articleId);
        assertThat(supersededEvents).hasSize(1);
        var payload = new ObjectMapper().readTree((String) supersededEvents.getFirst().get("payload_json"));
        assertThat(payload.path("replacementRevisionId").longValue()).isEqualTo(replacement.revisionId());
        assertThat(payload.path("supersededJobIds").toString()).isEqualTo(
                "[" + first.moderationJobId() + "," + secondOldJob + "]");
        assertThat(payload.path("supersededRevisionIds").toString()).isEqualTo(
                "[" + first.revisionId() + "," + secondOldRevision + "]");
        assertThat(DomainEventType.valueOf("ARTICLE_REVISION_SUPERSEDED"))
                .isEqualTo(DomainEventType.ARTICLE_REVISION_SUPERSEDED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lock_version FROM article WHERE id=?", Long.class, articleId)).isEqualTo(6L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT aggregate_version FROM domain_event_outbox
                WHERE aggregate_id=? AND event_type='ARTICLE_REVISION_SUBMITTED'
                ORDER BY aggregate_version DESC LIMIT 1
                """, Long.class, articleId)).isEqualTo(6L);
    }

    @Test
    void outboxFailureRollsBackRevisionJobAndArticlePointers() {
        long articleId = insertLegacyArticle(91_104L, 0, "old", "old-body");
        mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 0, "rollback", "", "rollback-body", "", List.of()), AUTHOR_ID);
        String dedupeKey = "ARTICLE:" + articleId + ":1:3:ARTICLE_REVISION_SUBMITTED";
        jdbcTemplate.update("""
                INSERT INTO domain_event_outbox
                    (event_id,aggregate_type,aggregate_id,aggregate_version,lifecycle_epoch,event_type,
                     payload_version,payload_json,dedupe_key,occurred_at,state,retry_count,next_attempt_at,created_at)
                VALUES (UNHEX(REPLACE(UUID(),'-','')),'ARTICLE',?,3,1,'ARTICLE_REVISION_SUBMITTED',
                        1,JSON_OBJECT('conflict',true),?,NOW(6),'PENDING',0,NOW(6),NOW(6))
                """, articleId, dedupeKey);

        assertThatThrownBy(() -> mutationFacade.submit(
                new SubmitArticleRevisionCommand(articleId, AUTHOR_ID, 1)))
                .isInstanceOf(DomainEventConflictException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id=?", Integer.class, articleId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_moderation_job WHERE article_id=?", Integer.class, articleId))
                .isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,latest_revision_id,pending_revision_id,lock_version FROM article WHERE id=?
                """, articleId))
                .containsEntry("status", 0)
                .containsEntry("latest_revision_id", null)
                .containsEntry("pending_revision_id", null)
                .containsEntry("lock_version", 2L);
    }

    @Test
    void existingPublishEntryUsesShadowTransactionAndNeverDirectlyPublishesRabbit() {
        ArticleDTO dto = new ArticleDTO();
        dto.setTitle("entry-title");
        dto.setSummary("entry-summary");
        dto.setContent("entry-body");
        dto.setTags(List.of("entry"));

        long articleId = articleService.publishOrSave(dto, true, AUTHOR_ID);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id=?", Integer.class, articleId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?", Integer.class, articleId))
                .isEqualTo(1);
        assertThat(rabbitTemplate.receive("article.audit.queue", 100)).isNull();
        assertThat(rabbitTemplate.receive("es.sync.queue", 100)).isNull();
    }

    @Test
    void shadowManualDecisionLazilyFreezesLegacyPendingArticleAndUsesOutboxOnly() {
        long articleId = insertLegacyArticle(91_105L, 2, "pending", "pending-body");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(91_002L));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/article/admin/audit"),
                new HttpEntity<>("{\"id\":" + articleId + ",\"pass\":true,\"reason\":\"ok\"}", headers),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,published_revision_id,pending_revision_id,visibility_state,review_state
                FROM article WHERE id=?
                """, articleId))
                .containsEntry("status", 1)
                .containsEntry("pending_revision_id", null)
                .containsEntry("visibility_state", "PUBLIC")
                .containsEntry("review_state", "APPROVED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM article_moderation_job WHERE article_id=?
                """, String.class, articleId)).isEqualTo("HUMAN_APPROVED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_id=? AND event_type='ARTICLE_REVISION_PUBLISHED'
                """, Integer.class, articleId)).isEqualTo(1);
        assertThat(rabbitTemplate.receive("es.sync.queue", 100)).isNull();
        assertThat(rabbitTemplate.receive("message.notify.queue", 100)).isNull();
    }

    @Test
    void recycleRestoreAndPurgeKeepFkTruthAndUseOnlyOutbox() {
        long articleId = insertLegacyArticle(91_106L, 0, "lifecycle", "body");

        articleService.moveToRecycleBin(articleId, AUTHOR_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT visibility_state FROM article WHERE id=?", String.class, articleId))
                .isEqualTo("RECYCLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_draft WHERE article_id=?", Integer.class, articleId))
                .isEqualTo(1);

        articleService.restoreArticle(articleId, AUTHOR_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM article WHERE id=?", Integer.class, articleId)).isZero();

        articleService.moveToRecycleBin(articleId, AUTHOR_ID);
        articleService.deletePermanently(articleId, AUTHOR_ID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article WHERE id=?", Integer.class, articleId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT visibility_state FROM article WHERE id=?", String.class, articleId))
                .isEqualTo("PURGED");
        assertThat(articleService.getRecycleBin(AUTHOR_ID).stream().map(a -> a.getId()))
                .doesNotContain(articleId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?", Integer.class, articleId))
                .isEqualTo(4);
        assertThat(rabbitTemplate.receive("es.sync.queue", 100)).isNull();
    }

    @Test
    void reportPenaltyUnpublishesThroughTheSameLockedShadowFacade() {
        long articleId = insertLegacyArticle(91_107L, 1, "reported", "reported-body");
        long reportId = 91_107L;
        jdbcTemplate.update("""
                INSERT INTO report (id,reporter_id,target_id,target_type,reason,status,create_time)
                VALUES (?,?,?,?,?,0,NOW(6))
                """, reportId, AUTHOR_ID, articleId, 1, "policy");

        reportService.processReport(91_002L, reportId, true, "confirmed");

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,published_revision_id,pending_revision_id,visibility_state,review_state
                FROM article WHERE id=?
                """, articleId))
                .containsEntry("status", 3)
                .containsEntry("published_revision_id", null)
                .containsEntry("pending_revision_id", null)
                .containsEntry("visibility_state", "PRIVATE")
                .containsEntry("review_state", "REJECTED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_id=? AND event_type='ARTICLE_UNPUBLISHED'
                """, Integer.class, articleId)).isEqualTo(1);
        long versionAfterFirstDecision = jdbcTemplate.queryForObject(
                "SELECT lock_version FROM article WHERE id=?", Long.class, articleId);
        int messagesAfterFirstDecision = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE target_id=?", Integer.class, articleId);

        reportService.processReport(91_002L, reportId, true, "confirmed");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT lock_version FROM article WHERE id=?", Long.class, articleId))
                .isEqualTo(versionAfterFirstDecision);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?", Integer.class, articleId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE target_id=?", Integer.class, articleId))
                .isEqualTo(messagesAfterFirstDecision);
        assertThat(rabbitTemplate.receive("es.sync.queue", 100)).isNull();
    }

    @Test
    void concurrentOwnerCasAllowsExactlyOneDraftWriterAndKeepsLegacyMirrorAtomic() throws Exception {
        long articleId = insertLegacyArticle(91_108L, 0, "race-old", "race-old-body");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(() -> saveRaceDraft(
                    articleId, "race-a", "race-body-a", ready, start));
            Future<Boolean> second = executor.submit(() -> saveRaceDraft(
                    articleId, "race-b", "race-body-b", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT a.content,d.body_markdown,d.draft_version
                FROM article a JOIN article_draft d ON d.article_id=a.id
                WHERE a.id=?
                """, articleId);
        assertThat(stored.get("content")).isEqualTo(stored.get("body_markdown"));
        assertThat(stored.get("content")).isIn("race-body-a", "race-body-b");
        assertThat(((Number) stored.get("draft_version")).longValue()).isEqualTo(1L);
    }

    @Test
    void articleFirstLockMakesLazyShadowAndFutureBackfillConvergeForEitherWinner() throws Exception {
        long backfillFirstArticle = insertLegacyArticle(91_109L, 0, "old-a", "old-body-a");
        runBackfillFirstRace(backfillFirstArticle, "mutation-after-backfill");
        assertConvergedRace(backfillFirstArticle, "mutation-after-backfill", "old-body-a");

        long mutationFirstArticle = insertLegacyArticle(91_110L, 0, "old-b", "old-body-b");
        runMutationFirstRace(mutationFirstArticle, "mutation-before-backfill");
        assertConvergedRace(mutationFirstArticle, "mutation-before-backfill", "old-body-b");
    }

    private boolean saveRaceDraft(long articleId, String title, String body,
                                  CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        try {
            mutationFacade.saveDraft(new SaveArticleDraftCommand(
                    articleId, 0, title, "", body, "", List.of("race")), AUTHOR_ID);
            return true;
        } catch (ResponseStatusException conflict) {
            if (conflict.getStatusCode().value() == 409) {
                return false;
            }
            throw conflict;
        }
    }

    private void runBackfillFirstRace(long articleId, String mutationBody) throws Exception {
        CountDownLatch backfillLocked = new CountDownLatch(1);
        CountDownLatch releaseBackfill = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> backfill = executor.submit(() -> backfillTransaction(
                    articleId, backfillLocked, releaseBackfill));
            assertThat(backfillLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> mutation = executor.submit(() -> {
                mutationStarted.countDown();
                mutationFacade.saveDraft(new SaveArticleDraftCommand(
                        articleId, 0, "mutation", "", mutationBody, "", List.of("race")), AUTHOR_ID);
            });
            assertThat(mutationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            releaseBackfill.countDown();
            backfill.get(10, TimeUnit.SECONDS);
            mutation.get(10, TimeUnit.SECONDS);
        }
    }

    private void runMutationFirstRace(long articleId, String mutationBody) throws Exception {
        CountDownLatch mutationLocked = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        CountDownLatch backfillStarted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> mutation = executor.submit(() -> transaction.executeWithoutResult(ignored -> {
                jdbcTemplate.queryForObject("SELECT id FROM article WHERE id=? FOR UPDATE",
                        Long.class, articleId);
                mutationLocked.countDown();
                await(releaseMutation);
                mutationFacade.saveDraft(new SaveArticleDraftCommand(
                        articleId, 0, "mutation", "", mutationBody, "", List.of("race")), AUTHOR_ID);
            }));
            assertThat(mutationLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> backfill = executor.submit(() -> {
                backfillStarted.countDown();
                backfillTransaction(articleId, null, null);
            });
            assertThat(backfillStarted.await(10, TimeUnit.SECONDS)).isTrue();
            releaseMutation.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            backfill.get(10, TimeUnit.SECONDS);
        }
    }

    private void backfillTransaction(long articleId, CountDownLatch locked, CountDownLatch release) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            Map<String, Object> article = jdbcTemplate.queryForMap("""
                    SELECT id,author_id,title,summary,content,cover
                    FROM article WHERE id=? FOR UPDATE
                    """, articleId);
            if (locked != null) {
                locked.countDown();
            }
            if (release != null) {
                await(release);
            }
            ArticleContentSnapshot snapshot = canonicalizer.canonicalize(
                    (String) article.get("title"), (String) article.get("summary"),
                    (String) article.get("content"), (String) article.get("cover"), List.of());
            long authorId = ((Number) article.get("author_id")).longValue();
            jdbcTemplate.update("""
                    INSERT IGNORE INTO article_draft
                        (article_id,user_id,draft_version,title,summary,body_markdown,body_plain,cover,
                         tags_json,content_hash,created_at,updated_at,lock_version)
                    VALUES (?,?,0,?,?,?,?,?,?,?,NOW(6),NOW(6),0)
                    """, articleId, authorId, snapshot.title(), snapshot.summary(), snapshot.bodyMarkdown(),
                    snapshot.bodyPlain(), snapshot.cover(), snapshot.tagsJson(), snapshot.contentHash());
            jdbcTemplate.update("""
                    INSERT IGNORE INTO article_revision
                        (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                         content_hash,source_draft_version,created_by,created_at)
                    VALUES (?,1,?,?,?,?,?,?,?,0,?,NOW(6))
                    """, articleId, snapshot.title(), snapshot.summary(), snapshot.bodyMarkdown(),
                    snapshot.bodyPlain(), snapshot.cover(), snapshot.tagsJson(), snapshot.contentHash(), authorId);
            jdbcTemplate.update("""
                    UPDATE article
                    SET visibility_state='PRIVATE',review_state='NOT_SUBMITTED',lock_version=lock_version+1
                    WHERE id=? AND visibility_state IS NULL
                    """, articleId);
        });
    }

    private void assertConvergedRace(long articleId, String expectedBody, String expectedBaselineBody) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT a.content,d.body_markdown,d.draft_version
                FROM article a JOIN article_draft d ON d.article_id=a.id
                WHERE a.id=?
                """, articleId))
                .containsEntry("content", expectedBody)
                .containsEntry("body_markdown", expectedBody)
                .containsEntry("draft_version", 1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id=?", Integer.class, articleId))
                .isEqualTo(1);
        Map<String, Object> baseline = jdbcTemplate.queryForMap("""
                SELECT title,summary,body_markdown,cover,tags_json,content_hash
                FROM article_revision WHERE article_id=? AND revision_no=1
                """, articleId);
        assertThat(baseline.get("body_markdown")).isEqualTo(expectedBaselineBody);
        ArticleContentSnapshot recomputed = canonicalizer.canonicalize(
                (String) baseline.get("title"), (String) baseline.get("summary"),
                (String) baseline.get("body_markdown"), (String) baseline.get("cover"),
                readTags((String) baseline.get("tags_json")));
        assertThat(baseline.get("content_hash")).isEqualTo(recomputed.contentHash());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?", Integer.class, articleId))
                .isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test latch interrupted", interrupted);
        }
    }

    private static List<String> readTags(String json) {
        try {
            return new ObjectMapper().readerForListOf(String.class).readValue(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private long insertLegacyArticle(long articleId, int status, String title, String content) {
        jdbcTemplate.update("""
                INSERT INTO article
                    (id,title,summary,content,author_id,view_count,like_count,comment_count,collect_count,
                     create_time,update_time,status,cover,is_deleted,lifecycle_epoch,lock_version)
                VALUES (?,?,?,?,?,0,0,0,0,NOW(6),NOW(6),?,'',0,1,0)
                """, articleId, title, "summary", content, AUTHOR_ID, status);
        return articleId;
    }
}
