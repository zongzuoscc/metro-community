package cumt.zongzuo.community.article.projection.vector;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.EmbeddingCommand;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import cumt.zongzuo.community.event.projection.ProjectionLeaseService;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class ArticleMilvusProjectionService {

    public static final String CONSUMER = "article-chunk-milvus";

    private final ProjectionLeaseService leases;
    private final ArticleVectorProjectionSource source;
    private final ArticleVectorRepository repository;
    private final AiCapabilityExecutor executor;
    private final EmbeddingGateway embeddingGateway;
    private final Clock clock;
    private final String physicalCollection;
    private final String embeddingModel;
    private final Duration leaseDuration;
    private final Duration providerTimeout;

    public ArticleMilvusProjectionService(ProjectionLeaseService leases,
                                          ArticleVectorProjectionSource source,
                                          ArticleVectorRepository repository,
                                          AiCapabilityExecutor executor,
                                          EmbeddingGateway embeddingGateway,
                                          Clock clock,
                                          String physicalCollection,
                                          String embeddingModel,
                                          Duration leaseDuration,
                                          Duration providerTimeout) {
        this.leases = Objects.requireNonNull(leases);
        this.source = Objects.requireNonNull(source);
        this.repository = Objects.requireNonNull(repository);
        this.executor = Objects.requireNonNull(executor);
        this.embeddingGateway = Objects.requireNonNull(embeddingGateway);
        this.clock = Objects.requireNonNull(clock);
        this.physicalCollection = requireText(physicalCollection, "physicalCollection");
        this.embeddingModel = requireText(embeddingModel, "embeddingModel");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.providerTimeout = requirePositive(providerTimeout, "providerTimeout");
    }

    public ProjectionLease.Decision apply(DomainEvent event) {
        requireEvent(event);
        ProjectionLease lease = leases.acquire(CONSUMER, event, leaseDuration);
        if (!lease.acquired()) {
            return lease.decision();
        }
        ArticleVectorProjectionSource.Snapshot snapshot = source.load(event.aggregateId());
        if (snapshot.chunkSetVersion() < event.aggregateVersion()
                || snapshot.lifecycleEpoch() < event.lifecycleEpoch()) {
            throw new IllegalStateException("article vector source is older than its event");
        }
        leases.assertOwned(lease);
        List<Long> previousIds = repository.listChunkIdsByArticle(physicalCollection,
                snapshot.articleId());
        List<Long> currentIds = snapshot.chunks().stream()
                .map(ArticleVectorProjectionSource.Chunk::id).sorted().toList();
        try {
            if (!snapshot.chunks().isEmpty()) {
                EmbeddingResult result = embed(event, snapshot);
                leases.renew(lease, leaseDuration);
                if (!source.isCurrent(snapshot)) {
                    throw new IllegalStateException("article chunk facts changed during embedding");
                }
                List<ArticleVectorDocument> documents = documents(snapshot, result);
                if (repository.upsert(physicalCollection, documents) != documents.size()) {
                    throw new IllegalStateException("Milvus did not upsert every article chunk");
                }
            } else {
                leases.renew(lease, leaseDuration);
            }
            HashSet<Long> retained = new HashSet<>(currentIds);
            List<Long> obsolete = previousIds.stream().filter(id -> !retained.contains(id)).toList();
            if (!obsolete.isEmpty()) {
                repository.deleteByChunkIds(physicalCollection, obsolete);
                repository.assertDeletedStrong(physicalCollection, obsolete);
            }
            leases.assertOwned(lease);
            if (!source.isCurrent(snapshot)) {
                compensate(snapshot);
                throw new IllegalStateException("article chunk facts changed during Milvus projection");
            }
            leases.complete(lease, event, snapshot.tombstone(), snapshot.chunkSetHash());
            return ProjectionLease.Decision.ACQUIRED;
        } catch (RuntimeException exception) {
            if (!source.isCurrent(snapshot)) {
                compensate(snapshot);
            }
            throw exception;
        }
    }

    private EmbeddingResult embed(DomainEvent event, ArticleVectorProjectionSource.Snapshot snapshot) {
        List<String> inputs = snapshot.chunks().stream()
                .map(ArticleVectorProjectionSource.Chunk::embeddingInput).toList();
        int inputCharacters = inputs.stream().mapToInt(String::length)
                .reduce(0, Math::addExact);
        AiInvocationContext context = new AiInvocationContext(AiCapability.EMBEDDING, null,
                event.eventId().toString(), inputCharacters, clock.instant().plus(providerTimeout), true);
        EmbeddingResult result = executor.execute(context,
                () -> embeddingGateway.embed(new EmbeddingCommand(AiCapability.EMBEDDING, inputs)));
        if (!embeddingModel.equals(result.model()) || result.vectors().size() != inputs.size()) {
            throw new IllegalStateException("embedding result identity does not match the projection target");
        }
        return result;
    }

    private List<ArticleVectorDocument> documents(ArticleVectorProjectionSource.Snapshot snapshot,
                                                   EmbeddingResult result) {
        List<float[]> vectors = result.vectors();
        long publishedAt = snapshot.publishedAt().atZone(clock.getZone()).toEpochSecond();
        return java.util.stream.IntStream.range(0, snapshot.chunks().size()).mapToObj(index -> {
            ArticleVectorProjectionSource.Chunk chunk = snapshot.chunks().get(index);
            return new ArticleVectorDocument(chunk.id(), vectors.get(index), snapshot.articleId(),
                    chunk.revisionId(), snapshot.authorId(), chunk.chunkNo(),
                    chunk.revisionContentHash(), true, publishedAt, chunk.language(), embeddingModel,
                    snapshot.parserVersion(), snapshot.parserGeneration(), snapshot.lifecycleEpoch(),
                    snapshot.chunkSetVersion(), snapshot.sourceAggregateVersion());
        }).toList();
    }

    private void compensate(ArticleVectorProjectionSource.Snapshot snapshot) {
        List<Long> ids = snapshot.chunks().stream()
                .map(ArticleVectorProjectionSource.Chunk::id).sorted().toList();
        if (!ids.isEmpty()) {
            repository.deleteByChunkIds(physicalCollection, ids);
            repository.assertDeletedStrong(physicalCollection, ids);
        }
    }

    private static void requireEvent(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        if (!"ARTICLE_CHUNK_SET".equals(event.aggregateType())
                || event.eventType() != DomainEventType.ARTICLE_CHUNK_REINDEX_REQUESTED) {
            throw new IllegalArgumentException("unsupported article Milvus projection event");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
