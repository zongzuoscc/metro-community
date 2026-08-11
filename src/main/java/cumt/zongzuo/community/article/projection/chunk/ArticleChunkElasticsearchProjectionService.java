package cumt.zongzuo.community.article.projection.chunk;

import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import cumt.zongzuo.community.event.projection.ProjectionLeaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

@Service
@ConditionalOnProperty(prefix = "metro.projection.article-chunk-elasticsearch",
        name = "enabled", havingValue = "true")
public class ArticleChunkElasticsearchProjectionService {

    public static final String CONSUMER = "article-chunk-elasticsearch";

    private final ProjectionLeaseService leases;
    private final ArticleChunkSearchSource source;
    private final ElasticsearchArticleChunkRepository repository;
    private final Duration leaseDuration;

    ArticleChunkElasticsearchProjectionService(ProjectionLeaseService leases,
                                               ArticleChunkSearchSource source,
                                               ElasticsearchArticleChunkRepository repository,
                                               @Value("${metro.projection.article-chunk-elasticsearch.lease-duration:PT30S}")
                                               Duration leaseDuration) {
        this.leases = leases;
        this.source = source;
        this.repository = repository;
        this.leaseDuration = leaseDuration;
    }

    public ProjectionLease.Decision apply(DomainEvent event) {
        requireEvent(event);
        ProjectionLease lease = leases.acquire(CONSUMER, event, leaseDuration);
        if (!lease.acquired()) {
            return lease.decision();
        }
        ArticleChunkSearchSource.Snapshot snapshot = source.load(event.aggregateId());
        if (snapshot.chunkSetVersion() < event.aggregateVersion()
                || snapshot.lifecycleEpoch() < event.lifecycleEpoch()) {
            throw new IllegalStateException("chunk fact is older than its projection event");
        }
        leases.assertOwned(lease);
        try {
            repository.replace(snapshot);
            leases.assertOwned(lease);
            if (!source.isCurrent(snapshot)) {
                repository.compensate(snapshot);
                throw new IllegalStateException("chunk fact changed during Elasticsearch projection");
            }
            leases.complete(lease, event, snapshot.tombstone(), snapshot.chunkSetHash());
            return ProjectionLease.Decision.ACQUIRED;
        } catch (RuntimeException exception) {
            if (!source.isCurrent(snapshot)) {
                repository.compensate(snapshot);
            }
            throw exception;
        }
    }

    private static void requireEvent(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        if (!"ARTICLE_CHUNK_SET".equals(event.aggregateType())
                || event.eventType() != DomainEventType.ARTICLE_CHUNK_REINDEX_REQUESTED) {
            throw new IllegalArgumentException("unsupported article chunk Elasticsearch event");
        }
    }
}
