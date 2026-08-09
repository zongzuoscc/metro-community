package cumt.zongzuo.community.event.projection;

import cumt.zongzuo.community.event.domain.DomainEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public interface ProjectionLeaseService {
    ProjectionLease acquire(String consumer, DomainEvent event, Duration lease);

    void complete(ProjectionLease lease, DomainEvent event, boolean tombstone, String resultHash);
}

@Service
class DefaultProjectionLeaseService implements ProjectionLeaseService {

    private final ConsumerInboxMapper inboxMapper;
    private final ProjectionWatermarkMapper watermarkMapper;

    DefaultProjectionLeaseService(ConsumerInboxMapper inboxMapper,
                                  ProjectionWatermarkMapper watermarkMapper) {
        this.inboxMapper = inboxMapper;
        this.watermarkMapper = watermarkMapper;
    }

    @Override
    @Transactional
    public ProjectionLease acquire(String consumer, DomainEvent event, Duration lease) {
        requireConsumer(consumer);
        Objects.requireNonNull(event, "event");
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        long leaseMicros;
        try {
            leaseMicros = Math.multiplyExact(lease.toMillis(), 1_000L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("lease is too large", exception);
        }

        watermarkMapper.insertIfAbsent(consumer, event.aggregateType(), event.aggregateId());
        ProjectionWatermark watermark = watermarkMapper.selectForUpdate(
                consumer, event.aggregateType(), event.aggregateId());

        if (inboxMapper.exists(consumer, event.eventId())) {
            return skipped(consumer, event, ProjectionLease.Decision.DUPLICATE);
        }
        LocalDateTime databaseNow = watermarkMapper.selectDatabaseNow();
        if (watermark.getLeaseOwner() != null && watermark.getLeaseUntil() != null
                && watermark.getLeaseUntil().isAfter(databaseNow)) {
            return skipped(consumer, event, ProjectionLease.Decision.BUSY);
        }
        if (isStale(watermark, event)) {
            return skipped(consumer, event, ProjectionLease.Decision.STALE);
        }

        String owner = UUID.randomUUID().toString();
        if (watermarkMapper.acquire(consumer, event.aggregateType(), event.aggregateId(), owner,
                leaseMicros) != 1) {
            return skipped(consumer, event, ProjectionLease.Decision.BUSY);
        }
        return new ProjectionLease(consumer, event.aggregateType(), event.aggregateId(),
                event.eventId(), event.aggregateVersion(), event.lifecycleEpoch(), owner,
                ProjectionLease.Decision.ACQUIRED);
    }

    @Override
    @Transactional
    public void complete(ProjectionLease lease, DomainEvent event, boolean tombstone, String resultHash) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(event, "event");
        if (!lease.acquired() || lease.leaseOwner() == null) {
            throw new IllegalArgumentException("only an acquired lease can be completed");
        }
        if (!lease.aggregateType().equals(event.aggregateType())
                || lease.aggregateId() != event.aggregateId()
                || !lease.eventId().equals(event.eventId())
                || lease.aggregateVersion() != event.aggregateVersion()
                || lease.lifecycleEpoch() != event.lifecycleEpoch()) {
            throw new IllegalArgumentException("lease does not belong to the event");
        }
        if (resultHash == null || !resultHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("resultHash must be lowercase SHA-256 hex");
        }
        inboxMapper.insert(lease.consumer(), event.eventId(), resultHash);
        if (watermarkMapper.complete(lease.consumer(), event.aggregateType(), event.aggregateId(),
                lease.leaseOwner(), event.aggregateVersion(), event.lifecycleEpoch(), tombstone) != 1) {
            throw new ProjectionLeaseLostException("projection lease was lost before completion");
        }
    }

    private static boolean isStale(ProjectionWatermark watermark, DomainEvent event) {
        if (event.lifecycleEpoch() < watermark.getLifecycleEpoch()) {
            return true;
        }
        return event.lifecycleEpoch() == watermark.getLifecycleEpoch()
                && event.aggregateVersion() <= watermark.getLastAppliedVersion();
    }

    private static ProjectionLease skipped(String consumer, DomainEvent event,
                                           ProjectionLease.Decision decision) {
        return new ProjectionLease(consumer, event.aggregateType(), event.aggregateId(),
                event.eventId(), event.aggregateVersion(), event.lifecycleEpoch(), null, decision);
    }

    private static void requireConsumer(String consumer) {
        if (consumer == null || consumer.isBlank() || consumer.length() > 96) {
            throw new IllegalArgumentException("consumer must contain 1..96 characters");
        }
    }
}

class ProjectionLeaseLostException extends IllegalStateException {
    ProjectionLeaseLostException(String message) {
        super(message);
    }
}
