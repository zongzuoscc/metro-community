package cumt.zongzuo.community.event.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DomainEvent(
        UUID eventId,
        String aggregateType,
        long aggregateId,
        long aggregateVersion,
        long lifecycleEpoch,
        DomainEventType eventType,
        int payloadVersion,
        JsonNode payload,
        Instant occurredAt) {

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        requireText(aggregateType, "aggregateType");
        if (aggregateId <= 0 || aggregateVersion < 0 || lifecycleEpoch < 0 || payloadVersion <= 0) {
            throw new IllegalArgumentException("event identifiers and versions are out of range");
        }
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
