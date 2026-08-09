package cumt.zongzuo.community.event.projection;

import java.util.Objects;
import java.util.UUID;

public record ProjectionLease(
        String consumer,
        String aggregateType,
        long aggregateId,
        UUID eventId,
        long aggregateVersion,
        long lifecycleEpoch,
        String leaseOwner,
        Decision decision) {

    public ProjectionLease {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(decision, "decision");
    }

    public boolean acquired() {
        return decision == Decision.ACQUIRED;
    }

    public enum Decision {
        ACQUIRED,
        DUPLICATE,
        STALE,
        BUSY
    }
}
