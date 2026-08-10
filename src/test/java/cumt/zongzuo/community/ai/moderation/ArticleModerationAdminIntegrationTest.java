package cumt.zongzuo.community.ai.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationWorker;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import cumt.zongzuo.community.article.model.ArticleRevision;
import cumt.zongzuo.community.article.service.ArticleContentCanonicalizer;
import cumt.zongzuo.community.article.service.ArticleMutationFacade;
import cumt.zongzuo.community.article.service.ExactArticleTagStore;
import cumt.zongzuo.community.article.service.PublishedArticleMirrorWriter;
import cumt.zongzuo.community.config.RabbitConfig;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxDispatcher;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxService;
import cumt.zongzuo.community.repository.ArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@TestPropertySource(properties = "metro.article.revision-mode=SHADOW")
class ArticleModerationAdminIntegrationTest extends IntegrationTestSupport {

    private static final long AUTHOR_ID = 95_001L;
    private static final long ADMIN_ID = 95_002L;
    private static final long USER_ID = 95_003L;
    private static final long ARTICLE_ID = 95_101L;

    @Autowired
    private ArticleContentCanonicalizer canonicalizer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ArticleMutationFacade mutationFacade;

    @Autowired
    private DomainEventOutboxDispatcher outboxDispatcher;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private ArticleRepository articleRepository;

    @MockitoSpyBean
    private ExactArticleTagStore exactTagStore;

    @MockitoSpyBean
    private DomainEventOutboxService outboxService;

    @MockitoSpyBean
    private PublishedArticleMirrorWriter mirrorWriter;

    @Autowired
    private ArticleModerationWorker moderationWorker;

    private long revisionId;
    private long jobId;

    @BeforeEach
    void seedAdminFixture() {
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE' AND aggregate_id BETWEEN 95000 AND 95999");
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id BETWEEN 95000 AND 95999");
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE article_id BETWEEN 95000 AND 95999");
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id BETWEEN 95000 AND 95999");
        jdbcTemplate.update("DELETE FROM article_draft WHERE article_id BETWEEN 95000 AND 95999");
        jdbcTemplate.update("DELETE FROM article_tag WHERE article_id BETWEEN 95000 AND 95999");
        jdbcTemplate.update("DELETE FROM consumer_inbox WHERE consumer_name IN "
                + "('article-search-current-pointer','article-moderation-notification')");
        jdbcTemplate.update("DELETE FROM projection_watermark WHERE consumer_name="
                + "'article-search-current-pointer' AND aggregate_id BETWEEN 95000 AND 95999");
        jdbcTemplate.update("DELETE FROM message WHERE target_id BETWEEN 95000 AND 95999 AND type=4");
        jdbcTemplate.update("DELETE FROM article WHERE id BETWEEN 95000 AND 95999");
        jdbcTemplate.update("DELETE FROM tag WHERE name LIKE 'task8-%'");
        jdbcTemplate.update("""
                INSERT INTO sys_user (id,username,password,email,role,status)
                VALUES (?, 'task8-author', 'unused', 'task8-author@example.com', 0, 0),
                       (?, 'task8-admin', 'unused', 'task8-admin@example.com', 1, 0),
                       (?, 'task8-user', 'unused', 'task8-user@example.com', 0, 0)
                ON DUPLICATE KEY UPDATE role=VALUES(role),status=0
                """, AUTHOR_ID, ADMIN_ID, USER_ID);

        jdbcTemplate.update("""
                INSERT INTO article
                    (id,title,summary,content,author_id,view_count,like_count,comment_count,collect_count,
                     create_time,update_time,status,cover,is_deleted,visibility_state,review_state,
                     lifecycle_epoch,lock_version)
                VALUES (?, 'legacy-sentinel', 'legacy-summary', 'legacy-body', ?, 0, 0, 0, 0,
                        NOW(6),NOW(6),2,'legacy-cover',0,'PRIVATE','HUMAN_PENDING',1,7)
                """, ARTICLE_ID, AUTHOR_ID);

        ArticleContentSnapshot snapshot = canonicalizer.canonicalize(
                "frozen-title", "frozen-summary", "frozen-body", "frozen-cover",
                List.of("frozen-tag"));
        ArticleContentSnapshot draft = canonicalizer.canonicalize(
                "draft-sentinel", "draft-summary", "draft-body", "draft-cover",
                List.of("draft-tag"));
        jdbcTemplate.update("""
                INSERT INTO article_draft
                    (article_id,user_id,draft_version,title,summary,body_markdown,body_plain,cover,
                     tags_json,content_hash,created_at,updated_at,lock_version)
                VALUES (?,?,5,?,?,?,?,?,?,?,NOW(6),NOW(6),2)
                """, ARTICLE_ID, AUTHOR_ID, draft.title(), draft.summary(), draft.bodyMarkdown(),
                draft.bodyPlain(), draft.cover(), draft.tagsJson(), draft.contentHash());
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                     content_hash,source_draft_version,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, ARTICLE_ID, 1, snapshot.title(), snapshot.summary(), snapshot.bodyMarkdown(),
                snapshot.bodyPlain(), snapshot.cover(), snapshot.tagsJson(), snapshot.contentHash(),
                1, AUTHOR_ID, LocalDateTime.now());
        revisionId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=?", Long.class, ARTICLE_ID);
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,pending_revision_id=? WHERE id=?
                """, revisionId, revisionId, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article_moderation_job
                    (article_id,revision_id,content_hash,state,attempt_count,created_at,updated_at,lock_version)
                VALUES (?,?,?,'HUMAN_PENDING',0,NOW(6),NOW(6),3)
                """, ARTICLE_ID, revisionId, snapshot.contentHash());
        jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_moderation_job WHERE article_id=?", Long.class, ARTICLE_ID);
        jdbcTemplate.update("INSERT INTO tag(name,article_count,create_time) VALUES ('task8-old',1,NOW(6))");
        Long tagId = jdbcTemplate.queryForObject(
                "SELECT id FROM tag WHERE name='task8-old'", Long.class);
        jdbcTemplate.update("INSERT INTO article_tag(article_id,tag_id) VALUES (?,?)", ARTICLE_ID, tagId);
        for (String queue : List.of("article.search.events.queue", "article.notification.events.queue",
                "es.sync.queue", "message.notify.queue",
                RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE,
                RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE,
                RabbitConfig.ARTICLE_SEARCH_PROJECTION_QUEUE + ".dlq",
                RabbitConfig.ARTICLE_MODERATION_NOTIFICATION_QUEUE + ".dlq")) {
            if (rabbitAdmin.getQueueProperties(queue) != null) {
                rabbitAdmin.purgeQueue(queue, true);
            }
        }
    }

    @Test
    void normalUserGetsProblemDetailFromAdminBoundary() throws Exception {
        ResponseEntity<String> response = get("/api/admin/moderation/jobs/" + jobId, USER_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode problem = objectMapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("ADMIN_ROLE_REQUIRED");
        assertThat(problem.path("requestId").asText()).isNotBlank();
    }

    @Test
    void adminDetailReturnsOnlyImmutableRevisionAndVersionedDecisionTuple() throws Exception {
        ResponseEntity<String> response = get("/api/admin/moderation/jobs/" + jobId, ADMIN_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("id").asLong()).isEqualTo(jobId);
        assertThat(body.path("articleId").asLong()).isEqualTo(ARTICLE_ID);
        assertThat(body.path("revisionId").asLong()).isEqualTo(revisionId);
        assertThat(body.path("state").asText()).isEqualTo("HUMAN_PENDING");
        assertThat(body.path("jobVersion").asLong()).isEqualTo(3L);
        assertThat(body.path("articleVersion").asLong()).isEqualTo(7L);
        assertThat(body.path("currentPublishedRevisionId").isNull()).isTrue();
        assertThat(body.path("revision").path("title").asText()).isEqualTo("frozen-title");
        assertThat(body.path("revision").path("bodyMarkdown").asText()).isEqualTo("frozen-body");
        assertThat(response.getBody()).doesNotContain("legacy-sentinel", "legacy-body");
    }

    @Test
    void approveUsesBothExpectedVersionsAndAtomicallyPublishesFrozenRevision() throws Exception {
        ResponseEntity<String> response = decide("approve", revisionId, 3, 7, "human approve");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT title,summary,content,cover,status,visibility_state,review_state,
                       pending_revision_id,published_revision_id,lock_version
                FROM article WHERE id=?
                """, ARTICLE_ID))
                .containsEntry("title", "frozen-title")
                .containsEntry("summary", "frozen-summary")
                .containsEntry("content", "frozen-body")
                .containsEntry("cover", "frozen-cover")
                .containsEntry("status", 1)
                .containsEntry("visibility_state", "PUBLIC")
                .containsEntry("review_state", "APPROVED")
                .containsEntry("pending_revision_id", null)
                .containsEntry("published_revision_id", revisionId)
                .containsEntry("lock_version", 8L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state,reviewer_id,review_reason,lock_version
                FROM article_moderation_job WHERE id=?
                """, jobId))
                .containsEntry("state", "HUMAN_APPROVED")
                .containsEntry("reviewer_id", ADMIN_ID)
                .containsEntry("review_reason", "human approve")
                .containsEntry("lock_version", 4L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=?", String.class, ARTICLE_ID))
                .isEqualTo("draft-body");
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.name FROM tag t JOIN article_tag at ON at.tag_id=t.id
                WHERE at.article_id=? ORDER BY t.name
                """, String.class, ARTICLE_ID)).containsExactly("frozen-tag");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_id=? AND event_type='ARTICLE_REVISION_PUBLISHED'
                """, Integer.class, ARTICLE_ID)).isEqualTo(1);
        assertThat(rabbitTemplate.receive("es.sync.queue", 100)).isNull();
        assertThat(rabbitTemplate.receive("message.notify.queue", 100)).isNull();
    }

    @Test
    void outboxFailureAfterBothDecisionCasRollsBackMirrorTagsJobAndEvent() throws Exception {
        doThrow(new IllegalStateException("injected outbox append failure"))
                .when(outboxService).append(anyString(), anyLong(), anyLong(), anyLong(),
                        any(), anyInt(), any(), anyString());

        ResponseEntity<String> response = decide(
                "approve", revisionId, 3, 7, "must fully roll back");

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(objectMapper.readTree(response.getBody()).path("code").asText())
                .isEqualTo("INTERNAL_ERROR");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT title,summary,content,cover,status,visibility_state,review_state,
                       pending_revision_id,published_revision_id,lock_version
                FROM article WHERE id=?
                """, ARTICLE_ID))
                .containsEntry("title", "legacy-sentinel")
                .containsEntry("summary", "legacy-summary")
                .containsEntry("content", "legacy-body")
                .containsEntry("cover", "legacy-cover")
                .containsEntry("status", 2)
                .containsEntry("visibility_state", "PRIVATE")
                .containsEntry("review_state", "HUMAN_PENDING")
                .containsEntry("pending_revision_id", revisionId)
                .containsEntry("published_revision_id", null)
                .containsEntry("lock_version", 7L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state,reviewer_id,review_reason,lock_version
                FROM article_moderation_job WHERE id=?
                """, jobId))
                .containsEntry("state", "HUMAN_PENDING")
                .containsEntry("reviewer_id", null)
                .containsEntry("review_reason", null)
                .containsEntry("lock_version", 3L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.name FROM tag t JOIN article_tag at ON at.tag_id=t.id
                WHERE at.article_id=? ORDER BY t.name
                """, String.class, ARTICLE_ID)).containsExactly("task8-old");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=?",
                String.class, ARTICLE_ID)).isEqualTo("draft-body");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?",
                Integer.class, ARTICLE_ID)).isZero();
    }

    @Test
    void jwtApprovalDispatchesOneEventThroughRabbitToSearchAndNotificationInboxes()
            throws Exception {
        var search = listenerRegistry.getListenerContainer("articleSearchProjectionConsumer");
        var notification = listenerRegistry.getListenerContainer(
                "articleModerationNotificationConsumer");
        assertThat(search).isNotNull();
        assertThat(notification).isNotNull();
        search.start();
        notification.start();
        try {
            ResponseEntity<String> response = decide(
                    "approve", revisionId, 3, 7, "fanout approve");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            String eventId = jdbcTemplate.queryForObject("""
                    SELECT BIN_TO_UUID(event_id) FROM domain_event_outbox
                    WHERE aggregate_id=? AND event_type='ARTICLE_REVISION_PUBLISHED'
                    """, String.class, ARTICLE_ID);

            outboxDispatcher.dispatchPending();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT state FROM domain_event_outbox
                        WHERE event_id=UUID_TO_BIN(?)
                        """, String.class, eventId)).isEqualTo("PUBLISHED");
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM consumer_inbox
                        WHERE consumer_name IN ('article-search-current-pointer',
                                                 'article-moderation-notification')
                          AND event_id=UUID_TO_BIN(?)
                        """, Long.class, eventId)).isEqualTo(2L);
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM message
                        WHERE target_id=? AND type=4 AND source_event_id=UUID_TO_BIN(?)
                        """, Long.class, ARTICLE_ID, eventId)).isEqualTo(1L);
                var document = articleRepository.findById(ARTICLE_ID).orElseThrow();
                assertThat(document.getRevisionId()).isEqualTo(revisionId);
                assertThat(document.getContentHash()).isEqualTo(canonicalizer.canonicalize(
                        "frozen-title", "frozen-summary", "frozen-body", "frozen-cover",
                        List.of("frozen-tag")).contentHash());
                assertThat(document.getTitle()).isEqualTo("frozen-title");
            });
        }
        finally {
            notification.stop();
            search.stop();
        }
    }

    @Test
    void approveMirrorsCaseDistinctFrozenTagsExactly() {
        ArticleContentSnapshot snapshot = canonicalizer.canonicalize(
                "frozen-title", "frozen-summary", "frozen-body", "frozen-cover",
                List.of("task8-AI", "task8-ai"));
        replacePendingRevision(snapshot);

        ResponseEntity<String> response = decide("approve", revisionId, 3, 7,
                "approve exact tags");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.name FROM tag t JOIN article_tag at ON at.tag_id=t.id
                WHERE at.article_id=? ORDER BY BINARY t.name
                """, String.class, ARTICLE_ID)).containsExactly("task8-AI", "task8-ai");
    }

    @Test
    void approveMirrorsAMediumTextFrozenBodyWithoutADataTooLongFailure() {
        String largeBody = "中".repeat(22_000);
        ArticleContentSnapshot snapshot = canonicalizer.canonicalize(
                "large-title", "large-summary", largeBody, "", List.of("task8-large"));
        replacePendingRevision(snapshot);

        ResponseEntity<String> response = decide("approve", revisionId, 3, 7,
                "approve medium text");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT OCTET_LENGTH(content) FROM article WHERE id=?", Long.class, ARTICLE_ID))
                .isGreaterThan(65_535L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM article WHERE id=?", String.class, ARTICLE_ID))
                .isEqualTo(largeBody);
    }

    @Test
    void concurrentFirstUseOfTheSameExactTagConvergesForTwoArticleApprovals()
            throws Exception {
        String sharedTag = "task8-concurrent-shared";
        ArticleContentSnapshot firstSnapshot = canonicalizer.canonicalize(
                "first", "", "first-body", "", List.of(sharedTag));
        replacePendingRevision(firstSnapshot);
        PendingDecision second = insertPendingDecision(
                95_102L, canonicalizer.canonicalize(
                        "second", "", "second-body", "", List.of(sharedTag)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch bothAtFirstInsert = new CountDownLatch(2);
        CountDownLatch releaseFirstInsert = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothAtFirstInsert.countDown();
            assertThat(releaseFirstInsert.await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(exactTagStore).getOrCreate(eq(sharedTag));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ResponseEntity<String>> first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return decide(jobId, "approve", revisionId, 3, 7, "first shared tag");
            });
            Future<ResponseEntity<String>> secondResponse = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return decide(second.jobId(), "approve", second.revisionId(),
                        3, 7, "second shared tag");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(bothAtFirstInsert.await(5, TimeUnit.SECONDS)).isTrue();
            releaseFirstInsert.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS).getStatusCode().value(),
                    secondResponse.get(10, TimeUnit.SECONDS).getStatusCode().value()))
                    .containsExactly(200, 200);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tag WHERE name=? COLLATE utf8mb4_0900_bin",
                Integer.class, sharedTag)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_tag at JOIN tag t ON t.id=at.tag_id
                WHERE at.article_id IN (?,?) AND t.name=? COLLATE utf8mb4_0900_bin
                """, Integer.class, ARTICLE_ID, second.articleId(), sharedTag)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_moderation_job
                WHERE id IN (?,?) AND state='HUMAN_APPROVED'
                """, Integer.class, jobId, second.jobId())).isEqualTo(2);
    }

    @Test
    void staleArticleVersionIsProblemDetailAndRollsBackTheWholeDecision() throws Exception {
        ResponseEntity<String> response = decide("reject", revisionId, 3, 6, "stale");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(objectMapper.readTree(response.getBody()).path("code").asText())
                .isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,pending_revision_id,published_revision_id,lock_version FROM article WHERE id=?
                """, ARTICLE_ID))
                .containsEntry("status", 2)
                .containsEntry("pending_revision_id", revisionId)
                .containsEntry("published_revision_id", null)
                .containsEntry("lock_version", 7L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state,reviewer_id,lock_version FROM article_moderation_job WHERE id=?
                """, jobId))
                .containsEntry("state", "HUMAN_PENDING")
                .containsEntry("reviewer_id", null)
                .containsEntry("lock_version", 3L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?",
                Integer.class, ARTICLE_ID)).isZero();
    }

    @Test
    void rejectWithoutPreviousPublicationKeepsDraftAndBecomesPrivate() {
        ResponseEntity<String> response = decide("reject", revisionId, 3, 7, "human reject");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT title,content,status,visibility_state,review_state,pending_revision_id,
                       published_revision_id,lock_version
                FROM article WHERE id=?
                """, ARTICLE_ID))
                .containsEntry("title", "legacy-sentinel")
                .containsEntry("content", "legacy-body")
                .containsEntry("status", 3)
                .containsEntry("visibility_state", "PRIVATE")
                .containsEntry("review_state", "REJECTED")
                .containsEntry("pending_revision_id", null)
                .containsEntry("published_revision_id", null)
                .containsEntry("lock_version", 8L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=?", String.class, ARTICLE_ID))
                .isEqualTo("draft-body");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_id=? AND event_type='ARTICLE_REVISION_REJECTED'
                """, Integer.class, ARTICLE_ID)).isEqualTo(1);
    }

    @Test
    void rejectReplacementPreservesOldPublishedPointerMirrorAndTags() {
        ArticleContentSnapshot old = canonicalizer.canonicalize(
                "old-public-title", "old-public-summary", "old-public-body", "old-public-cover",
                List.of("task8-old"));
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                     content_hash,source_draft_version,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,NOW(6))
                """, ARTICLE_ID, 2, old.title(), old.summary(), old.bodyMarkdown(), old.bodyPlain(),
                old.cover(), old.tagsJson(), old.contentHash(), 1, AUTHOR_ID);
        long oldRevisionId = jdbcTemplate.queryForObject("""
                SELECT id FROM article_revision WHERE article_id=? AND revision_no=2
                """, Long.class, ARTICLE_ID);
        jdbcTemplate.update("""
                UPDATE article SET title=?,summary=?,content=?,cover=?,status=1,visibility_state='PUBLIC',
                    published_revision_id=? WHERE id=?
                """, old.title(), old.summary(), old.bodyMarkdown(), old.cover(), oldRevisionId, ARTICLE_ID);

        ResponseEntity<String> response = decide("reject", revisionId, 3, 7, "replacement rejected");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT title,summary,content,cover,status,visibility_state,pending_revision_id,
                       published_revision_id FROM article WHERE id=?
                """, ARTICLE_ID))
                .containsEntry("title", "old-public-title")
                .containsEntry("summary", "old-public-summary")
                .containsEntry("content", "old-public-body")
                .containsEntry("cover", "old-public-cover")
                .containsEntry("status", 1)
                .containsEntry("visibility_state", "PUBLIC")
                .containsEntry("pending_revision_id", null)
                .containsEntry("published_revision_id", oldRevisionId);
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.name FROM tag t JOIN article_tag at ON at.tag_id=t.id
                WHERE at.article_id=? ORDER BY t.name
                """, String.class, ARTICLE_ID)).containsExactly("task8-old");
    }

    @Test
    void rejectedReplacementCanRecycleAndRestoreTheApprovedOldPublicationInTheNewLifecycle() {
        ArticleContentSnapshot old = canonicalizer.canonicalize(
                "old-public-title", "old-public-summary", "old-public-body", "old-public-cover",
                List.of("task8-old"));
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                     content_hash,source_draft_version,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,NOW(6))
                """, ARTICLE_ID, 2, old.title(), old.summary(), old.bodyMarkdown(), old.bodyPlain(),
                old.cover(), old.tagsJson(), old.contentHash(), 1, AUTHOR_ID);
        long oldRevisionId = jdbcTemplate.queryForObject("""
                SELECT id FROM article_revision WHERE article_id=? AND revision_no=2
                """, Long.class, ARTICLE_ID);
        jdbcTemplate.update("""
                UPDATE article SET title=?,summary=?,content=?,cover=?,status=1,visibility_state='PUBLIC',
                    review_state='HUMAN_PENDING',published_revision_id=? WHERE id=?
                """, old.title(), old.summary(), old.bodyMarkdown(), old.cover(), oldRevisionId, ARTICLE_ID);

        ResponseEntity<String> rejected = decide("reject", revisionId, 3, 7, "replacement rejected");

        assertThat(rejected.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT review_state,published_revision_id,lifecycle_epoch FROM article WHERE id=?
                """, ARTICLE_ID))
                .containsEntry("review_state", "APPROVED")
                .containsEntry("published_revision_id", oldRevisionId)
                .containsEntry("lifecycle_epoch", 1L);

        mutationFacade.recycle(ARTICLE_ID, AUTHOR_ID);
        mutationFacade.restore(ARTICLE_ID, AUTHOR_ID);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT title,content,status,is_deleted,visibility_state,review_state,
                       published_revision_id,lifecycle_epoch
                FROM article WHERE id=?
                """, ARTICLE_ID))
                .containsEntry("title", "old-public-title")
                .containsEntry("content", "old-public-body")
                .containsEntry("status", 1)
                .containsEntry("is_deleted", 0)
                .containsEntry("visibility_state", "PUBLIC")
                .containsEntry("review_state", "APPROVED")
                .containsEntry("published_revision_id", oldRevisionId)
                .containsEntry("lifecycle_epoch", 2L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.name FROM tag t JOIN article_tag at ON at.tag_id=t.id
                WHERE at.article_id=? ORDER BY t.name
                """, String.class, ARTICLE_ID)).containsExactly("task8-old");
        var restoreEvent = jdbcTemplate.queryForMap("""
                SELECT lifecycle_epoch,
                       CAST(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.publishedRevisionId')) AS UNSIGNED)
                           AS published_revision_id
                FROM domain_event_outbox
                WHERE aggregate_id=? AND event_type='ARTICLE_REVISION_PUBLISHED'
                """, ARTICLE_ID);
        assertThat(restoreEvent).containsEntry("lifecycle_epoch", 2L);
        assertThat(((Number) restoreEvent.get("published_revision_id")).longValue())
                .isEqualTo(oldRevisionId);
    }

    @Test
    void wrongRevisionHashStateOrJobVersionCannotDecide() throws Exception {
        assertOptimisticConflict(decide("approve", revisionId, 2, 7, "stale job"));
        assertOptimisticConflict(decide("approve", revisionId + 99, 3, 7, "cross revision"));

        jdbcTemplate.update("UPDATE article_moderation_job SET state='RUNNING' WHERE id=?", jobId);
        assertOptimisticConflict(decide("approve", revisionId, 3, 7, "wrong state"));
        jdbcTemplate.update("UPDATE article_moderation_job SET state='HUMAN_PENDING',content_hash=? WHERE id=?",
                "f".repeat(64), jobId);
        assertOptimisticConflict(decide("approve", revisionId, 3, 7, "changed hash"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?",
                Integer.class, ARTICLE_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pending_revision_id FROM article WHERE id=?", Long.class, ARTICLE_ID))
                .isEqualTo(revisionId);
    }

    @Test
    void humanPendingRowWithAnActiveLeaseCannotBeDecided() throws Exception {
        jdbcTemplate.update("""
                UPDATE article_moderation_job
                SET lease_owner='corrupt-active-worker',lease_until=TIMESTAMPADD(MINUTE,5,NOW(6))
                WHERE id=?
                """, jobId);

        ResponseEntity<String> response = decide("approve", revisionId, 3, 7, "must not race lease");

        assertOptimisticConflict(response);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state,reviewer_id,lease_owner,lock_version
                FROM article_moderation_job WHERE id=?
                """, jobId))
                .containsEntry("state", "HUMAN_PENDING")
                .containsEntry("reviewer_id", null)
                .containsEntry("lease_owner", "corrupt-active-worker")
                .containsEntry("lock_version", 3L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT pending_revision_id,published_revision_id,lock_version FROM article WHERE id=?
                """, ARTICLE_ID))
                .containsEntry("pending_revision_id", revisionId)
                .containsEntry("published_revision_id", null)
                .containsEntry("lock_version", 7L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.name FROM tag t JOIN article_tag at ON at.tag_id=t.id
                WHERE at.article_id=? ORDER BY t.name
                """, String.class, ARTICLE_ID)).containsExactly("task8-old");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?",
                Integer.class, ARTICLE_ID)).isZero();
    }

    @Test
    void concurrentOppositeDecisionsHaveExactlyOneWinnerAndOneEvent() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> approve = executor.submit(
                    () -> decideAfterBarrier("approve", "concurrent approve", ready, start));
            Future<ResponseEntity<String>> reject = executor.submit(
                    () -> decideAfterBarrier("reject", "concurrent reject", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(
                    approve.get(10, TimeUnit.SECONDS).getStatusCode().value(),
                    reject.get(10, TimeUnit.SECONDS).getStatusCode().value()));
            Collections.sort(statuses);
            assertThat(statuses).containsExactly(200, 409);
        }
        finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM article_moderation_job
                WHERE id=? AND state IN ('HUMAN_APPROVED','HUMAN_REJECTED')
                """, Integer.class, jobId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?",
                Integer.class, ARTICLE_ID)).isEqualTo(1);
    }

    @Test
    void approvalSerializesBeforeAuthorResubmitWithoutDeadlockOrPartialSecondDecision()
            throws Exception {
        ApprovalBarrier barrier = blockApprovalBeforeArticleCas();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ResponseEntity<String>> approval = executor.submit(
                    () -> decide("approve", revisionId, 3, 7, "approve before resubmit"));
            assertThat(barrier.entered().await(5, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<String>> resubmit = executor.submit(this::resubmitAsAuthor);
            barrier.release().countDown();

            assertThat(approval.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(resubmit.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(409);
        }
        finally {
            barrier.release().countDown();
        }

        assertOneApprovedDecisionEvent();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT pending_revision_id,published_revision_id,status,is_deleted,lock_version
                FROM article WHERE id=?
                """, ARTICLE_ID))
                .containsEntry("pending_revision_id", null)
                .containsEntry("published_revision_id", revisionId)
                .containsEntry("status", 1)
                .containsEntry("is_deleted", 0)
                .containsEntry("lock_version", 8L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id=?",
                Long.class, ARTICLE_ID)).isOne();
    }

    @Test
    void approvalThenRecycleConvergesToOneDecisionAndOneHigherEpochTombstone()
            throws Exception {
        ApprovalBarrier barrier = blockApprovalBeforeArticleCas();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ResponseEntity<String>> approval = executor.submit(
                    () -> decide("approve", revisionId, 3, 7, "approve before recycle"));
            assertThat(barrier.entered().await(5, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<String>> recycle = executor.submit(this::recycleAsAuthor);
            barrier.release().countDown();

            assertThat(approval.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(recycle.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        }
        finally {
            barrier.release().countDown();
        }

        assertOneApprovedDecisionEvent();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT pending_revision_id,published_revision_id,status,is_deleted,
                       visibility_state,lifecycle_epoch,lock_version
                FROM article WHERE id=?
                """, ARTICLE_ID))
                .containsEntry("pending_revision_id", null)
                .containsEntry("published_revision_id", revisionId)
                .containsEntry("status", 1)
                .containsEntry("is_deleted", 1)
                .containsEntry("visibility_state", "RECYCLED")
                .containsEntry("lifecycle_epoch", 2L)
                .containsEntry("lock_version", 9L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_id=? AND event_type='ARTICLE_DELETED'
                  AND lifecycle_epoch=2 AND aggregate_version=9
                """, Long.class, ARTICLE_ID)).isOne();
    }

    @Test
    void approvalSerializesAgainstLateTaskSevenWorkerWithoutModelStateOverwrite()
            throws Exception {
        ApprovalBarrier barrier = blockApprovalBeforeArticleCas();
        DomainEvent submitted = submittedEvent();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ResponseEntity<String>> approval = executor.submit(
                    () -> decide("approve", revisionId, 3, 7, "approve before late worker"));
            assertThat(barrier.entered().await(5, TimeUnit.SECONDS)).isTrue();
            Future<ArticleModerationWorker.ProcessOutcome> worker = executor.submit(
                    () -> moderationWorker.process(submitted));
            barrier.release().countDown();

            assertThat(approval.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(worker.get(10, TimeUnit.SECONDS))
                    .isEqualTo(ArticleModerationWorker.ProcessOutcome.COMPLETE);
        }
        finally {
            barrier.release().countDown();
        }

        assertOneApprovedDecisionEvent();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state,model_decision,lease_owner,lease_until,lock_version
                FROM article_moderation_job WHERE id=?
                """, jobId))
                .containsEntry("state", "HUMAN_APPROVED")
                .containsEntry("model_decision", null)
                .containsEntry("lease_owner", null)
                .containsEntry("lease_until", null)
                .containsEntry("lock_version", 4L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_moderation_attempt WHERE job_id=?",
                Long.class, jobId)).isZero();
    }

    private ResponseEntity<String> get(String path, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(userId));
        return restTemplate.exchange(url(path), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> resubmitAsAuthor() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(AUTHOR_ID));
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"id":%d,"title":"new-title","summary":"new-summary",
                 "content":"new-body","cover":"new-cover","tags":["task8-resubmit"],
                 "isPublish":true,"expectedDraftVersion":5}
                """.formatted(ARTICLE_ID);
        return restTemplate.postForEntity(
                url("/api/article/publish"), new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> recycleAsAuthor() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(AUTHOR_ID));
        return restTemplate.exchange(url("/api/article/" + ARTICLE_ID),
                org.springframework.http.HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    private ApprovalBarrier blockApprovalBeforeArticleCas() {
        ApprovalBarrier barrier = new ApprovalBarrier(
                new CountDownLatch(1), new CountDownLatch(1));
        doAnswer(invocation -> {
            barrier.entered().countDown();
            assertThat(barrier.release().await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(mirrorWriter).publishLocked(eq(ARTICLE_ID), any(ArticleRevision.class),
                eq(7L), any(LocalDateTime.class));
        return barrier;
    }

    private DomainEvent submittedEvent() {
        ArticleContentSnapshot snapshot = canonicalizer.canonicalize(
                "frozen-title", "frozen-summary", "frozen-body", "frozen-cover",
                List.of("frozen-tag"));
        return new DomainEvent(UUID.randomUUID(), "ARTICLE", ARTICLE_ID, 7, 1,
                DomainEventType.ARTICLE_REVISION_SUBMITTED, 1,
                objectMapper.createObjectNode()
                        .put("articleId", ARTICLE_ID)
                        .put("revisionId", revisionId)
                        .put("moderationJobId", jobId)
                        .put("contentHash", snapshot.contentHash()),
                Instant.now());
    }

    private void assertOneApprovedDecisionEvent() {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state,reviewer_id,lock_version FROM article_moderation_job WHERE id=?
                """, jobId))
                .containsEntry("state", "HUMAN_APPROVED")
                .containsEntry("reviewer_id", ADMIN_ID)
                .containsEntry("lock_version", 4L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_id=? AND event_type IN
                    ('ARTICLE_REVISION_PUBLISHED','ARTICLE_REVISION_REJECTED')
                """, Long.class, ARTICLE_ID)).isOne();
    }

    private ResponseEntity<String> decide(String action, long requestRevisionId,
                                          long expectedJobVersion, long expectedArticleVersion,
                                          String reason) {
        return decide(jobId, action, requestRevisionId, expectedJobVersion,
                expectedArticleVersion, reason);
    }

    private ResponseEntity<String> decide(long requestJobId, String action,
                                          long requestRevisionId,
                                          long expectedJobVersion, long expectedArticleVersion,
                                          String reason) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(ADMIN_ID));
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"revisionId":%d,"expectedJobVersion":%d,"expectedArticleVersion":%d,"reason":"%s"}
                """.formatted(requestRevisionId, expectedJobVersion, expectedArticleVersion, reason);
        return restTemplate.postForEntity(
                url("/api/admin/moderation/jobs/" + requestJobId + "/" + action),
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> decideAfterBarrier(String action, String reason,
                                                      CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return decide(action, revisionId, 3, 7, reason);
    }

    private void replacePendingRevision(ArticleContentSnapshot snapshot) {
        jdbcTemplate.update("""
                UPDATE article_revision
                SET title=?,summary=?,body_markdown=?,body_plain=?,cover=?,tags_json=?,content_hash=?
                WHERE id=?
                """, snapshot.title(), snapshot.summary(), snapshot.bodyMarkdown(),
                snapshot.bodyPlain(), snapshot.cover(), snapshot.tagsJson(), snapshot.contentHash(),
                revisionId);
        jdbcTemplate.update("UPDATE article_moderation_job SET content_hash=? WHERE id=?",
                snapshot.contentHash(), jobId);
    }

    private PendingDecision insertPendingDecision(long articleId,
                                                   ArticleContentSnapshot snapshot) {
        jdbcTemplate.update("""
                INSERT INTO article
                    (id,title,summary,content,author_id,view_count,like_count,comment_count,collect_count,
                     create_time,update_time,status,cover,is_deleted,visibility_state,review_state,
                     lifecycle_epoch,lock_version)
                VALUES (?, 'second-legacy', '', 'second-legacy-body', ?, 0, 0, 0, 0,
                        NOW(6),NOW(6),2,'',0,'PRIVATE','HUMAN_PENDING',1,7)
                """, articleId, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id,revision_no,title,summary,body_markdown,body_plain,cover,tags_json,
                     content_hash,source_draft_version,created_by,created_at)
                VALUES (?,1,?,?,?,?,?,?,?,?,?,NOW(6))
                """, articleId, snapshot.title(), snapshot.summary(), snapshot.bodyMarkdown(),
                snapshot.bodyPlain(), snapshot.cover(), snapshot.tagsJson(), snapshot.contentHash(),
                1, AUTHOR_ID);
        long pendingRevisionId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_revision WHERE article_id=?", Long.class, articleId);
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,pending_revision_id=? WHERE id=?
                """, pendingRevisionId, pendingRevisionId, articleId);
        jdbcTemplate.update("""
                INSERT INTO article_moderation_job
                    (article_id,revision_id,content_hash,state,attempt_count,created_at,updated_at,lock_version)
                VALUES (?,?,?,'HUMAN_PENDING',0,NOW(6),NOW(6),3)
                """, articleId, pendingRevisionId, snapshot.contentHash());
        long pendingJobId = jdbcTemplate.queryForObject(
                "SELECT id FROM article_moderation_job WHERE article_id=?", Long.class, articleId);
        return new PendingDecision(articleId, pendingRevisionId, pendingJobId);
    }

    private record PendingDecision(long articleId, long revisionId, long jobId) {
    }

    private record ApprovalBarrier(CountDownLatch entered, CountDownLatch release) {
    }

    private void assertOptimisticConflict(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(objectMapper.readTree(response.getBody()).path("code").asText())
                .isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
    }
}
