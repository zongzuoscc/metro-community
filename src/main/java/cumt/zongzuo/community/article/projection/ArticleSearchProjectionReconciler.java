package cumt.zongzuo.community.article.projection;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import cumt.zongzuo.community.event.projection.ProjectionLease;
import cumt.zongzuo.community.mapper.ArticleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ArticleSearchProjectionReconciler {

    private final ArticleMapper articleMapper;
    private final ArticleSearchProjectionService projectionService;

    public ArticleSearchProjectionReconciler(ArticleMapper articleMapper,
                                             ArticleSearchProjectionService projectionService) {
        this.articleMapper = articleMapper;
        this.projectionService = projectionService;
    }

    /**
     * Rebuilds at most one bounded keyset page through the same aggregate fence as
     * live delivery. The deterministic synthetic identity makes reruns idempotent.
     */
    public BatchResult reconcileAfter(long afterArticleId, int limit) {
        if (afterArticleId < 0 || limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("reconcile cursor/limit is invalid");
        }
        List<Article> cursors = articleMapper.selectProjectionCursorsAfter(afterArticleId, limit);
        if (cursors.isEmpty()) {
            return new BatchResult(afterArticleId, 0, 0, 0, 0, 0, 0, false);
        }
        int upserted = 0;
        int deleted = 0;
        int skipped = 0;
        int scanned = 0;
        long nextArticleId = afterArticleId;
        for (Article cursor : cursors) {
            scanned++;
            DomainEvent event = reconciliationEvent(cursor);
            ArticleSearchProjectionService.ApplyResult result = projectionService.apply(event);
            if (result.decision() == ProjectionLease.Decision.BUSY) {
                return new BatchResult(nextArticleId, scanned, upserted, deleted, 1, skipped,
                        cursors.size(), true);
            }
            nextArticleId = cursor.getId();
            if (result.decision() == ProjectionLease.Decision.ACQUIRED) {
                if (result.tombstone()) {
                    deleted++;
                } else {
                    upserted++;
                }
            } else {
                skipped++;
            }
        }
        return new BatchResult(nextArticleId, scanned, upserted, deleted, 0, skipped,
                cursors.size(), cursors.size() == limit);
    }

    private static DomainEvent reconciliationEvent(Article cursor) {
        if (cursor.getId() == null || cursor.getId() <= 0
                || cursor.getLifecycleEpoch() == null || cursor.getLifecycleEpoch() < 0
                || cursor.getLockVersion() == null || cursor.getLockVersion() < 0) {
            throw new IllegalStateException("article projection reconciliation identity is invalid");
        }
        String identity = "article-search-reconcile-v1:" + cursor.getId() + ":"
                + cursor.getLifecycleEpoch() + ":" + cursor.getLockVersion();
        return new DomainEvent(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                "ARTICLE", cursor.getId(), cursor.getLockVersion(), cursor.getLifecycleEpoch(),
                DomainEventType.ARTICLE_REVISION_PUBLISHED, 1,
                JsonNodeFactory.instance.objectNode()
                        .put("articleId", cursor.getId())
                        .put("transition", "RECONCILE"),
                Instant.EPOCH);
    }

    public record BatchResult(long nextArticleId, int scanned, int upserted, int deleted,
                              int busy, int skipped, int maximumBatchSize,
                              boolean mayHaveMore) {
    }
}

@Component
@ConditionalOnProperty(name = "metro.article.projection.reconcile-enabled", havingValue = "true")
class ArticleSearchProjectionReconciliationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(
            ArticleSearchProjectionReconciliationRunner.class);

    private final ArticleSearchProjectionReconciler reconciler;
    private final ArticleProjectionProperties properties;

    ArticleSearchProjectionReconciliationRunner(ArticleSearchProjectionReconciler reconciler,
                                                ArticleProjectionProperties properties) {
        this.reconciler = reconciler;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        properties.validate();
        long cursor = properties.getReconcileStartAfterId();
        long scanned = 0;
        for (int batch = 0; batch < properties.getReconcileMaximumBatches(); batch++) {
            ArticleSearchProjectionReconciler.BatchResult result = reconciler.reconcileAfter(
                    cursor, properties.getReconcileBatchSize());
            if (result.busy() > 0) {
                throw new IllegalStateException("article projection reconciliation aggregate is busy; cursor="
                        + result.nextArticleId());
            }
            scanned += result.scanned();
            cursor = result.nextArticleId();
            if (!result.mayHaveMore()) {
                log.info("Article projection reconciliation completed: scanned={}, cursor={}",
                        scanned, cursor);
                return;
            }
        }
        throw new IllegalStateException("article projection reconciliation exceeded maximum batches; cursor="
                + cursor);
    }
}
