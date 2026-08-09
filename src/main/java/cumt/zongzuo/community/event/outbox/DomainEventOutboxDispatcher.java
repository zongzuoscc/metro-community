package cumt.zongzuo.community.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class DomainEventOutboxDispatcher {

    private final DomainEventOutboxClaimer claimer;
    private final DomainEventOutboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final DomainEventPublisher publisher;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxAttempts;

    @Autowired
    public DomainEventOutboxDispatcher(
            DomainEventOutboxClaimer claimer,
            DomainEventOutboxMapper mapper,
            ObjectMapper objectMapper,
            DomainEventPublisher publisher,
            @Value("${metro.events.outbox.batch-size:100}") int batchSize,
            @Value("${metro.events.outbox.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${metro.events.outbox.max-attempts:12}") int maxAttempts) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        if (maxAttempts != DomainEventOutboxClaimer.MAX_DISPATCH_ATTEMPTS) {
            throw new IllegalArgumentException("domain event maxAttempts is fixed at 12");
        }
        this.claimer = claimer;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxAttempts = maxAttempts;
    }

    public void dispatchPending() {
        /*
         * A lease starts when its row is claimed. Claiming a large batch and then
         * waiting for confirms serially can therefore expire rows at the tail
         * before their first publish attempt. Keep each lease scoped to exactly
         * one confirm; batchSize only bounds the work performed by this tick.
         */
        for (int dispatched = 0; dispatched < batchSize; dispatched++) {
            var claimed = claimer.claimBatch(1, leaseDuration);
            if (claimed.isEmpty()) {
                return;
            }
            DomainEventOutbox row = claimed.getFirst();
            try {
                dispatchClaimed(row);
            } catch (OutboxLeaseLostException exception) {
                log.warn("Domain outbox completion lost its lease: {}", exception.getMessage());
            }
        }
    }

    public void dispatchClaimed(DomainEventOutbox row) {
        if (row.isDispatchExhausted()) {
            markDead(row, "Dispatch attempts exhausted after lease recovery");
            return;
        }
        try {
            publisher.publish(row.toEvent(objectMapper), row.getLeaseOwner());
        } catch (Exception exception) {
            handleFailure(row, exception);
            return;
        }
        if (mapper.markPublished(row.getId(), row.getLeaseOwner(), databaseNow()) != 1) {
            throw new OutboxLeaseLostException(row.getId(), row.getLeaseOwner());
        }
    }

    private void handleFailure(DomainEventOutbox row, Exception exception) {
        String error = sanitizeError(exception);
        if (row.getRetryCount() >= maxAttempts) {
            markDead(row, error);
            return;
        }
        long delaySeconds = retryDelaySeconds(row.getRetryCount());
        Instant nextAttemptAt = databaseNow().plusSeconds(delaySeconds);
        if (mapper.markRetry(row.getId(), row.getLeaseOwner(), row.getRetryCount(),
                nextAttemptAt, error) != 1) {
            throw new OutboxLeaseLostException(row.getId(), row.getLeaseOwner());
        }
        log.warn("Domain outbox {} attempt {} failed; retry scheduled",
                row.getId(), row.getRetryCount());
    }

    private void markDead(DomainEventOutbox row, String error) {
        if (mapper.markDead(row.getId(), row.getLeaseOwner(), row.getRetryCount(),
                sanitizeText(error), databaseNow()) != 1) {
            throw new OutboxLeaseLostException(row.getId(), row.getLeaseOwner());
        }
        log.error("Domain outbox {} exhausted {} dispatch attempts", row.getId(), row.getRetryCount());
    }

    private Instant databaseNow() {
        return mapper.selectDatabaseNow();
    }

    static long retryDelaySeconds(int attempt) {
        if (attempt <= 0) {
            return 1;
        }
        int shift = Math.min(attempt - 1, 8);
        return Math.min(1L << shift, 300L);
    }

    static String sanitizeError(Throwable throwable) {
        String message = throwable.getMessage();
        return sanitizeText(throwable.getClass().getSimpleName() + ": "
                + (message == null ? "unspecified" : message));
    }

    private static String sanitizeText(String value) {
        String sanitized = value.replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll(" +", " ").trim();
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }
}

@Component
@ConditionalOnProperty(name = "metro.events.outbox.dispatch-enabled", matchIfMissing = true)
class DomainEventOutboxSchedule {

    private final DomainEventOutboxDispatcher dispatcher;

    DomainEventOutboxSchedule(DomainEventOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${metro.events.outbox.dispatch-delay-ms:1000}")
    void dispatch() {
        dispatcher.dispatchPending();
    }
}
