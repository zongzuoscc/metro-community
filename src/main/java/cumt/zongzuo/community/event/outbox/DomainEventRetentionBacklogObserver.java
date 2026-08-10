package cumt.zongzuo.community.event.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(
        name = "metro.events.retention.metrics-enabled",
        havingValue = "true",
        matchIfMissing = true)
class DomainEventRetentionBacklogObserver {

    private final DomainEventRetentionMapper mapper;
    private final DomainEventOutboxMapper outboxMapper;
    private final DomainEventRetentionMetrics metrics;

    DomainEventRetentionBacklogObserver(
            DomainEventRetentionMapper mapper,
            DomainEventOutboxMapper outboxMapper,
            DomainEventRetentionMetrics metrics) {
        this.mapper = mapper;
        this.outboxMapper = outboxMapper;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${metro.events.retention.metrics-delay:PT5M}",
            initialDelayString = "${metro.events.retention.metrics-initial-delay:PT30S}")
    void observe() {
        LocalDateTime databaseNow = outboxMapper.selectDatabaseLocalNow();
        metrics.observeBacklog(
                mapper.selectUnresolvedDeadCount(),
                mapper.selectOldestPendingCreatedAt(),
                databaseNow);
    }
}
