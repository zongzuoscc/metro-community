package cumt.zongzuo.community.article.chunk;

import cumt.zongzuo.community.article.rollout.ArticleRevisionBuildIdentity;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 本地开发的一次性 Chunk Fact 引导器。
 *
 * <p>它只在 worker-service 且显式打开 local-bootstrap-enabled 时运行：首次写入当前
 * parser generation，随后把所有文章的当前指针物化为 Chunk Fact。生产环境保持关闭，
 * 仍应使用受审计的 rebuild/operator 流程。</p>
 */
@Component
@Profile("worker-service")
@Order(20)
@ConditionalOnProperty(prefix = "metro.projection.article-chunks",
        name = "local-bootstrap-enabled", havingValue = "true")
public class ArticleChunkLocalBootstrapRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final ArticleChunkMaterializationService materialization;
    private final ArticleRevisionBuildIdentity buildIdentity;
    private final long parserGeneration;

    public ArticleChunkLocalBootstrapRunner(
            JdbcTemplate jdbc,
            ArticleChunkMaterializationService materialization,
            ArticleRevisionBuildIdentity buildIdentity,
            @org.springframework.beans.factory.annotation.Value(
                    "${metro.projection.article-chunks.parser-generation:1}") long parserGeneration) {
        this.jdbc = jdbc;
        this.materialization = materialization;
        this.buildIdentity = buildIdentity;
        this.parserGeneration = parserGeneration;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        bootstrapParserGeneration();
        List<ArticlePointer> articles = jdbc.query("""
                SELECT id,lifecycle_epoch,lock_version FROM article ORDER BY id
                """, (rs, row) -> new ArticlePointer(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
        for (ArticlePointer article : articles) {
            materialization.materialize(article.id(), article.lifecycleEpoch(), article.aggregateVersion());
        }
    }

    private void bootstrapParserGeneration() {
        Integer checkpoints = jdbc.queryForObject(
                "SELECT COUNT(*) FROM article_chunk_parser_checkpoint", Integer.class);
        if (checkpoints != null && checkpoints > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO article_chunk_parser_generation
                  (generation,parser_version,token_estimator_version,dependency_fingerprint,
                   required_build_digest,state,operator_identity,created_at,updated_at,lock_version)
                VALUES (?,?,?,?,?,'ACTIVE','local-bootstrap',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
                """, parserGeneration, ArticleChunker.PARSER_VERSION,
                ArticleChunker.TOKEN_ESTIMATOR_VERSION, ArticleChunker.DEPENDENCY_FINGERPRINT,
                buildIdentity.buildDigest());
        jdbc.update("""
                INSERT INTO article_chunk_parser_checkpoint
                  (checkpoint_id,active_generation,lock_version,updated_by,updated_at)
                VALUES (1,?,0,'local-bootstrap',CURRENT_TIMESTAMP(6))
                """, parserGeneration);
    }

    private record ArticlePointer(long id, long lifecycleEpoch, long aggregateVersion) {
    }
}
