package cumt.zongzuo.community.article;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.service.ArticleMutationFacade;
import cumt.zongzuo.community.article.web.SaveArticleDraftCommand;
import cumt.zongzuo.community.article.web.SubmitArticleRevisionCommand;
import cumt.zongzuo.community.dto.ArticleDTO;
import cumt.zongzuo.community.service.ArticleService;
import cumt.zongzuo.community.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract class AbstractArticleRevisionFenceIntegrationTest extends IntegrationTestSupport {

    private static final long AUTHOR_ID = 92_001L;
    private static final long ADMIN_ID = 92_002L;

    @Autowired
    private ArticleRevisionModeResolver modeResolver;
    @Autowired
    private ArticleMutationFacade mutationFacade;
    @Autowired
    private ArticleService articleService;
    @Autowired
    private ReportService reportService;

    protected abstract ArticleRevisionMode expectedMode();

    @BeforeEach
    void cleanFenceFixture() {
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE' AND aggregate_id BETWEEN 92000 AND 92999");
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id BETWEEN 92000 AND 92999");
        jdbcTemplate.update("DELETE FROM article_moderation_job WHERE article_id BETWEEN 92000 AND 92999");
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id BETWEEN 92000 AND 92999");
        jdbcTemplate.update("DELETE FROM article_draft WHERE article_id BETWEEN 92000 AND 92999");
        jdbcTemplate.update("DELETE FROM article_tag WHERE article_id BETWEEN 92000 AND 92999");
        jdbcTemplate.update("DELETE FROM report WHERE id BETWEEN 92000 AND 92999");
        jdbcTemplate.update("DELETE FROM article WHERE id BETWEEN 92000 AND 92999");
        jdbcTemplate.update("""
                INSERT INTO sys_user (id,username,password,email,role,status)
                VALUES (?, 'fence-author', 'unused', 'fence-author@example.com', 0, 0),
                       (?, 'fence-admin', 'unused', 'fence-admin@example.com', 1, 0)
                ON DUPLICATE KEY UPDATE role=VALUES(role),status=0
                """, AUTHOR_ID, ADMIN_ID);
    }

    @Test
    void blocksEveryContentLifecycleAndInternalArticleWriteWithStable503() {
        insertArticle(92_101L, 0, 0, "draft");
        insertArticle(92_102L, 2, 0, "pending");
        insertArticle(92_103L, 1, 1, "recycled");
        insertArticle(92_104L, 1, 0, "reported");
        jdbcTemplate.update("UPDATE article SET delete_time=? WHERE id=?",
                LocalDateTime.now().minusDays(8), 92_103L);
        jdbcTemplate.update("""
                INSERT INTO report (id,reporter_id,target_id,target_type,reason,status,create_time)
                VALUES (?,?,?,?,?,0,NOW(6))
                """, 92_104L, AUTHOR_ID, 92_104L, 1, "policy");

        assertThat(modeResolver.current()).isEqualTo(expectedMode());
        assertFenced(() -> mutationFacade.saveDraft(new SaveArticleDraftCommand(
                92_101L, 0, "changed", "", "changed", "", List.of()), AUTHOR_ID));
        assertFenced(() -> mutationFacade.submit(new SubmitArticleRevisionCommand(92_101L, AUTHOR_ID, 0)));

        ArticleDTO dto = new ArticleDTO();
        dto.setId(92_101L);
        dto.setTitle("changed");
        dto.setContent("changed");
        assertFenced(() -> articleService.publishOrSave(dto, false, AUTHOR_ID));
        assertFenced(() -> articleService.moveToRecycleBin(92_101L, AUTHOR_ID));
        assertFenced(() -> articleService.restoreArticle(92_103L, AUTHOR_ID));
        assertFenced(() -> articleService.deletePermanently(92_103L, AUTHOR_ID));
        assertFenced(articleService::cleanExpiredArticles);
        assertFenced(() -> articleService.auditArticle(92_102L, true, "ok"));
        assertFenced(() -> reportService.processReport(ADMIN_ID, 92_104L, true, "confirmed"));
        assertFenced(() -> mutationFacade.addLikeCount(92_101L, 1));
        assertFenced(() -> mutationFacade.addCommentCount(92_101L, 1));
        assertFenced(() -> mutationFacade.syncViewCount(92_101L, 99));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT title,content,status,is_deleted,like_count,comment_count,view_count
                FROM article WHERE id=92101
                """))
                .containsEntry("title", "draft")
                .containsEntry("content", "draft-body")
                .containsEntry("status", 0)
                .containsEntry("is_deleted", 0)
                .containsEntry("like_count", 0)
                .containsEntry("comment_count", 0)
                .containsEntry("view_count", 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_draft WHERE article_id BETWEEN 92000 AND 92999",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_revision WHERE article_id BETWEEN 92000 AND 92999",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id BETWEEN 92000 AND 92999",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM report WHERE id=92104", Integer.class)).isZero();
    }

    private void assertFenced(Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode().value()).isEqualTo(503);
                    assertThat(response.getReason()).isEqualTo("ARTICLE_CUTOVER_IN_PROGRESS");
                });
    }

    private void insertArticle(long id, int status, int deleted, String title) {
        jdbcTemplate.update("""
                INSERT INTO article
                    (id,title,summary,content,author_id,view_count,like_count,comment_count,collect_count,
                     create_time,update_time,status,cover,is_deleted,lifecycle_epoch,lock_version)
                VALUES (?,?,?, ?,?,0,0,0,0,NOW(6),NOW(6),?,'',?,1,0)
                """, id, title, "summary", title + "-body", AUTHOR_ID, status, deleted);
    }
}
