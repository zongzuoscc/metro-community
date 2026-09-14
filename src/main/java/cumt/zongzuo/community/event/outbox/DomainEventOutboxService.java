package cumt.zongzuo.community.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.event.domain.DomainEventType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

public interface DomainEventOutboxService {
    UUID append(String aggregateType, long aggregateId, long aggregateVersion,
                long lifecycleEpoch, DomainEventType type, int payloadVersion,
                JsonNode payload, String dedupeKey);
}

@Service
class DefaultDomainEventOutboxService implements DomainEventOutboxService {

    private final DomainEventOutboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher applicationEvents;

    @Autowired
    DefaultDomainEventOutboxService(DomainEventOutboxMapper mapper, ObjectMapper objectMapper,
                                    org.springframework.context.ApplicationEventPublisher applicationEvents) {
        this(mapper, objectMapper, Clock.systemUTC(), applicationEvents);
    }

    DefaultDomainEventOutboxService(DomainEventOutboxMapper mapper, ObjectMapper objectMapper, Clock clock,
                                    org.springframework.context.ApplicationEventPublisher applicationEvents) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.applicationEvents = applicationEvents;
    }

    @Override
    @Transactional
    public UUID append(String aggregateType, long aggregateId, long aggregateVersion,
                       long lifecycleEpoch, DomainEventType type, int payloadVersion,
                       JsonNode payload, String dedupeKey) {
        validate(aggregateType, aggregateId, aggregateVersion, lifecycleEpoch,
                type, payloadVersion, payload, dedupeKey);
        String canonicalDedupeKey = aggregateType + ":" + aggregateId + ":"
                + lifecycleEpoch + ":" + aggregateVersion + ":" + type.name();
        if (!canonicalDedupeKey.equals(dedupeKey)) {
            throw new IllegalArgumentException("dedupeKey must equal the canonical aggregate event key");
        }
        Instant now = clock.instant();
        DomainEventOutbox row = new DomainEventOutbox();
        row.setEventId(UUID.randomUUID());
        row.setAggregateType(aggregateType);
        row.setAggregateId(aggregateId);
        row.setAggregateVersion(aggregateVersion);
        row.setLifecycleEpoch(lifecycleEpoch);
        row.setEventType(type.name());
        row.setPayloadVersion(payloadVersion);
        row.setPayloadJson(writePayload(payload));
        row.setDedupeKey(dedupeKey);
        row.setOccurredAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        mapper.insertIdempotently(row);
        DomainEventOutbox stored = mapper.selectByDedupeKey(dedupeKey);
        if (!sameLogicalEvent(row, stored)) {
            throw new DomainEventConflictException(
                    "dedupe key already belongs to a different domain event");
        }
        // 本地监听器只在提交后尝试快速失效；崩溃或 Redis 故障仍由已落库的 Outbox 补送。
        // 发布 Spring 事件不替代 RabbitMQ，也不改变其他领域事件的持久化语义。
        applicationEvents.publishEvent(stored.toEvent(objectMapper));
        return stored.getEventId();
    }

    private String writePayload(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("payload cannot be serialized", exception);
        }
    }

    private static void validate(String aggregateType, long aggregateId, long aggregateVersion,
                                 long lifecycleEpoch, DomainEventType type, int payloadVersion,
                                 JsonNode payload, String dedupeKey) {
        if (aggregateType == null || aggregateType.isBlank() || aggregateType.length() > 64) {
            throw new IllegalArgumentException("aggregateType must contain 1..64 characters");
        }
        if (aggregateId <= 0 || aggregateVersion < 0 || lifecycleEpoch < 0 || payloadVersion <= 0) {
            throw new IllegalArgumentException("event identifiers and versions are out of range");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (dedupeKey == null || dedupeKey.isBlank() || dedupeKey.length() > 190) {
            throw new IllegalArgumentException("dedupeKey must contain 1..190 characters");
        }
    }

    private boolean sameLogicalEvent(DomainEventOutbox requested, DomainEventOutbox stored) {
        try {
            return requested.getAggregateType().equals(stored.getAggregateType())
                    && requested.getAggregateId().equals(stored.getAggregateId())
                    && requested.getAggregateVersion().equals(stored.getAggregateVersion())
                    && requested.getLifecycleEpoch().equals(stored.getLifecycleEpoch())
                    && requested.getEventType().equals(stored.getEventType())
                    && requested.getPayloadVersion().equals(stored.getPayloadVersion())
                    && objectMapper.readTree(requested.getPayloadJson())
                    .equals(objectMapper.readTree(stored.getPayloadJson()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored domain event payload is invalid", exception);
        }
    }
}
