package cumt.zongzuo.community.event.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Own proxy boundary: every claim commits before any Rabbit publish begins. */
@Component
public class DomainEventOutboxClaimer {

    static final int MAX_DISPATCH_ATTEMPTS = 12;
    private static final int MAX_BATCH_SIZE = 100;

    private final DomainEventOutboxMapper mapper;

    public DomainEventOutboxClaimer(DomainEventOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<DomainEventOutbox> claimBatch(int limit, Duration lease) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("claim limit must be between 1 and 100");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        long leaseMicros;
        try {
            leaseMicros = Math.multiplyExact(lease.toMillis(), 1_000L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("lease is too large", exception);
        }

        String leaseOwner = UUID.randomUUID().toString();
        List<DomainEventOutbox> selected = mapper.selectClaimableForUpdate(limit);
        List<DomainEventOutbox> claimed = new ArrayList<>(selected.size());
        for (DomainEventOutbox candidate : selected) {
            int previousAttempts = candidate.getRetryCount();
            boolean exhaustedRecovery = previousAttempts >= MAX_DISPATCH_ATTEMPTS;
            int attempt = exhaustedRecovery ? previousAttempts : previousAttempts + 1;
            if (mapper.claim(candidate.getId(), leaseOwner, attempt, leaseMicros) != 1) {
                continue;
            }
            DomainEventOutbox row = mapper.selectById(candidate.getId());
            row.setDispatchExhausted(exhaustedRecovery);
            claimed.add(row);
        }
        return List.copyOf(claimed);
    }
}
