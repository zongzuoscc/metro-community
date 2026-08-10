package cumt.zongzuo.community.event.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DomainEventRetentionMetrics {

    enum Kind {
        PUBLISHED("published"),
        REQUEUED_PUBLISHED("requeued_published"),
        RESOLVED_DEAD("resolved_dead"),
        INBOX("inbox"),
        MIGRATION_ISSUE("migration_issue");

        private final String tag;

        Kind(String tag) {
            this.tag = tag;
        }
    }

    private final EnumMap<Kind, Counter> deleted = new EnumMap<>(Kind.class);
    private final AtomicLong unresolvedDead = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    DomainEventRetentionMetrics(MeterRegistry registry) {
        for (Kind kind : Kind.values()) {
            deleted.put(kind, Counter.builder("domain.event.retention.deleted")
                    .tag("kind", kind.tag)
                    .register(registry));
        }
        registry.gauge(
                "domain.event.retention.unresolved.dead.count", unresolvedDead);
        registry.gauge(
                "domain.event.outbox.oldest.pending.age.seconds", oldestPendingAgeSeconds);
    }

    void deleted(Kind kind, int count) {
        if (count > 0) {
            deleted.get(kind).increment(count);
        }
    }

    void observeBacklog(long unresolvedDeadCount,
                        LocalDateTime oldestPendingCreatedAt,
                        LocalDateTime databaseNow) {
        unresolvedDead.set(Math.max(0L, unresolvedDeadCount));
        long pendingAge = oldestPendingCreatedAt == null
                ? 0L
                : Duration.between(oldestPendingCreatedAt, databaseNow).getSeconds();
        oldestPendingAgeSeconds.set(Math.max(0L, pendingAge));
    }
}
