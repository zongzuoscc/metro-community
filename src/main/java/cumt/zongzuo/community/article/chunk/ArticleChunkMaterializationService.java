package cumt.zongzuo.community.article.chunk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.article.model.ArticleRevision;
import cumt.zongzuo.community.article.persistence.ArticleRevisionMapper;
import cumt.zongzuo.community.article.rollout.ArticleRevisionBuildIdentity;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.outbox.DomainEventOutboxService;
import cumt.zongzuo.community.mapper.ArticleMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
public class ArticleChunkMaterializationService {

    private static final String AGGREGATE_TYPE = "ARTICLE_CHUNK_SET";

    private final JdbcTemplate jdbc;
    private final ArticleMapper articleMapper;
    private final ArticleRevisionMapper revisionMapper;
    private final DomainEventOutboxService outbox;
    private final ObjectMapper objectMapper;
    private final ArticleChunker chunker;
    private final ArticleRevisionBuildIdentity buildIdentity;
    private final long localParserGeneration;

    public ArticleChunkMaterializationService(JdbcTemplate jdbc,
                                              ArticleMapper articleMapper,
                                              ArticleRevisionMapper revisionMapper,
                                              DomainEventOutboxService outbox,
                                              ObjectMapper objectMapper,
                                              ArticleRevisionBuildIdentity buildIdentity,
                                              @Value("${metro.projection.article-chunks.parser-generation:1}")
                                              long localParserGeneration) {
        if (localParserGeneration <= 0) {
            throw new IllegalArgumentException("article chunk parser generation must be positive");
        }
        this.jdbc = jdbc;
        this.articleMapper = articleMapper;
        this.revisionMapper = revisionMapper;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.chunker = new ArticleChunker(512);
        this.buildIdentity = buildIdentity;
        this.localParserGeneration = localParserGeneration;
    }

    @Transactional
    public MaterializationResult materialize(long articleId,
                                             long expectedLifecycleEpoch,
                                             long expectedAggregateVersion) {
        if (articleId <= 0 || expectedLifecycleEpoch < 0 || expectedAggregateVersion < 0) {
            throw new IllegalArgumentException("materialization identity is out of range");
        }
        ParserCheckpoint parser = lockParserCheckpoint();
        Article article = articleMapper.selectByIdForUpdate(articleId);
        if (article == null
                || !Objects.equals(article.getLifecycleEpoch(), expectedLifecycleEpoch)
                || !Objects.equals(article.getLockVersion(), expectedAggregateVersion)) {
            return new MaterializationResult(false, true, 0L, 0, null);
        }

        Long revisionId = currentPublishedRevision(article);
        ArticleRevision revision = revisionId == null ? null : revisionMapper.selectByIdForUpdate(revisionId);
        if (revision != null && (!Objects.equals(revision.getArticleId(), articleId)
                || !revision.getContentHash().matches("[0-9a-f]{64}"))) {
            throw new IllegalStateException("published revision binding is invalid");
        }
        ChunkSet current = lockChunkSet(articleId);
        if (sameSource(current, revisionId, parser.generation(),
                expectedLifecycleEpoch, expectedAggregateVersion)) {
            return new MaterializationResult(false, false, current.version(), current.activeCount(), current.hash());
        }

        LocalDateTime publishedAt = revision == null ? null : publishedAt(article, revision);
        List<ArticleChunkDraft> drafts = revision == null ? List.of()
                : chunker.chunk(revision.getId(), parser.generation(), revision.getTitle(),
                revision.getBodyMarkdown());
        jdbc.update("""
                UPDATE article_chunk SET is_active=0,updated_at=CURRENT_TIMESTAMP(6)
                WHERE article_id=? AND is_active=1
                """, articleId);
        for (ArticleChunkDraft draft : drafts) {
            insertAndVerifyChunk(articleId, revision, draft, publishedAt);
        }

        long nextVersion = current == null ? 1L : Math.addExact(current.version(), 1L);
        String setHash = chunkSetHash(parser, revisionId, drafts);
        if (current == null) {
            jdbc.update("""
                    INSERT INTO article_chunk_set
                      (article_id,published_revision_id,parser_generation,parser_version,
                       chunk_set_version,source_lifecycle_epoch,source_aggregate_version,
                       chunk_set_hash,active_chunk_count,published_at,lock_version,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP(6))
                    """, articleId, revisionId, parser.generation(), parser.parserVersion(),
                    nextVersion, expectedLifecycleEpoch, expectedAggregateVersion, setHash,
                    drafts.size(), publishedAt);
        } else {
            int updated = jdbc.update("""
                    UPDATE article_chunk_set
                    SET published_revision_id=?,parser_generation=?,parser_version=?,
                        chunk_set_version=?,source_lifecycle_epoch=?,source_aggregate_version=?,
                        chunk_set_hash=?,active_chunk_count=?,published_at=?,
                        lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP(6)
                    WHERE article_id=? AND lock_version=?
                    """, revisionId, parser.generation(), parser.parserVersion(), nextVersion,
                    expectedLifecycleEpoch, expectedAggregateVersion, setHash, drafts.size(),
                    publishedAt, articleId, current.lockVersion());
            if (updated != 1) {
                throw new IllegalStateException("article chunk set changed concurrently");
            }
        }

        ObjectNode payload = objectMapper.createObjectNode()
                .put("articleId", articleId)
                .put("parserGeneration", parser.generation())
                .put("parserVersion", parser.parserVersion())
                .put("chunkSetHash", setHash)
                .put("activeChunkCount", drafts.size())
                .put("chunkSetVersion", nextVersion)
                .put("sourceLifecycleEpoch", expectedLifecycleEpoch)
                .put("sourceAggregateVersion", expectedAggregateVersion);
        if (revisionId == null) {
            payload.putNull("revisionId");
        } else {
            payload.put("revisionId", revisionId);
        }
        String dedupe = AGGREGATE_TYPE + ":" + articleId + ":" + expectedLifecycleEpoch + ":"
                + nextVersion + ":" + DomainEventType.ARTICLE_CHUNK_REINDEX_REQUESTED.name();
        outbox.append(AGGREGATE_TYPE, articleId, nextVersion, expectedLifecycleEpoch,
                DomainEventType.ARTICLE_CHUNK_REINDEX_REQUESTED, 1, payload, dedupe);
        return new MaterializationResult(true, false, nextVersion, drafts.size(), setHash);
    }

    private ParserCheckpoint lockParserCheckpoint() {
        List<ParserCheckpoint> rows = jdbc.query("""
                SELECT g.generation,g.parser_version,g.token_estimator_version,
                       g.dependency_fingerprint,g.required_build_digest
                FROM article_chunk_parser_checkpoint c
                JOIN article_chunk_parser_generation g ON g.generation=c.active_generation
                WHERE c.checkpoint_id=1 AND g.state='ACTIVE'
                FOR SHARE
                """, (rs, rowNum) -> new ParserCheckpoint(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5)));
        if (rows.size() != 1) {
            throw new IllegalStateException("active article chunk parser checkpoint is unavailable");
        }
        ParserCheckpoint checkpoint = rows.getFirst();
        if (checkpoint.generation() != localParserGeneration
                || !ArticleChunker.PARSER_VERSION.equals(checkpoint.parserVersion())
                || !ArticleChunker.TOKEN_ESTIMATOR_VERSION.equals(checkpoint.estimatorVersion())
                || !ArticleChunker.DEPENDENCY_FINGERPRINT.equals(checkpoint.dependencyFingerprint())
                || !buildIdentity.buildDigest().equals(checkpoint.requiredBuildDigest())) {
            throw new IllegalStateException("local article chunk parser does not match the active generation");
        }
        return checkpoint;
    }

    private ChunkSet lockChunkSet(long articleId) {
        List<ChunkSet> rows = jdbc.query("""
                SELECT published_revision_id,parser_generation,chunk_set_version,
                       source_lifecycle_epoch,source_aggregate_version,active_chunk_count,
                       chunk_set_hash,lock_version
                FROM article_chunk_set WHERE article_id=? FOR UPDATE
                """, (rs, rowNum) -> new ChunkSet((Long) rs.getObject(1), rs.getLong(2),
                rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getInt(6), rs.getString(7),
                rs.getLong(8)), articleId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insertAndVerifyChunk(long articleId,
                                      ArticleRevision revision,
                                      ArticleChunkDraft draft,
                                      LocalDateTime publishedAt) {
        String headings;
        try {
            headings = objectMapper.writeValueAsString(draft.headingPath());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("heading path cannot be serialized", exception);
        }
        jdbc.update("""
                INSERT IGNORE INTO article_chunk
                  (id,article_id,revision_id,chunk_no,parser_generation,parser_version,title,
                   heading_path_json,body_text,start_codepoint,end_codepoint,estimated_tokens,
                   revision_content_hash,chunk_hash,embedding_input_hash,language,is_active,
                   published_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, draft.id(), articleId, revision.getId(), draft.chunkNo(),
                draft.parserGeneration(), draft.parserVersion(), draft.title(), headings,
                draft.bodyText(), draft.startCodepoint(), draft.endCodepoint(), draft.estimatedTokens(),
                revision.getContentHash(), draft.chunkHash(), draft.embeddingInputHash(), "zh-CN",
                publishedAt);
        List<String> identity = jdbc.query("""
                SELECT CONCAT(revision_id,':',parser_generation,':',parser_version,':',chunk_no,':',chunk_hash)
                FROM article_chunk WHERE id=? FOR UPDATE
                """, (rs, rowNum) -> rs.getString(1), draft.id());
        String expected = revision.getId() + ":" + draft.parserGeneration() + ":"
                + draft.parserVersion() + ":" + draft.chunkNo() + ":" + draft.chunkHash();
        if (identity.size() != 1 || !expected.equals(identity.getFirst())) {
            throw new IllegalStateException("deterministic article chunk ID collision");
        }
        jdbc.update("""
                UPDATE article_chunk SET is_active=1,updated_at=CURRENT_TIMESTAMP(6)
                WHERE id=? AND is_active=0
                """, draft.id());
    }

    private static Long currentPublishedRevision(Article article) {
        return Integer.valueOf(0).equals(article.getIsDeleted())
                && Integer.valueOf(1).equals(article.getStatus())
                && "PUBLIC".equals(article.getVisibilityState())
                ? article.getPublishedRevisionId() : null;
    }

    private LocalDateTime publishedAt(Article article, ArticleRevision revision) {
        List<ApprovalFact> jobs = jdbc.query("""
                SELECT state,reviewed_at
                FROM article_moderation_job
                WHERE article_id=? AND revision_id=? AND content_hash=?
                """, (rs, rowNum) -> new ApprovalFact(rs.getString(1),
                rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toLocalDateTime()),
                article.getId(), revision.getId(), revision.getContentHash());
        if (jobs.size() > 1) {
            throw new IllegalStateException("published revision has multiple moderation jobs");
        }
        if (jobs.size() == 1) {
            ApprovalFact job = jobs.getFirst();
            if (!"HUMAN_APPROVED".equals(job.state()) || job.reviewedAt() == null) {
                throw new IllegalStateException("published revision moderation fact is not approved");
            }
            return job.reviewedAt();
        }
        if (article.getCreateTime() == null) {
            throw new IllegalStateException("published article is missing a stable publication time");
        }
        return article.getCreateTime();
    }

    private static boolean sameSource(ChunkSet current, Long revisionId, long generation,
                                      long lifecycleEpoch, long aggregateVersion) {
        return current != null
                && Objects.equals(current.revisionId(), revisionId)
                && current.parserGeneration() == generation
                && current.lifecycleEpoch() == lifecycleEpoch
                && current.aggregateVersion() == aggregateVersion;
    }

    private static String chunkSetHash(ParserCheckpoint parser,
                                       Long revisionId,
                                       List<ArticleChunkDraft> drafts) {
        StringBuilder value = new StringBuilder("article-chunk-set-v1\n")
                .append(parser.generation()).append('\n')
                .append(parser.parserVersion()).append('\n')
                .append(revisionId == null ? "null" : revisionId).append('\n');
        drafts.forEach(draft -> value.append(draft.id()).append(':')
                .append(draft.chunkHash()).append(':')
                .append(draft.embeddingInputHash()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record MaterializationResult(boolean applied, boolean stale,
                                        long chunkSetVersion, int activeChunkCount,
                                        String resultHash) {
    }

    private record ParserCheckpoint(long generation, String parserVersion, String estimatorVersion,
                                    String dependencyFingerprint, String requiredBuildDigest) {
    }

    private record ApprovalFact(String state, LocalDateTime reviewedAt) {
    }

    private record ChunkSet(Long revisionId, long parserGeneration, long version,
                            long lifecycleEpoch, long aggregateVersion,
                            int activeCount, String hash, long lockVersion) {
    }
}
