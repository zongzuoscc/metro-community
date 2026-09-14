package cumt.zongzuo.community.article;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.article.service.ArticleMutationFacade;
import cumt.zongzuo.community.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

/** 兼容旧文章模式也必须在真实写入入口生成可靠的失效事件，不能只修新版审核。 */
@TestPropertySource(properties = "metro.article.revision-mode=LEGACY")
class ArticleLegacyCacheInvalidationIntegrationTest extends IntegrationTestSupport {
    private static final long ID = 9_001_001L;
    private static final long AUTHOR = 9_001_002L;
    @Autowired private ArticleService articles;
    @Autowired private ArticleMutationFacade mutations;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void seed() {
        redis.delete("article:detail:public:v3:legacy:" + ID);
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE' AND aggregate_id=?", ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user (id,username,password,email,role,status)
                VALUES (?,'cache-author','unused','cache-author@example.com',0,0)
                ON DUPLICATE KEY UPDATE status=0
                """, AUTHOR);
        jdbcTemplate.update("""
                INSERT INTO article (id,title,content,author_id,status,is_deleted,visibility_state,
                                     lifecycle_epoch,lock_version,view_count,create_time,update_time)
                VALUES (?,'缓存验证','公开正文',?,1,0,'PUBLIC',0,0,0,NOW(),NOW())
                """, ID, AUTHOR);
    }

    @Test
    void recycleAndRestoreEvictBothPublicAndMissingCache() {
        assertThat(articles.getDetail(ID).getContent()).isEqualTo("公开正文");
        mutations.recycle(ID, AUTHOR);
        assertThatThrownBy(() -> articles.getDetail(ID)).isInstanceOf(ResponseStatusException.class);
        mutations.restore(ID, AUTHOR);
        assertThat(articles.getDetail(ID).getContent()).isEqualTo("公开正文");
        assertThat(invalidationEvents()).isEqualTo(2);
    }

    @Test
    void reportRejectionEvictsPreviouslyPublicArticle() {
        articles.getDetail(ID);
        mutations.rejectReportedArticle(ID);
        assertThatThrownBy(() -> articles.getDetail(ID)).isInstanceOf(ResponseStatusException.class);
        assertThat(invalidationEvents()).isEqualTo(1);
    }

    @Test
    void approvalEvictsNegativeCacheAfterDatabaseCommit() {
        jdbcTemplate.update("UPDATE article SET status=2 WHERE id=?", ID);
        assertThatThrownBy(() -> articles.getDetail(ID)).isInstanceOf(ResponseStatusException.class);
        mutations.auditLegacyArticle(ID, true, "通过", AUTHOR);
        assertThat(articles.getDetail(ID).getContent()).isEqualTo("公开正文");
        assertThat(invalidationEvents()).isEqualTo(1);
    }

    private int invalidationEvents() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_type='ARTICLE' AND aggregate_id=?
                  AND event_type='ARTICLE_DETAIL_CACHE_INVALIDATED'
                """, Integer.class, ID);
    }
}
