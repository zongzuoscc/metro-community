package cumt.zongzuo.community.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.event.domain.DomainEvent;
import cumt.zongzuo.community.event.domain.DomainEventType;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Data
public class DomainEventOutbox {
    private Long id;
    private UUID eventId;
    private String aggregateType;
    private Long aggregateId;
    private Long aggregateVersion;
    private Long lifecycleEpoch;
    private String eventType;
    private Integer payloadVersion;
    private String payloadJson;
    private String dedupeKey;
    private LocalDateTime occurredAt;
    private String state;
    private Integer retryCount;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    private LocalDateTime failedAt;
    private boolean dispatchExhausted;

    public DomainEvent toEvent(ObjectMapper objectMapper) {
        try {
            return new DomainEvent(eventId, aggregateType, aggregateId,
                    aggregateVersion, lifecycleEpoch, DomainEventType.valueOf(eventType),
                    payloadVersion, objectMapper.readTree(payloadJson),
                    occurredAt.toInstant(ZoneOffset.UTC));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored domain event payload is invalid JSON", exception);
        }
    }
}
