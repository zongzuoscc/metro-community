package cumt.zongzuo.community.event.projection;

import java.util.Objects;

public record ProjectionRepairLease(
        String consumer,
        String aggregateType,
        long aggregateId,
        long aggregateVersion,
        long lifecycleEpoch,
        String leaseOwner,
        Decision decision) {

    public ProjectionRepairLease {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(decision, "decision");
    }

    public boolean acquired() {
        return decision == Decision.ACQUIRED;
    }

    public enum Decision {
        ACQUIRED,
        STALE,
        BUSY
    }
}
