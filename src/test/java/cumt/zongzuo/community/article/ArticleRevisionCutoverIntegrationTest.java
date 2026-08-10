package cumt.zongzuo.community.article;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.article.model.ArticleDraft;
import cumt.zongzuo.community.article.service.ArticleMutationFacade;
import cumt.zongzuo.community.article.web.SaveArticleDraftCommand;
import cumt.zongzuo.community.article.web.SubmissionResult;
import cumt.zongzuo.community.article.web.SubmitArticleRevisionCommand;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "metro.article.revision-mode=CUTOVER")
class ArticleRevisionCutoverIntegrationTest extends IntegrationTestSupport {

    private static final long AUTHOR_ID = 93_001L;
    private static final long OTHER_ID = 93_002L;

    @Autowired
    private ArticleMutationFacade mutationFacade;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private ArticleService articleService;

    @BeforeEach
    void cleanCutoverFixture() {
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE' AND aggregate_id BETWEEN 93000 AND 93999");
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id BETWEEN 93000 AND 93999");
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE article_id BETWEEN 93000 AND 93999");
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id BETWEEN 93000 AND 93999");
        jdbcTemplate.update("DELETE FROM article_draft WHERE article_id BETWEEN 93000 AND 93999");
        jdbcTemplate.update("DELETE FROM article_tag WHERE article_id BETWEEN 93000 AND 93999");
        jdbcTemplate.update("DELETE FROM article WHERE id BETWEEN 93000 AND 93999");
        jdbcTemplate.update("DELETE FROM tag WHERE name LIKE 'cutover-%'");
        jdbcTemplate.update("""
                INSERT INTO sys_user (id,username,password,email,role,status)
                VALUES (?, 'cutover-author', 'unused', 'cutover-author@example.com', 0, 0),
                       (?, 'cutover-other', 'unused', 'cutover-other@example.com', 0, 0)
                ON DUPLICATE KEY UPDATE status=0
                """, AUTHOR_ID, OTHER_ID);
        for (String queue : List.of("article.audit.queue", "es.sync.queue", "message.notify.queue")) {
            rabbitAdmin.purgeQueue(queue, true);
        }
    }

    @Test
    void nativeShellKeepsPrivateTextOnlyInDraftUntilFirstSubmission() {
        ArticleDraft draft = mutationFacade.saveDraft(new SaveArticleDraftCommand(
                null, 0, "private-title", "private-summary", "private-body", "private-cover",
                List.of("cutover-private")), AUTHOR_ID);

        long articleId = draft.getArticleId();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT title,summary,content,cover,status,visibility_state,review_state
                FROM article WHERE id=?
                """, articleId))
                .containsEntry("title", "")
                .containsEntry("summary", null)
                .containsEntry("content", null)
                .containsEntry("cover", null)
                .containsEntry("status", 0)
                .containsEntry("visibility_state", "PRIVATE")
                .containsEntry("review_state", "NOT_SUBMITTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_draft WHERE article_id=?", String.class, articleId))
                .isEqualTo("private-body");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id=?", Integer.class, articleId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_tag WHERE article_id=?", Integer.class, articleId)).isZero();

        ArticleDTO publish = new ArticleDTO();
        publish.setId(articleId);
        publish.setExpectedDraftVersion(1L);
        publish.setTitle("private-title");
        publish.setSummary("private-summary");
        publish.setContent("private-body");
        publish.setCover("private-cover");
        publish.setTags(List.of("cutover-private"));
        articleService.publishOrSave(publish, true, AUTHOR_ID);
        SubmissionResult submitted = submittedResult(articleId);

        assertThat(submitted.revisionNo()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,visibility_state,latest_revision_id,pending_revision_id,published_revision_id
                FROM article WHERE id=?
                """, articleId))
                .containsEntry("status", 2)
                .containsEntry("visibility_state", "PRIVATE")
                .containsEntry("latest_revision_id", submitted.revisionId())
                .containsEntry("pending_revision_id", submitted.revisionId())
                .containsEntry("published_revision_id", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM article_moderation_job WHERE id=?", String.class,
                submitted.moderationJobId())).isEqualTo("HUMAN_PENDING");
        assertThat(rabbitTemplate.receive("article.audit.queue", 100)).isNull();
        assertThat(rabbitTemplate.receive("es.sync.queue", 100)).isNull();
    }

    @Test
    void publishedAutosaveAndSubmissionPreserveOldPublicMirrorAndTags() {
        long articleId = 93_101L;
        insertPublishedLegacy(articleId);

        ArticleDraft draft = mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 0, "pending-title", "pending-summary", "pending-body", "pending-cover",
                List.of("cutover-new")), AUTHOR_ID);
        long oldPublishedRevisionId = jdbcTemplate.queryForObject(
                "SELECT published_revision_id FROM article WHERE id=?", Long.class, articleId);

        assertLegacyMirrorUnchanged(articleId, oldPublishedRevisionId);
        SubmissionResult submitted = mutationFacade.submit(
                new SubmitArticleRevisionCommand(articleId, AUTHOR_ID, draft.getDraftVersion()));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status,visibility_state,review_state,latest_revision_id,pending_revision_id,
                       published_revision_id
                FROM article WHERE id=?
                """, articleId))
                .containsEntry("status", 1)
                .containsEntry("visibility_state", "PUBLIC")
                .containsEntry("review_state", "HUMAN_PENDING")
                .containsEntry("latest_revision_id", submitted.revisionId())
                .containsEntry("pending_revision_id", submitted.revisionId())
                .containsEntry("published_revision_id", oldPublishedRevisionId);
        assertLegacyMirrorUnchanged(articleId, oldPublishedRevisionId);

        mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, draft.getDraftVersion(), "later-title", "", "later-body", "",
                List.of("cutover-later")), AUTHOR_ID);

        assertLegacyMirrorUnchanged(articleId, oldPublishedRevisionId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id=?", Integer.class, articleId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_moderation_job WHERE article_id=?", Integer.class, articleId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body_markdown FROM article_revision WHERE id=?", String.class,
                submitted.revisionId())).isEqualTo("pending-body");
        assertThat(rabbitTemplate.receive("article.audit.queue", 100)).isNull();
        assertThat(rabbitTemplate.receive("es.sync.queue", 100)).isNull();
    }

    @Test
    void ownerScopedCutoverDraftHidesArticleExistenceFromAnotherUser() {
        long articleId = 93_102L;
        insertPublishedLegacy(articleId);

        assertThatThrownBy(() -> mutationFacade.saveDraft(new SaveArticleDraftCommand(
                articleId, 0, "stolen", "", "stolen", "", List.of()), OTHER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_draft WHERE article_id=?", Integer.class, articleId)).isZero();
    }

    @Test
    void existingCutoverHttpDraftRequiresTheClientDraftVersion() {
        long articleId = 93_103L;
        insertPublishedLegacy(articleId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(AUTHOR_ID));
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = restTemplate.postForEntity(url("/api/article/draft"), new HttpEntity<>("""
                {"id":%d,"title":"missing-version","content":"body","tags":[]}
                """.formatted(articleId), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_draft WHERE article_id=?", Integer.class, articleId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM article WHERE id=?", String.class, articleId)).isEqualTo("public-body");
    }

    private void insertPublishedLegacy(long articleId) {
        jdbcTemplate.update("""
                INSERT INTO article
                    (id,title,summary,content,author_id,view_count,like_count,comment_count,collect_count,
                     create_time,update_time,status,cover,is_deleted,lifecycle_epoch,lock_version)
                VALUES (?,'public-title','public-summary','public-body',?,0,0,0,0,
                        NOW(6),NOW(6),1,'public-cover',0,1,0)
                """, articleId, AUTHOR_ID);
        jdbcTemplate.update("""
                INSERT INTO tag (name,article_count,create_time) VALUES ('cutover-old',1,NOW(6))
                """);
        Long tagId = jdbcTemplate.queryForObject(
                "SELECT id FROM tag WHERE name='cutover-old'", Long.class);
        jdbcTemplate.update("INSERT INTO article_tag (article_id,tag_id) VALUES (?,?)", articleId, tagId);
    }

    private SubmissionResult submittedResult(long articleId) {
        var row = jdbcTemplate.queryForMap("""
                SELECT r.id revision_id,r.revision_no,j.id job_id,r.content_hash
                FROM article_revision r
                JOIN article_moderation_job j ON j.revision_id=r.id AND j.article_id=r.article_id
                WHERE r.article_id=?
                """, articleId);
        return new SubmissionResult(articleId,
                ((Number) row.get("revision_id")).longValue(),
                ((Number) row.get("revision_no")).longValue(),
                ((Number) row.get("job_id")).longValue(),
                (String) row.get("content_hash"));
    }

    private void assertLegacyMirrorUnchanged(long articleId, long publishedRevisionId) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT title,summary,content,cover,published_revision_id
                FROM article WHERE id=?
                """, articleId))
                .containsEntry("title", "public-title")
                .containsEntry("summary", "public-summary")
                .containsEntry("content", "public-body")
                .containsEntry("cover", "public-cover")
                .containsEntry("published_revision_id", publishedRevisionId);
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.name FROM tag t JOIN article_tag at ON at.tag_id=t.id
                WHERE at.article_id=? ORDER BY t.name
                """, String.class, articleId)).containsExactly("cutover-old");
    }
}
