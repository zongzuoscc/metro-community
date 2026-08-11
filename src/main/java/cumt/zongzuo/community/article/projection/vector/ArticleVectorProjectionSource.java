package cumt.zongzuo.community.article.projection.vector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Component
public class ArticleVectorProjectionSource {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ArticleVectorProjectionSource(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Snapshot load(long articleId) {
        List<Header> headers = jdbc.query("""
                SELECT a.author_id,s.published_revision_id,s.parser_generation,s.parser_version,
                       s.chunk_set_version,s.source_lifecycle_epoch,s.source_aggregate_version,
                       s.chunk_set_hash,s.active_chunk_count,s.published_at
                FROM article_chunk_set s JOIN article a ON a.id=s.article_id
                WHERE s.article_id=? FOR SHARE
                """, (rs, rowNum) -> new Header(rs.getLong(1), (Long) rs.getObject(2), rs.getLong(3),
                rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                rs.getInt(9), rs.getTimestamp(10) == null ? null
                : rs.getTimestamp(10).toLocalDateTime()), articleId);
        if (headers.size() != 1) {
            throw new IllegalStateException("article vector source is unavailable");
        }
        Header header = headers.getFirst();
        List<Chunk> chunks = jdbc.query("""
                SELECT id,revision_id,chunk_no,title,heading_path_json,body_text,
                       revision_content_hash,embedding_input_hash,language
                FROM article_chunk
                WHERE article_id=? AND is_active=1 AND parser_generation=?
                ORDER BY chunk_no,id
                """, (rs, rowNum) -> {
            String input = embeddingInput(rs.getString(4), headings(rs.getString(5)), rs.getString(6));
            if (!sha256(input).equals(rs.getString(8))) {
                throw new IllegalStateException("article chunk embedding input hash drift");
            }
            return new Chunk(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), input,
                    rs.getString(7), rs.getString(8), rs.getString(9));
        }, articleId, header.parserGeneration());
        if (chunks.size() != header.activeCount()
                || (header.publishedRevisionId() == null && !chunks.isEmpty())
                || chunks.stream().anyMatch(chunk -> header.publishedRevisionId() == null
                || chunk.revisionId() != header.publishedRevisionId())) {
            throw new IllegalStateException("article vector source does not match active chunk facts");
        }
        return new Snapshot(articleId, header.authorId(), header.publishedRevisionId(),
                header.parserGeneration(), header.parserVersion(), header.chunkSetVersion(),
                header.lifecycleEpoch(), header.sourceAggregateVersion(), header.chunkSetHash(),
                header.publishedAt(), chunks);
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

    private List<String> headings(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception exception) {
            throw new IllegalStateException("article chunk heading path is invalid", exception);
        }
    }

    private static String embeddingInput(String title, List<String> headings, String body) {
        return String.join("\n", title, String.join(" > ", headings), body);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Snapshot(long articleId, long authorId, Long publishedRevisionId,
                           long parserGeneration, String parserVersion, long chunkSetVersion,
                           long lifecycleEpoch, long sourceAggregateVersion, String chunkSetHash,
                           LocalDateTime publishedAt, List<Chunk> chunks) {
        public boolean tombstone() {
            return publishedRevisionId == null && chunks.isEmpty();
        }
    }

    public record Chunk(long id, long revisionId, int chunkNo, String title,
                        String embeddingInput, String revisionContentHash,
                        String embeddingInputHash, String language) {
    }

    private record Header(long authorId, Long publishedRevisionId, long parserGeneration,
                          String parserVersion, long chunkSetVersion, long lifecycleEpoch,
                          long sourceAggregateVersion, String chunkSetHash, int activeCount,
                          LocalDateTime publishedAt) {
    }
}
