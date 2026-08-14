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
    private final HydeHypotheticalDocumentService hyde;
    private final int hydeShortQueryCharacters;
    private final int hydeMinimumCandidates;

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
        this(lexical, vectors, resolver, executor, embedding, clock, vectorAlias,
                embeddingModel, candidateLimit, contextLimit, embeddingTimeout, null, 18, 3);
    }

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
                                         Duration embeddingTimeout,
                                         HydeHypotheticalDocumentService hyde,
                                         int hydeShortQueryCharacters,
                                         int hydeMinimumCandidates) {
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
        this.hyde = hyde;
        if (hydeShortQueryCharacters < 1 || hydeMinimumCandidates < 1
                || hydeMinimumCandidates > contextLimit) {
            throw new IllegalArgumentException("HyDE retrieval thresholds are invalid");
        }
        this.hydeShortQueryCharacters = hydeShortQueryCharacters;
        this.hydeMinimumCandidates = hydeMinimumCandidates;
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

        List<Fused> firstFused = fuse(lexicalHits, denseHits, List.of());
        List<RankedArticleChunk> firstRanked = resolveAndRank(firstFused);
        List<ArticleVectorHit> hydeHits = List.of();
        if (shouldUseHyde(query.query(), firstRanked.size())) {
            try {
                String hypotheticalDocument = hyde.generate(query.userId(), query.requestId(),
                        query.query(), query.deadline());
                Instant embeddingDeadline = min(query.deadline(),
                        clock.instant().plus(embeddingTimeout));
                EmbeddingResult result = executor.execute(new AiInvocationContext(
                                AiCapability.EMBEDDING, query.userId(),
                                query.requestId() + ":hyde-embedding",
                                hypotheticalDocument.length(), embeddingDeadline, false),
                        () -> embedding.embed(new EmbeddingCommand(AiCapability.EMBEDDING,
                                List.of(hypotheticalDocument))));
                if (result.vectors().size() != 1 || !embeddingModel.equals(result.model())) {
                    throw new IllegalStateException("HyDE embedding result is incompatible");
                }
                hydeHits = vectors.searchActive(vectorAlias, result.vectors().getFirst(),
                        candidateLimit, embeddingModel, resolver.activeParserGeneration());
            } catch (RuntimeException unavailable) {
                // HyDE 是召回增强而不是回答的单点依赖：任何失败都继续使用已完成的 BM25 + Dense。
                hydeHits = List.of();
            }
        }

        List<RankedArticleChunk> ranked = hydeHits.isEmpty() ? firstRanked
                : resolveAndRank(fuse(lexicalHits, denseHits, hydeHits));
        List<ResolvedArticleChunk> authorized = ranked.stream().map(RankedArticleChunk::chunk).toList();
        return new ArticleRetrievalResult(lexicalHits.size(), denseHits.size(), lexicalAvailable,
                denseAvailable, authorized, ranked);
    }

    /**
     * 短问题往往与长文档的字面形式差异最大；即使问题较长，首轮回源后的
     * 真实有效候选过少也说明语义桥接不足。两者任一满足时只触发一次 HyDE。
     */
    private boolean shouldUseHyde(String query, int validCandidates) {
        return hyde != null && (query.codePointCount(0, query.length()) <= hydeShortQueryCharacters
                || validCandidates < hydeMinimumCandidates);
    }

    private List<RankedArticleChunk> resolveAndRank(List<Fused> fused) {
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
                    candidate.lexicalRank(), candidate.denseRank(), candidate.hydeRank()));
            articleCounts.merge(current.articleId(), 1, Integer::sum);
            if (ranked.size() == contextLimit) {
                break;
            }
        }
        return List.copyOf(ranked);
    }

    private static List<Fused> fuse(List<ArticleChunkSearchHit> lexical,
                                    List<ArticleVectorHit> dense,
                                    List<ArticleVectorHit> hyde) {
        Map<Long, MutableFused> merged = new LinkedHashMap<>();
        for (int index = 0; index < lexical.size(); index++) {
            ArticleChunkSearchHit hit = lexical.get(index);
            merged.computeIfAbsent(hit.chunkId(), MutableFused::new).lexical(index + 1);
        }
        for (int index = 0; index < dense.size(); index++) {
            ArticleVectorHit hit = dense.get(index);
            merged.computeIfAbsent(hit.chunkId(), MutableFused::new).dense(index + 1);
        }
        for (int index = 0; index < hyde.size(); index++) {
            ArticleVectorHit hit = hyde.get(index);
            merged.computeIfAbsent(hit.chunkId(), MutableFused::new).hyde(index + 1);
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
        private Integer hydeRank;

        private MutableFused(long chunkId) {
            this.chunkId = chunkId;
        }

        private void lexical(int rank) {
            lexicalRank = rank;
        }

        private void dense(int rank) {
            denseRank = rank;
        }

        private void hyde(int rank) {
            hydeRank = rank;
        }

        private Fused freeze() {
            double score = (lexicalRank == null ? 0D : 1D / (RRF_CONSTANT + lexicalRank))
                    + (denseRank == null ? 0D : 1D / (RRF_CONSTANT + denseRank))
                    + (hydeRank == null ? 0D : 1D / (RRF_CONSTANT + hydeRank));
            return new Fused(chunkId, score, lexicalRank, denseRank, hydeRank);
        }
    }

    private record Fused(long chunkId, double score, Integer lexicalRank, Integer denseRank,
                         Integer hydeRank) {
    }
}
