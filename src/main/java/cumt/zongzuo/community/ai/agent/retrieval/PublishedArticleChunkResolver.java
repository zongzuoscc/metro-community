package cumt.zongzuo.community.ai.agent.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class PublishedArticleChunkResolver {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PublishedArticleChunkResolver(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ResolvedArticleChunk> resolveCurrent(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = chunkIds.stream().distinct().limit(120).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(distinct.size(), "?"));
        return jdbc.query("""
                SELECT c.id,c.article_id,c.revision_id,c.chunk_no,c.title,c.heading_path_json,
                       c.body_text,c.revision_content_hash,c.chunk_hash
                FROM article_chunk c
                JOIN article a ON a.id=c.article_id
                JOIN article_chunk_set s ON s.article_id=c.article_id
                WHERE c.id IN (%s)
                  AND c.is_active=1
                  AND a.is_deleted=0 AND a.visibility_state='PUBLIC'
                  AND a.published_revision_id=c.revision_id
                  AND s.published_revision_id=c.revision_id
                  AND s.parser_generation=c.parser_generation
                """.formatted(placeholders), (rs, rowNum) -> new ResolvedArticleChunk(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getString(5),
                headings(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getString(9)),
                distinct.toArray());
    }

    @Transactional(readOnly = true)
    public long activeParserGeneration() {
        Long generation = jdbc.queryForObject("""
                SELECT active_generation FROM article_chunk_parser_checkpoint WHERE checkpoint_id=1
                """, Long.class);
        if (generation == null || generation <= 0) {
            throw new IllegalStateException("active article chunk parser generation is unavailable");
        }
        return generation;
    }

    private List<String> headings(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception exception) {
            throw new IllegalStateException("article chunk heading path is invalid", exception);
        }
    }
}
