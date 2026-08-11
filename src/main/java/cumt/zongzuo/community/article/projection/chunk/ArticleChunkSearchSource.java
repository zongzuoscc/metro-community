package cumt.zongzuo.community.article.projection.chunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "metro.projection.article-chunk-elasticsearch",
        name = "enabled", havingValue = "true")
class ArticleChunkSearchSource {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    ArticleChunkSearchSource(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Snapshot load(long articleId) {
        List<SnapshotHeader> headers = jdbc.query("""
                SELECT published_revision_id,parser_generation,parser_version,chunk_set_version,
                       source_lifecycle_epoch,source_aggregate_version,chunk_set_hash,
                       active_chunk_count,published_at
                FROM article_chunk_set WHERE article_id=? FOR SHARE
                """, (rs, rowNum) -> new SnapshotHeader((Long) rs.getObject(1), rs.getLong(2),
                rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getString(7),
                rs.getInt(8), rs.getTimestamp(9) == null ? null
                : rs.getTimestamp(9).toLocalDateTime()), articleId);
        if (headers.size() != 1) {
            throw new IllegalStateException("article chunk set is unavailable");
        }
        SnapshotHeader header = headers.getFirst();
        List<Chunk> chunks = jdbc.query("""
                SELECT id,revision_id,chunk_no,parser_generation,parser_version,title,
                       heading_path_json,body_text,estimated_tokens,revision_content_hash,
                       chunk_hash,embedding_input_hash,language,published_at
                FROM article_chunk
                WHERE article_id=? AND is_active=1 AND parser_generation=?
                ORDER BY chunk_no,id
                """, (rs, rowNum) -> new Chunk(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                rs.getLong(4), rs.getString(5), rs.getString(6), json(rs.getString(7)),
                rs.getString(8), rs.getInt(9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getString(13), rs.getTimestamp(14).toLocalDateTime()),
                articleId, header.parserGeneration());
        if (chunks.size() != header.activeCount()
                || (header.publishedRevisionId() == null && !chunks.isEmpty())
                || chunks.stream().anyMatch(chunk -> header.publishedRevisionId() == null
                || chunk.revisionId() != header.publishedRevisionId())) {
            throw new IllegalStateException("article chunk set does not match its active facts");
        }
        return new Snapshot(articleId, header.publishedRevisionId(), header.parserGeneration(),
                header.parserVersion(), header.chunkSetVersion(), header.lifecycleEpoch(),
                header.sourceAggregateVersion(), header.chunkSetHash(), header.publishedAt(), chunks);
    }

    @Transactional(readOnly = true)
    public boolean isCurrent(Snapshot snapshot) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM article_chunk_set
                WHERE article_id=? AND parser_generation=? AND chunk_set_version=?
                  AND source_lifecycle_epoch=? AND source_aggregate_version=?
                  AND chunk_set_hash=? AND active_chunk_count=?
                """, Integer.class, snapshot.articleId(), snapshot.parserGeneration(),
                snapshot.chunkSetVersion(), snapshot.lifecycleEpoch(),
                snapshot.sourceAggregateVersion(), snapshot.chunkSetHash(), snapshot.chunks().size());
        return count != null && count == 1;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("chunk heading path is invalid", exception);
        }
    }

    record Snapshot(long articleId, Long publishedRevisionId, long parserGeneration,
                    String parserVersion, long chunkSetVersion, long lifecycleEpoch,
                    long sourceAggregateVersion, String chunkSetHash, LocalDateTime publishedAt,
                    List<Chunk> chunks) {
        boolean tombstone() {
            return publishedRevisionId == null && chunks.isEmpty();
        }
    }

    record Chunk(long id, long revisionId, int chunkNo, long parserGeneration,
                 String parserVersion, String title, JsonNode headingPath, String bodyText,
                 int estimatedTokens, String revisionContentHash, String chunkHash,
                 String embeddingInputHash, String language, LocalDateTime publishedAt) {
    }

    private record SnapshotHeader(Long publishedRevisionId, long parserGeneration,
                                  String parserVersion, long chunkSetVersion,
                                  long lifecycleEpoch, long sourceAggregateVersion,
                                  String chunkSetHash, int activeCount,
                                  LocalDateTime publishedAt) {
    }
}
