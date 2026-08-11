package cumt.zongzuo.community.article.chunk;

import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "metro.projection.article-chunks", name = "enabled", havingValue = "true")
public class ArticleChunkProjectionService {

    private static final Set<DomainEventType> SUPPORTED = EnumSet.of(
            DomainEventType.ARTICLE_REVISION_PUBLISHED,
            DomainEventType.ARTICLE_REVISION_REJECTED,
            DomainEventType.ARTICLE_REVISION_SUPERSEDED,
            DomainEventType.ARTICLE_UNPUBLISHED,
            DomainEventType.ARTICLE_DELETED);

    private final ArticleChunkProjectionTransaction transaction;

    public ArticleChunkProjectionService(ArticleChunkProjectionTransaction transaction) {
        this.transaction = transaction;
    }

    public ApplyResult apply(DomainEvent event) {
        requireSupported(event);
        try {
            return transaction.apply(event);
        } catch (ProjectionSkipped skipped) {
            return new ApplyResult(skipped.decision(), false);
        }
    }

    private static void requireSupported(DomainEvent event) {
        if (event == null || !"ARTICLE".equals(event.aggregateType())
                || !SUPPORTED.contains(event.eventType())) {
            throw new IllegalArgumentException("unsupported article chunk projection event");
        }
    }

    public record ApplyResult(ProjectionLease.Decision decision, boolean materialized) {
    }

    static final class ProjectionSkipped extends RuntimeException {

        private final ProjectionLease.Decision decision;

        ProjectionSkipped(ProjectionLease.Decision decision) {
            super("article chunk projection was skipped: " + decision);
            this.decision = decision;
        }

        ProjectionLease.Decision decision() {
            return decision;
        }
    }
}
