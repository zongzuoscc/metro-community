package cumt.zongzuo.community.article.chunk;

import cumt.zongzuo.community.article.projection.ArticleProjectionConsumers;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import cumt.zongzuo.community.event.projection.ProjectionLeaseService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@ConditionalOnProperty(prefix = "metro.projection.article-chunks", name = "enabled", havingValue = "true")
class ArticleChunkProjectionTransaction {

    private final ProjectionLeaseService leaseService;
    private final ArticleChunkMaterializationService materializationService;

    ArticleChunkProjectionTransaction(ProjectionLeaseService leaseService,
                                      ArticleChunkMaterializationService materializationService) {
        this.leaseService = leaseService;
        this.materializationService = materializationService;
    }

    @Transactional
    ArticleChunkProjectionService.ApplyResult apply(DomainEvent event) {
        ArticleChunkMaterializationService.MaterializationResult result =
                materializationService.materialize(event.aggregateId(), event.lifecycleEpoch(),
                        event.aggregateVersion());
        if (result.stale()) {
            return new ArticleChunkProjectionService.ApplyResult(ProjectionLease.Decision.STALE, false);
        }
        ProjectionLease lease = leaseService.acquire(ArticleProjectionConsumers.CHUNK_CURRENT_POINTER,
                event, Duration.ofMinutes(2));
        if (!lease.acquired()) {
            throw new ArticleChunkProjectionService.ProjectionSkipped(lease.decision());
        }
        leaseService.complete(lease, event, result.activeChunkCount() == 0, result.resultHash());
        return new ArticleChunkProjectionService.ApplyResult(ProjectionLease.Decision.ACQUIRED,
                result.applied());
    }
}
