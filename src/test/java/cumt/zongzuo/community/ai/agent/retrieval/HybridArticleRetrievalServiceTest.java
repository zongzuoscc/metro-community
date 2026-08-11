package cumt.zongzuo.community.ai.agent.retrieval;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchHit;
import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchRepository;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorHit;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorRepository;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridArticleRetrievalServiceTest {

    private final ArticleChunkSearchRepository lexical = mock(ArticleChunkSearchRepository.class);
    private final ArticleVectorRepository vectors = mock(ArticleVectorRepository.class);
    private final PublishedArticleChunkResolver resolver = mock(PublishedArticleChunkResolver.class);
    private final EmbeddingGateway embedding = command -> new EmbeddingResult(
            List.of(new float[]{1F, 0F}), "test", "bge-m3");

    HybridArticleRetrievalServiceTest() {
        when(resolver.activeParserGeneration()).thenReturn(3L);
    }

    @Test
    void fusesBothRankingsThenDropsStaleCandidatesThroughMysql() {
        when(lexical.searchActive("java concurrency", 40)).thenReturn(List.of(
                new ArticleChunkSearchHit(11L, 101L, 1001L, 9F),
                new ArticleChunkSearchHit(12L, 102L, 1002L, 8F)));
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenReturn(List.of(
                new ArticleVectorHit(12L, 102L, 1002L, .99F),
                new ArticleVectorHit(13L, 103L, 1003L, .98F)));
        ResolvedArticleChunk current = new ResolvedArticleChunk(12L, 102L, 1002L, 0,
                "Locks", List.of("Concurrency"), "Use a bounded critical section.",
                "a".repeat(64), "b".repeat(64));
        when(resolver.resolveCurrent(List.of(11L, 12L, 13L))).thenReturn(List.of(current));

        HybridArticleRetrievalService service = service();

        ArticleRetrievalResult result = service.retrieve(
                new ArticleRetrievalQuery(7L, "req-1", "java concurrency",
                        Instant.parse("2026-08-12T00:00:30Z")));

        assertThat(result.lexicalCount()).isEqualTo(2);
        assertThat(result.denseCount()).isEqualTo(2);
        assertThat(result.authorizedChunks()).containsExactly(current);
        assertThat(result.rankedCandidates()).extracting(RankedArticleChunk::chunkId)
                .containsExactly(12L);
    }

    @Test
    void denseFailureFallsBackToAuthorizedBm25Candidates() {
        when(lexical.searchActive("mysql lock", 40)).thenReturn(List.of(
                new ArticleChunkSearchHit(21L, 201L, 2001L, 9F)));
        when(vectors.searchActive("metro_article_chunks_read", new float[]{1F, 0F},
                40, "bge-m3", 3L)).thenThrow(new IllegalStateException("milvus down"));
        ResolvedArticleChunk current = new ResolvedArticleChunk(21L, 201L, 2001L, 0,
                "MySQL", List.of(), "Use SELECT FOR UPDATE.",
                "c".repeat(64), "d".repeat(64));
        when(resolver.resolveCurrent(List.of(21L))).thenReturn(List.of(current));

        ArticleRetrievalResult result = service().retrieve(
                new ArticleRetrievalQuery(8L, "req-2", "mysql lock",
                        Instant.parse("2026-08-12T00:00:30Z")));

        assertThat(result.denseAvailable()).isFalse();
        assertThat(result.authorizedChunks()).containsExactly(current);
    }

    private HybridArticleRetrievalService service() {
        return new HybridArticleRetrievalService(lexical, vectors, resolver,
                new DirectExecutor(), embedding,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
                "metro_article_chunks_read", "bge-m3", 40, 8,
                Duration.ofSeconds(20));
    }

    private static final class DirectExecutor implements AiCapabilityExecutor {
        @Override
        public <T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                             CheckedSupplier<T> operation) {
            assertThat(context.capability()).isEqualTo(AiCapability.EMBEDDING);
            assertThat(context.background()).isFalse();
            try {
                return operation.get();
            } catch (Throwable error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public <A, T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                                AttemptObserver<A, T> observer, AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }
}
