package cumt.zongzuo.community.article.projection.vector;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.EmbeddingResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import cumt.zongzuo.community.event.projection.ProjectionLeaseService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleMilvusProjectionServiceTest {

    private final ProjectionLeaseService leases = mock(ProjectionLeaseService.class);
    private final ArticleVectorProjectionSource source = mock(ArticleVectorProjectionSource.class);
    private final ArticleVectorRepository repository = mock(ArticleVectorRepository.class);
    private final AtomicInteger embeddingCalls = new AtomicInteger();
    private final AiCapabilityExecutor executor = new DirectExecutor();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void replacementEmbedsThroughTheExecutorAndStronglyDeletesHistoricalChunkIds() {
        DomainEvent event = event(2L, 1L);
        acquired(event);
        ArticleVectorProjectionSource.Snapshot snapshot = snapshot(List.of(
                chunk(101L, 0, "标题\n总览\n第一块"),
                chunk(102L, 1, "标题\n细节\n第二块")));
        when(source.load(ARTICLE_ID)).thenReturn(snapshot);
        when(source.isCurrent(snapshot)).thenReturn(true);
        when(repository.listChunkIdsByArticle(MilvusCollectionSchemas.ARTICLE_COLLECTION, ARTICLE_ID))
                .thenReturn(List.of(99L, 101L));
        when(repository.upsert(any(), any())).thenReturn(2L);

        ArticleMilvusProjectionService service = service();

        assertThat(service.apply(event)).isEqualTo(ProjectionLease.Decision.ACQUIRED);
        assertThat(embeddingCalls).hasValue(1);
        verify(repository).upsert(any(), org.mockito.ArgumentMatchers.argThat(documents ->
                documents.size() == 2 && documents.stream().allMatch(ArticleVectorDocument::active)));
        verify(repository).deleteByChunkIds(MilvusCollectionSchemas.ARTICLE_COLLECTION, List.of(99L));
        verify(repository).assertDeletedStrong(MilvusCollectionSchemas.ARTICLE_COLLECTION, List.of(99L));
        verify(leases).complete(any(), org.mockito.ArgumentMatchers.eq(event),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("f".repeat(64)));
    }

    @Test
    void tombstoneDeletesAllHistoricalVectorsWithoutCallingEmbedding() {
        DomainEvent event = event(3L, 2L);
        acquired(event);
        ArticleVectorProjectionSource.Snapshot tombstone = new ArticleVectorProjectionSource.Snapshot(
                ARTICLE_ID, 42L, null, 1L, "markdown-v1", 3L, 2L, 9L,
                "e".repeat(64), null, List.of());
        when(source.load(ARTICLE_ID)).thenReturn(tombstone);
        when(source.isCurrent(tombstone)).thenReturn(true);
        when(repository.listChunkIdsByArticle(MilvusCollectionSchemas.ARTICLE_COLLECTION, ARTICLE_ID))
                .thenReturn(List.of(101L, 102L));

        assertThat(service().apply(event)).isEqualTo(ProjectionLease.Decision.ACQUIRED);

        assertThat(embeddingCalls).hasValue(0);
        verify(repository, never()).upsert(any(), any());
        verify(repository).deleteByChunkIds(MilvusCollectionSchemas.ARTICLE_COLLECTION,
                List.of(101L, 102L));
        verify(repository).assertDeletedStrong(MilvusCollectionSchemas.ARTICLE_COLLECTION,
                List.of(101L, 102L));
        verify(leases).complete(any(), org.mockito.ArgumentMatchers.eq(event),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("e".repeat(64)));
    }

    private ArticleMilvusProjectionService service() {
        return new ArticleMilvusProjectionService(leases, source, repository, executor,
                command -> {
                    embeddingCalls.incrementAndGet();
                    return new EmbeddingResult(command.inputs().stream()
                            .map(ignored -> unitVector()).toList(), "test", "bge-m3");
                }, clock, MilvusCollectionSchemas.ARTICLE_COLLECTION, "bge-m3",
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(45));
    }

    private void acquired(DomainEvent event) {
        when(leases.acquire(org.mockito.ArgumentMatchers.eq(ArticleMilvusProjectionService.CONSUMER),
                org.mockito.ArgumentMatchers.eq(event), any())).thenReturn(new ProjectionLease(
                ArticleMilvusProjectionService.CONSUMER, event.aggregateType(), event.aggregateId(),
                event.eventId(), event.aggregateVersion(), event.lifecycleEpoch(), "owner",
                ProjectionLease.Decision.ACQUIRED));
    }

    private static ArticleVectorProjectionSource.Snapshot snapshot(
            List<ArticleVectorProjectionSource.Chunk> chunks) {
        return new ArticleVectorProjectionSource.Snapshot(ARTICLE_ID, 42L, 77L, 1L,
                "markdown-v1", 2L, 1L, 8L, "f".repeat(64),
                LocalDateTime.of(2026, 8, 1, 12, 0), chunks);
    }

    private static ArticleVectorProjectionSource.Chunk chunk(long id, int number, String input) {
        return new ArticleVectorProjectionSource.Chunk(id, 77L, number, "标题", input,
                "c".repeat(64), "d".repeat(64), "zh-CN");
    }

    private static DomainEvent event(long version, long epoch) {
        return new DomainEvent(UUID.randomUUID(), "ARTICLE_CHUNK_SET", ARTICLE_ID, version, epoch,
                DomainEventType.ARTICLE_CHUNK_REINDEX_REQUESTED, 1,
                JsonNodeFactory.instance.objectNode(), Instant.now());
    }

    private static float[] unitVector() {
        float[] result = new float[MilvusCollectionSchemas.EMBEDDING_DIMENSION];
        result[0] = 1F;
        return result;
    }

    private static final long ARTICLE_ID = 9_001L;

    private final class DirectExecutor implements AiCapabilityExecutor {
        @Override
        public <T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                             io.github.resilience4j.core.functions.CheckedSupplier<T> operation) {
            assertThat(context.capability()).isEqualTo(AiCapability.EMBEDDING);
            assertThat(context.background()).isTrue();
            try {
                return operation.get();
            } catch (Throwable throwable) {
                throw new IllegalStateException(throwable);
            }
        }

        @Override
        public <A, T> T execute(cumt.zongzuo.community.ai.runtime.AiInvocationContext context,
                                AttemptObserver<A, T> observer, AttemptOperation<A, T> operation) {
            throw new UnsupportedOperationException();
        }
    }
}
