package cumt.zongzuo.community.article.projection;

import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import cumt.zongzuo.community.event.projection.ProjectionLeaseService;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

/**
 * The single fenced write path for both live events and bounded reconciliation.
 */
@Service
class ArticleSearchProjectionService {

    static final String CONSUMER = "article-search-current-pointer";
    private static final Set<DomainEventType> SUPPORTED = EnumSet.of(
            DomainEventType.ARTICLE_REVISION_PUBLISHED,
            DomainEventType.ARTICLE_REVISION_REJECTED,
            DomainEventType.ARTICLE_REVISION_SUPERSEDED,
            DomainEventType.ARTICLE_UNPUBLISHED,
            DomainEventType.ARTICLE_DELETED);

    private final ProjectionLeaseService leaseService;
    private final ArticleProjectionSource source;
    private final ArticleProjectionEffectStore effectStore;
    private final ArticleProjectionProperties properties;
    private final ArticleIndexMappingGuard mappingGuard;

    ArticleSearchProjectionService(ProjectionLeaseService leaseService,
                                   ArticleProjectionSource source,
                                   ArticleProjectionEffectStore effectStore,
                                   ArticleProjectionProperties properties,
                                   ArticleIndexMappingGuard mappingGuard) {
        this.leaseService = leaseService;
        this.source = source;
        this.effectStore = effectStore;
        this.properties = properties;
        this.mappingGuard = mappingGuard;
    }

    ApplyResult apply(DomainEvent event) {
        requireSupported(event);
        ProjectionLease lease = leaseService.acquire(CONSUMER, event, properties.getLeaseDuration());
        if (!lease.acquired()) {
            return new ApplyResult(lease.decision(), false);
        }

        // Source is deliberately re-read only after owning the aggregate fence.
        ArticleProjectionSource.Snapshot snapshot = source.loadCurrent(event.aggregateId(),
                event.lifecycleEpoch(), event.aggregateVersion());
        mappingGuard.ensureCompatible();
        effectStore.apply(event.aggregateId(), snapshot);
        // ES is intentionally applied first. If completion fails, Rabbit/reconcile
        // retries after the lease and safely repeats the idempotent ES effect.
        leaseService.complete(lease, event, !snapshot.present(), snapshot.resultHash());
        return new ApplyResult(ProjectionLease.Decision.ACQUIRED, !snapshot.present());
    }

    private static void requireSupported(DomainEvent event) {
        if (event == null || !"ARTICLE".equals(event.aggregateType())
                || !SUPPORTED.contains(event.eventType())) {
            throw new IllegalArgumentException("unsupported article projection event");
        }
    }

    record ApplyResult(ProjectionLease.Decision decision, boolean tombstone) {
    }
}
