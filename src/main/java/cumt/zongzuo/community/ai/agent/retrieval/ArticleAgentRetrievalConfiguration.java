package cumt.zongzuo.community.ai.agent.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.GroundedAnswerParser;
import cumt.zongzuo.community.ai.agent.GroundedAnswerService;
import cumt.zongzuo.community.ai.agent.history.AgentConversationHistorySearchService;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSearchGateway;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryRecallService;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.userprovider.UserAiChatRouter;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchRepository;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorDocument;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorHit;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.agent.enabled"}, havingValue = "true")
class ArticleAgentRetrievalConfiguration {

    @Bean
    HybridArticleRetrievalService hybridArticleRetrievalService(
            ObjectProvider<ArticleChunkSearchRepository> lexicalProvider,
            ObjectProvider<ArticleVectorRepository> vectorProvider,
            PublishedArticleChunkResolver resolver,
            AiCapabilityExecutor executor,
            EmbeddingGateway embedding,
            UserAiChatRouter router,
            MetroAiProperties properties,
            Clock clock,
            @Value("${metro.ai.agent-retrieval.vector-alias:metro_article_chunks_read}")
            String vectorAlias,
            @Value("${metro.ai.agent-retrieval.candidate-limit:40}") int candidateLimit,
            @Value("${metro.ai.agent-retrieval.context-limit:8}") int contextLimit,
            @Value("${metro.ai.agent-retrieval.hyde-short-query-characters:18}")
            int hydeShortQueryCharacters,
            @Value("${metro.ai.agent-retrieval.hyde-minimum-candidates:3}")
            int hydeMinimumCandidates) {
        if (candidateLimit < 1 || candidateLimit > 100 || contextLimit < 1 || contextLimit > 16) {
            throw new IllegalStateException("Agent retrieval limits are invalid");
        }
        ArticleChunkSearchRepository lexical = lexicalProvider.getIfAvailable(
                () -> (query, topK) -> {
                    throw new IllegalStateException("article chunk Elasticsearch is unavailable");
                });
        ArticleVectorRepository vectors = vectorProvider.getIfAvailable(UnavailableVectors::new);
        HydeHypotheticalDocumentService hyde = new HydeHypotheticalDocumentService(
                executor, router, clock, properties.getHyde().getTimeout(),
                properties.getHyde().getMaxOutputCharacters());
        return new HybridArticleRetrievalService(lexical, vectors, resolver, executor, embedding,
                clock, vectorAlias, properties.getEmbedding().getModel(), candidateLimit, contextLimit,
                min(properties.getAgent().getTimeout(), properties.getEmbedding().getTimeout()),
                hyde, hydeShortQueryCharacters, hydeMinimumCandidates);
    }

    @Bean
    GroundedAnswerService groundedAnswerService(HybridArticleRetrievalService retrieval,
                                                AiCapabilityExecutor executor,
                                                UserAiChatRouter router,
                                                ObjectMapper objectMapper,
                                                MetroAiProperties properties,
                                                Clock clock,
                                                ObjectProvider<AgentMemoryRecallService> memories,
                                                ObjectProvider<AgentConversationHistorySearchService> history,
                                                ObjectProvider<AgentWebSearchGateway> webSearch) {
        String model = properties.getPlatform().getModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Agent model must not be blank");
        }
        return new GroundedAnswerService(retrieval, executor, router,
                new GroundedAnswerParser(objectMapper), clock, model,
                properties.getAgent().getTimeout(), memories.getIfAvailable(),
                history.getIfAvailable(), properties.getMemory().isEnabled(),
                webSearch.getIfAvailable());
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static final class UnavailableVectors implements ArticleVectorRepository {
        @Override
        public List<Long> listChunkIdsByArticle(String physicalCollection, long articleId) {
            throw unavailable();
        }

        @Override
        public long upsert(String physicalCollection, List<ArticleVectorDocument> documents) {
            throw unavailable();
        }

        @Override
        public List<ArticleVectorHit> searchActive(String readAlias, float[] embedding, int topK,
                                                   String embeddingModel, long parserGeneration) {
            throw unavailable();
        }

        @Override
        public long deleteByChunkIds(String physicalCollection, List<Long> chunkIds) {
            throw unavailable();
        }

        @Override
        public void assertDeletedStrong(String physicalCollection, List<Long> chunkIds) {
            throw unavailable();
        }

        private static IllegalStateException unavailable() {
            return new IllegalStateException("article chunk Milvus is unavailable");
        }
    }
}
