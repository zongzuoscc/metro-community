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

    void renew(ProjectionLease lease, Duration duration);

    void assertOwned(ProjectionLease lease);

    void complete(ProjectionLease lease, DomainEvent event, boolean tombstone, String resultHash);

    ProjectionRepairLease acquireRepair(String consumer, String aggregateType, long aggregateId,
                                        long aggregateVersion, long lifecycleEpoch, Duration lease);

    void assertOwned(ProjectionRepairLease lease);

    void completeRepair(ProjectionRepairLease lease);
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
        long leaseMicros = leaseMicros(lease);

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
    public void renew(ProjectionLease lease, Duration duration) {
        requireAcquired(lease);
        if (watermarkMapper.renew(lease.consumer(), lease.aggregateType(), lease.aggregateId(),
                lease.leaseOwner(), leaseMicros(duration)) != 1) {
            throw lost();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void assertOwned(ProjectionLease lease) {
        requireAcquired(lease);
        if (watermarkMapper.countOwned(lease.consumer(), lease.aggregateType(), lease.aggregateId(),
                lease.leaseOwner()) != 1) {
            throw lost();
        }
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

    @Override
    @Transactional
    public ProjectionRepairLease acquireRepair(String consumer, String aggregateType, long aggregateId,
                                               long aggregateVersion, long lifecycleEpoch,
                                               Duration lease) {
        requireConsumer(consumer);
        if (aggregateType == null || aggregateType.isBlank() || aggregateType.length() > 64) {
            throw new IllegalArgumentException("aggregateType must contain 1..64 characters");
        }
        if (aggregateVersion < 0 || lifecycleEpoch < 0) {
            throw new IllegalArgumentException("repair tuple must be non-negative");
        }
        long leaseMicros = leaseMicros(lease);
        watermarkMapper.insertIfAbsent(consumer, aggregateType, aggregateId);
        ProjectionWatermark watermark = watermarkMapper.selectForUpdate(consumer, aggregateType, aggregateId);
        LocalDateTime databaseNow = watermarkMapper.selectDatabaseNow();
        if (watermark.getLeaseOwner() != null && watermark.getLeaseUntil() != null
                && watermark.getLeaseUntil().isAfter(databaseNow)) {
            return repairSkipped(consumer, aggregateType, aggregateId, aggregateVersion,
                    lifecycleEpoch, ProjectionRepairLease.Decision.BUSY);
        }
        if (watermark.getLastAppliedVersion() != aggregateVersion
                || watermark.getLifecycleEpoch() != lifecycleEpoch) {
            return repairSkipped(consumer, aggregateType, aggregateId, aggregateVersion,
                    lifecycleEpoch, ProjectionRepairLease.Decision.STALE);
        }
        String owner = UUID.randomUUID().toString();
        if (watermarkMapper.acquire(consumer, aggregateType, aggregateId, owner, leaseMicros) != 1) {
            return repairSkipped(consumer, aggregateType, aggregateId, aggregateVersion,
                    lifecycleEpoch, ProjectionRepairLease.Decision.BUSY);
        }
        return new ProjectionRepairLease(consumer, aggregateType, aggregateId, aggregateVersion,
                lifecycleEpoch, owner, ProjectionRepairLease.Decision.ACQUIRED);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertOwned(ProjectionRepairLease lease) {
        requireAcquired(lease);
        if (watermarkMapper.countRepairOwned(lease.consumer(), lease.aggregateType(), lease.aggregateId(),
                lease.leaseOwner(), lease.aggregateVersion(), lease.lifecycleEpoch()) != 1) {
            throw lost();
        }
    }

    @Override
    @Transactional
    public void completeRepair(ProjectionRepairLease lease) {
        requireAcquired(lease);
        if (watermarkMapper.completeRepair(lease.consumer(), lease.aggregateType(), lease.aggregateId(),
                lease.leaseOwner(), lease.aggregateVersion(), lease.lifecycleEpoch()) != 1) {
            throw lost();
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

    private static ProjectionRepairLease repairSkipped(String consumer, String aggregateType,
                                                       long aggregateId, long aggregateVersion,
                                                       long lifecycleEpoch,
                                                       ProjectionRepairLease.Decision decision) {
        return new ProjectionRepairLease(consumer, aggregateType, aggregateId, aggregateVersion,
                lifecycleEpoch, null, decision);
    }

    private static long leaseMicros(Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        try {
            return Math.multiplyExact(lease.toMillis(), 1_000L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("lease is too large", exception);
        }
    }

    private static void requireAcquired(ProjectionLease lease) {
        Objects.requireNonNull(lease, "lease");
        if (!lease.acquired() || lease.leaseOwner() == null) {
            throw new IllegalArgumentException("only an acquired lease is owned");
        }
    }

    private static void requireAcquired(ProjectionRepairLease lease) {
        Objects.requireNonNull(lease, "lease");
        if (!lease.acquired() || lease.leaseOwner() == null) {
            throw new IllegalArgumentException("only an acquired repair lease is owned");
        }
    }

    private static ProjectionLeaseLostException lost() {
        return new ProjectionLeaseLostException("projection lease was lost");
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
