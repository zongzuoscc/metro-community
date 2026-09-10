package cumt.zongzuo.community.ai.agent.retrieval;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.EmbeddingCommand;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchRepository;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class HybridArticleRetrievalService {

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
        checkActive(query);
        Query standardQuery = new Query(query.query());
        List<Document> lexicalDocuments = List.of();
        List<Document> denseDocuments = List.of();
        boolean lexicalAvailable = true;
        boolean denseAvailable = true;
        try {
            DocumentRetriever lexicalRetriever =
                    new ArticleLexicalDocumentRetriever(lexical, candidateLimit);
            lexicalDocuments = lexicalRetriever.retrieve(standardQuery);
            checkActive(query);
        } catch (RuntimeException unavailable) {
            requireFallbackAllowed(query, unavailable);
            lexicalAvailable = false;
        }
        try {
            checkActive(query);
            Instant embeddingDeadline = min(query.deadline(), clock.instant().plus(embeddingTimeout));
            float[] vector = embed(query, query.query(), query.requestId() + ":embedding",
                    embeddingDeadline, "query embedding result is incompatible");
            long parserGeneration = resolver.activeParserGeneration();
            checkActive(query);
            DocumentRetriever denseRetriever = new ArticleVectorDocumentRetriever(vectors,
                    vectorAlias, embeddingModel, candidateLimit, vector, parserGeneration, "denseRank");
            denseDocuments = denseRetriever.retrieve(standardQuery);
            checkActive(query);
        } catch (RuntimeException unavailable) {
            requireFallbackAllowed(query, unavailable);
            denseAvailable = false;
        }

        checkActive(query);
        DocumentJoiner joiner = new ReciprocalRankFusionDocumentJoiner();
        DocumentPostProcessor postProcessor =
                new PublishedArticleDocumentPostProcessor(resolver, contextLimit);
        List<Document> firstJoined = joiner.join(Map.of(standardQuery,
                List.of(lexicalDocuments, denseDocuments)));
        List<Document> firstProcessed = postProcessor.process(standardQuery, firstJoined);
        checkActive(query);
        List<Document> hydeDocuments = List.of();
        if (shouldUseHyde(query.query(), firstProcessed.size())) {
            try {
                String hypotheticalDocument = hyde.generate(query.userId(), query.requestId(),
                        query.query(), query.deadline(), query.route(), () -> checkActive(query));
                checkActive(query);
                Instant embeddingDeadline = min(query.deadline(),
                        clock.instant().plus(embeddingTimeout));
                float[] vector = embed(query, hypotheticalDocument,
                        query.requestId() + ":hyde-embedding", embeddingDeadline,
                        "HyDE embedding result is incompatible");
                long parserGeneration = resolver.activeParserGeneration();
                checkActive(query);
                DocumentRetriever hydeRetriever = new ArticleVectorDocumentRetriever(vectors,
                        vectorAlias, embeddingModel, candidateLimit, vector, parserGeneration,
                        "hydeRank");
                hydeDocuments = hydeRetriever.retrieve(new Query(hypotheticalDocument));
                checkActive(query);
            } catch (RuntimeException unavailable) {
                requireFallbackAllowed(query, unavailable);
                // HyDE 是召回增强而不是回答的单点依赖：任何失败都继续使用已完成的 BM25 + Dense。
                hydeDocuments = List.of();
            }
        }

        List<Document> processed = hydeDocuments.isEmpty() ? firstProcessed
                : postProcessor.process(standardQuery, joiner.join(Map.of(standardQuery,
                        List.of(lexicalDocuments, denseDocuments, hydeDocuments))));
        List<RankedArticleChunk> ranked = processed.stream()
                .map(HybridArticleRetrievalService::toRankedChunk).toList();
        List<ResolvedArticleChunk> authorized = ranked.stream().map(RankedArticleChunk::chunk).toList();
        checkActive(query);
        return new ArticleRetrievalResult(lexicalDocuments.size(), denseDocuments.size(), lexicalAvailable,
                denseAvailable, authorized, ranked);
    }

    private float[] embed(ArticleRetrievalQuery query, String text, String requestId,
                          Instant deadline, String incompatibleMessage) {
        EmbeddingResult result = executor.execute(new AiInvocationContext(AiCapability.EMBEDDING,
                        query.userId(), requestId, text.length(), deadline, false),
                () -> {
                    checkActive(query);
                    var embedded = embedding.embed(new EmbeddingCommand(AiCapability.EMBEDDING,
                            List.of(text)));
                    checkActive(query);
                    return embedded;
                });
        checkActive(query);
        if (result.vectors().size() != 1 || !embeddingModel.equals(result.model())) {
            throw new IllegalStateException(incompatibleMessage);
        }
        return result.vectors().getFirst();
    }

    private void checkActive(ArticleRetrievalQuery query) {
        query.validate().run();
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Article retrieval was interrupted");
        }
        if (!query.deadline().isAfter(clock.instant())) {
            throw new IllegalStateException("article retrieval deadline has expired");
        }
    }

    /** 可用性降级不能吞掉撤销运行权；执行器可能把取消包装成运行时异常。 */
    private void requireFallbackAllowed(ArticleRetrievalQuery query, RuntimeException error) {
        checkActive(query);
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.CancellationException
                    || cause instanceof cumt.zongzuo.community.ai.runtime.AiExecutionException execution
                    && execution.reason() == cumt.zongzuo.community.ai.runtime.AiExecutionErrorReason.CANCELLED) {
                throw error;
            }
            if (cause.getCause() == cause) break;
        }
    }

    /**
     * 短问题往往与长文档的字面形式差异最大；即使问题较长，首轮回源后的
     * 真实有效候选过少也说明语义桥接不足。两者任一满足时只触发一次 HyDE。
     */
    private boolean shouldUseHyde(String query, int validCandidates) {
        return hyde != null && (query.codePointCount(0, query.length()) <= hydeShortQueryCharacters
                || validCandidates < hydeMinimumCandidates);
    }

    @SuppressWarnings("unchecked")
    private static RankedArticleChunk toRankedChunk(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        ResolvedArticleChunk chunk = new ResolvedArticleChunk(Long.parseLong(document.getId()),
                number(metadata, "articleId").longValue(), number(metadata, "revisionId").longValue(),
                number(metadata, "chunkNo").intValue(), (String) metadata.get("title"),
                (List<String>) metadata.get("headingPath"), document.getText(),
                (String) metadata.get("revisionContentHash"), (String) metadata.get("chunkHash"));
        return new RankedArticleChunk(chunk, number(metadata, "rrfScore").doubleValue(),
                optionalRank(metadata, "lexicalRank"), optionalRank(metadata, "denseRank"),
                optionalRank(metadata, "hydeRank"));
    }

    private static Number number(Map<String, Object> metadata, String key) {
        return (Number) metadata.get(key);
    }

    private static Integer optionalRank(Map<String, Object> metadata, String key) {
        Number value = (Number) metadata.get(key);
        return value == null ? null : value.intValue();
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

}
