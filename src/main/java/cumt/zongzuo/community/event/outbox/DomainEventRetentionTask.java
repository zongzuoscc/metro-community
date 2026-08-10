package cumt.zongzuo.community.event.outbox;

import cumt.zongzuo.community.article.projection.ArticleProjectionConsumers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

@Component
public class DomainEventRetentionTask {

    private static final Duration PUBLISHED_RETENTION = Duration.ofDays(7);
    private static final Duration INBOX_RETENTION = Duration.ofDays(30);
    private static final Duration OPERATOR_RESOLVED_RETENTION = Duration.ofDays(90);

    private final DomainEventRetentionMapper mapper;
    private final DomainEventOutboxMapper outboxMapper;
    private final TransactionTemplate transactions;
    private final DomainEventRetentionMetrics metrics;
    private final int batchSize;
    private final int maxBatches;

    public DomainEventRetentionTask(
            DomainEventRetentionMapper mapper,
            DomainEventOutboxMapper outboxMapper,
            PlatformTransactionManager transactionManager,
            DomainEventRetentionMetrics metrics,
            @Value("${metro.events.retention.batch-size:200}") int batchSize,
            @Value("${metro.events.retention.max-batches:20}") int maxBatches) {
        if (batchSize < 1 || batchSize > 1_000 || maxBatches < 1 || maxBatches > 1_000) {
            throw new IllegalArgumentException("retention batch bounds are invalid");
        }
        this.mapper = mapper;
        this.outboxMapper = outboxMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    public DomainEventRetentionResult runOnce() {
        return runOnceAt(outboxMapper.selectDatabaseLocalNow());
    }

    DomainEventRetentionResult runOnceAt(LocalDateTime databaseNow) {
        LocalDateTime publishedCutoff = databaseNow.minus(PUBLISHED_RETENTION);
        LocalDateTime inboxCutoff = databaseNow.minus(INBOX_RETENTION);
        LocalDateTime operatorCutoff = databaseNow.minus(OPERATOR_RESOLVED_RETENTION);

        int published = deleteLongBatches(
                limit -> mapper.selectPublishedForRetention(
                        publishedCutoff, ArticleProjectionConsumers.SEARCH_CURRENT_POINTER, limit),
                ids -> mapper.deletePublishedBatchExact(
                        ids, publishedCutoff, ArticleProjectionConsumers.SEARCH_CURRENT_POINTER));
        int requeuedPublished = deleteLongBatches(
                limit -> mapper.selectRequeuedPublishedForRetention(
                        operatorCutoff, publishedCutoff,
                        ArticleProjectionConsumers.SEARCH_CURRENT_POINTER, limit),
                ids -> mapper.deleteRequeuedPublishedBatchExact(
                        ids, operatorCutoff, publishedCutoff,
                        ArticleProjectionConsumers.SEARCH_CURRENT_POINTER));
        int resolvedDead = deleteLongBatches(
                limit -> mapper.selectResolvedDeadForRetention(
                        operatorCutoff, ArticleProjectionConsumers.SEARCH_CURRENT_POINTER, limit),
                ids -> mapper.deleteResolvedDeadBatchExact(
                        ids, operatorCutoff,
                        ArticleProjectionConsumers.SEARCH_CURRENT_POINTER));
        int inbox = deleteBatches(
                limit -> mapper.selectInboxForRetention(inboxCutoff, limit),
                keys -> mapper.deleteInboxBatchExact(keys, inboxCutoff));
        int migrationIssues = deleteLongBatches(
                limit -> mapper.selectResolvedMigrationIssuesForRetention(operatorCutoff, limit),
                ids -> mapper.deleteResolvedMigrationIssueBatchExact(ids, operatorCutoff));

        metrics.deleted(DomainEventRetentionMetrics.Kind.PUBLISHED, published);
        metrics.deleted(DomainEventRetentionMetrics.Kind.REQUEUED_PUBLISHED, requeuedPublished);
        metrics.deleted(DomainEventRetentionMetrics.Kind.RESOLVED_DEAD, resolvedDead);
        metrics.deleted(DomainEventRetentionMetrics.Kind.INBOX, inbox);
        metrics.deleted(DomainEventRetentionMetrics.Kind.MIGRATION_ISSUE, migrationIssues);
        return new DomainEventRetentionResult(
                published, requeuedPublished, resolvedDead, inbox, migrationIssues);
    }

    private int deleteLongBatches(Function<Integer, List<Long>> selector,
                                  ToIntFunction<List<Long>> deleter) {
        return deleteBatches(selector, deleter);
    }

    private <T> int deleteBatches(Function<Integer, List<T>> selector,
                                  ToIntFunction<List<T>> deleter) {
        int deleted = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            BatchResult result = transactions.execute(status -> {
                List<T> candidates = selector.apply(batchSize);
                int affected = candidates.isEmpty() ? 0 : deleter.applyAsInt(candidates);
                return new BatchResult(candidates.size(), affected);
            });
            if (result == null) {
                throw new IllegalStateException("retention transaction returned no result");
            }
            deleted += result.deleted();
            if (result.selected() < batchSize) {
                break;
            }
        }
        return deleted;
    }

    private record BatchResult(int selected, int deleted) {
    }
}
