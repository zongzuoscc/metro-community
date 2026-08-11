package cumt.zongzuo.community.article.chunk;

import cumt.zongzuo.community.IntegrationTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "metro.projection.article-chunks.enabled=true",
        "metro.projection.article-chunks.parser-generation=2"
})
class ArticleChunkParserGenerationIntegrationTest extends IntegrationTestSupport {

    private static final long ARTICLE_ID = 88_401L;
    private static final long REVISION_ID = 88_501L;

    @Autowired
    private ArticleChunkMaterializationService materializationService;

    @BeforeEach
    void seedPreviousGeneration() {
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk");
        jdbcTemplate.update("DELETE FROM article_chunk_set");
        jdbcTemplate.update("DELETE FROM article_chunk_parser_checkpoint");
        jdbcTemplate.update("DELETE FROM article_chunk_parser_generation");
        jdbcTemplate.update("""
                UPDATE article
                SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL
                WHERE id=?
                """, ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article(id,title,content,summary,author_id,status,is_deleted,create_time,
                  visibility_state,review_state,lifecycle_epoch,lock_version)
                VALUES (?, '代际标题', '代际正文', '摘要', 44, 1, 0, CURRENT_TIMESTAMP(6),
                  'PUBLIC','APPROVED',1,9)
                """, ARTICLE_ID);
        jdbcTemplate.update("""
                INSERT INTO article_revision(id,article_id,revision_no,title,summary,body_markdown,
                  body_plain,cover,tags_json,content_hash,source_draft_version,created_by,created_at)
                VALUES (?, ?, 1, '代际标题', '摘要', '# 代际\n\n同一文章版本重新分块',
                  '同一文章版本重新分块', NULL, '[]', ?, 1, 44, CURRENT_TIMESTAMP(6))
                """, REVISION_ID, ARTICLE_ID, "f".repeat(64));
        jdbcTemplate.update("""
                UPDATE article SET latest_revision_id=?,published_revision_id=? WHERE id=?
                """, REVISION_ID, REVISION_ID, ARTICLE_ID);
        insertGeneration(1, "DRAINING");
        insertGeneration(2, "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO article_chunk_parser_checkpoint
                  (checkpoint_id,active_generation,lock_version,updated_by,updated_at)
                VALUES (1,2,1,'test',CURRENT_TIMESTAMP(6))
                """);
        jdbcTemplate.update("""
                INSERT INTO article_chunk_set
                  (article_id,published_revision_id,parser_generation,parser_version,
                   chunk_set_version,source_lifecycle_epoch,source_aggregate_version,
                   chunk_set_hash,active_chunk_count,published_at,lock_version,updated_at)
                VALUES (?,?,1,?,1,1,9,?,0,CURRENT_TIMESTAMP(6),0,CURRENT_TIMESTAMP(6))
                """, ARTICLE_ID, REVISION_ID, ArticleChunker.PARSER_VERSION, "1".repeat(64));
    }

    @AfterAll
    void removeGenerationFixture() {
        jdbcTemplate.update("DELETE FROM domain_event_outbox WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_chunk_set WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("UPDATE article SET latest_revision_id=NULL,pending_revision_id=NULL,published_revision_id=NULL WHERE id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article_revision WHERE article_id=?", ARTICLE_ID);
        jdbcTemplate.update("DELETE FROM article WHERE id=?", ARTICLE_ID);
    }

    @Test
    void newParserGenerationAdvancesChunkSetWithoutChangingArticleVersion() {
        var result = materializationService.materialize(ARTICLE_ID, 1L, 9L);

        assertThat(result.applied()).isTrue();
        assertThat(result.chunkSetVersion()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT parser_generation FROM article_chunk_set WHERE article_id=?",
                Long.class, ARTICLE_ID)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_chunk WHERE article_id=? AND parser_generation=2 AND is_active=1",
                Integer.class, ARTICLE_ID)).isPositive();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_event_outbox
                WHERE aggregate_type='ARTICLE_CHUNK_SET' AND aggregate_id=?
                  AND aggregate_version=2 AND event_type='ARTICLE_CHUNK_REINDEX_REQUESTED'
                """, Integer.class, ARTICLE_ID)).isEqualTo(1);
    }

    private void insertGeneration(long generation, String state) {
        jdbcTemplate.update("""
                INSERT INTO article_chunk_parser_generation
                  (generation,parser_version,token_estimator_version,dependency_fingerprint,
                   required_build_digest,state,operator_identity,created_at,updated_at,lock_version)
                VALUES (?,?,?,?,?,?, 'test',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
                """, generation, ArticleChunker.PARSER_VERSION,
                ArticleChunker.TOKEN_ESTIMATOR_VERSION,
                ArticleChunker.DEPENDENCY_FINGERPRINT, "a".repeat(64), state);
    }
}
