package cumt.zongzuo.community.ai.agent.retrieval;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.EmbeddingCommand;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchHit;
import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchRepository;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorHit;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HybridArticleRetrievalService {

    private static final int RRF_CONSTANT = 60;

    private final ArticleChunkSearchRepository lexical;
    private final ArticleVectorRepository vectors;
    private final PublishedArticleChunkResolver resolver;
    private final AiCapabilityExecutor executor;
    private final EmbeddingGateway embedding;
    private final Clock clock;
    private final String vectorAlias;
    private final String embeddingModel;
    private final int candidateLimit;
    private final int contextLimit;
    private final Duration embeddingTimeout;

    public HybridArticleRetrievalService(ArticleChunkSearchRepository lexical,
                                         ArticleVectorRepository vectors,
                                         PublishedArticleChunkResolver resolver,
                                         AiCapabilityExecutor executor,
                                         EmbeddingGateway embedding,
                                         Clock clock,
                                         String vectorAlias,
                                         String embeddingModel,
                                         int candidateLimit,
                                         int contextLimit,
                                         Duration embeddingTimeout) {
        this.lexical = lexical;
        this.vectors = vectors;
        this.resolver = resolver;
        this.executor = executor;
        this.embedding = embedding;
        this.clock = clock;
        this.vectorAlias = vectorAlias;
        this.embeddingModel = embeddingModel;
        this.candidateLimit = candidateLimit;
        this.contextLimit = contextLimit;
        this.embeddingTimeout = embeddingTimeout;
    }

    public ArticleRetrievalResult retrieve(ArticleRetrievalQuery query) {
        if (!query.deadline().isAfter(clock.instant())) {
            throw new IllegalStateException("article retrieval deadline has expired");
        }
        List<ArticleChunkSearchHit> lexicalHits = List.of();
        List<ArticleVectorHit> denseHits = List.of();
        boolean lexicalAvailable = true;
        boolean denseAvailable = true;
        try {
            lexicalHits = lexical.searchActive(query.query(), candidateLimit);
        } catch (RuntimeException unavailable) {
            lexicalAvailable = false;
        }
        try {
            Instant embeddingDeadline = min(query.deadline(), clock.instant().plus(embeddingTimeout));
            EmbeddingResult result = executor.execute(new AiInvocationContext(AiCapability.EMBEDDING,
                            query.userId(), query.requestId() + ":embedding", query.query().length(),
                            embeddingDeadline, false),
                    () -> embedding.embed(new EmbeddingCommand(AiCapability.EMBEDDING,
                            List.of(query.query()))));
            if (result.vectors().size() != 1 || !embeddingModel.equals(result.model())) {
                throw new IllegalStateException("query embedding result is incompatible");
            }
            long parserGeneration = resolver.activeParserGeneration();
            denseHits = vectors.searchActive(vectorAlias, result.vectors().getFirst(), candidateLimit,
                    embeddingModel, parserGeneration);
        } catch (RuntimeException unavailable) {
            denseAvailable = false;
        }

        List<Fused> fused = fuse(lexicalHits, denseHits);
        List<Long> ids = fused.stream().map(Fused::chunkId).sorted().toList();
        Map<Long, ResolvedArticleChunk> currentById = new HashMap<>();
        for (ResolvedArticleChunk chunk : resolver.resolveCurrent(ids)) {
            currentById.put(chunk.chunkId(), chunk);
        }
        Map<Long, Integer> articleCounts = new HashMap<>();
        List<RankedArticleChunk> ranked = new ArrayList<>();
        for (Fused candidate : fused) {
            ResolvedArticleChunk current = currentById.get(candidate.chunkId());
            if (current == null || articleCounts.getOrDefault(current.articleId(), 0) >= 2) {
                continue;
            }
            ranked.add(new RankedArticleChunk(current, candidate.score(),
                    candidate.lexicalRank(), candidate.denseRank()));
            articleCounts.merge(current.articleId(), 1, Integer::sum);
            if (ranked.size() == contextLimit) {
                break;
            }
        }
        List<ResolvedArticleChunk> authorized = ranked.stream().map(RankedArticleChunk::chunk).toList();
        return new ArticleRetrievalResult(lexicalHits.size(), denseHits.size(), lexicalAvailable,
                denseAvailable, authorized, ranked);
    }

    private static List<Fused> fuse(List<ArticleChunkSearchHit> lexical,
                                    List<ArticleVectorHit> dense) {
        Map<Long, MutableFused> merged = new LinkedHashMap<>();
        for (int index = 0; index < lexical.size(); index++) {
            ArticleChunkSearchHit hit = lexical.get(index);
            merged.computeIfAbsent(hit.chunkId(), MutableFused::new).lexical(index + 1);
        }
        for (int index = 0; index < dense.size(); index++) {
            ArticleVectorHit hit = dense.get(index);
            merged.computeIfAbsent(hit.chunkId(), MutableFused::new).dense(index + 1);
        }
        return merged.values().stream().map(MutableFused::freeze)
                .sorted(Comparator.comparingDouble(Fused::score).reversed()
                        .thenComparingLong(Fused::chunkId))
                .toList();
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static final class MutableFused {
        private final long chunkId;
        private Integer lexicalRank;
        private Integer denseRank;

        private MutableFused(long chunkId) {
            this.chunkId = chunkId;
        }

        private void lexical(int rank) {
            lexicalRank = rank;
        }

        private void dense(int rank) {
            denseRank = rank;
        }

        private Fused freeze() {
            double score = (lexicalRank == null ? 0D : 1D / (RRF_CONSTANT + lexicalRank))
                    + (denseRank == null ? 0D : 1D / (RRF_CONSTANT + denseRank));
            return new Fused(chunkId, score, lexicalRank, denseRank);
        }
    }

    private record Fused(long chunkId, double score, Integer lexicalRank, Integer denseRank) {
    }
}
