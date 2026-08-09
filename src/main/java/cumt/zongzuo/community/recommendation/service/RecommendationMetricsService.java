package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.dto.RecommendationFeedResponse;
import cumt.zongzuo.community.recommendation.dto.RecommendationItem;
import cumt.zongzuo.community.recommendation.dto.RecommendationMode;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class RecommendationMetricsService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String KEY_PREFIX = "recommendation:metrics:";
    private static final long TTL_SECONDS = Duration.ofDays(40).toSeconds();
    private static final int DIAGNOSTIC_LOG_LIMIT = 5;
    private static final List<String> DELIVERY_SOURCES = List.of(
            "FOLLOW", "TAG", "SIMILAR", "EXPLORE", "CHRONOLOGICAL");
    private static final Set<String> DELIVERY_SOURCE_ALLOW_LIST = Set.copyOf(DELIVERY_SOURCES);
    private static final List<RecommendationEventType> EVENT_TYPES = List.of(
            RecommendationEventType.VIEW,
            RecommendationEventType.LIKE,
            RecommendationEventType.COLLECT,
            RecommendationEventType.COMMENT,
            RecommendationEventType.FOLLOW_AUTHOR);
    private static final Set<RecommendationEventType> EVENT_TYPE_ALLOW_LIST = EnumSet.copyOf(EVENT_TYPES);
    private static final DefaultRedisScript<Long> INCREMENT_AND_EXPIRE_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('INCRBY', KEYS[1], ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return value
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final AtomicInteger invalidSourceDiagnostics = new AtomicInteger();
    private final AtomicInteger writeFailureDiagnostics = new AtomicInteger();

    public RecommendationMetricsService(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void recordDeliveries(RecommendationFeedResponse response) {
        try {
            if (response == null || response.mode() == null || response.mode() == RecommendationMode.FALLBACK) {
                return;
            }
            List<RecommendationItem> items = response.items();
            if (items == null || items.isEmpty()) {
                return;
            }
            LocalDate date = LocalDate.now(clock.withZone(SHANGHAI));
            Map<String, Long> counts = new LinkedHashMap<>();
            if (response.mode() == RecommendationMode.COLD_START) {
                counts.put("CHRONOLOGICAL", (long) items.size());
            } else if (response.mode() == RecommendationMode.PERSONALIZED) {
                for (RecommendationItem item : items) {
                    String source = item == null ? null : item.source();
                    if (source == null || !DELIVERY_SOURCE_ALLOW_LIST.contains(source)) {
                        logInvalidSource();
                        continue;
                    }
                    counts.merge(source, 1L, Long::sum);
                }
            }
            counts.forEach((source, count) -> increment(deliveryKey(date, source), count));
        } catch (RuntimeException exception) {
            logWriteFailure("delivery");
        }
    }

    public void recordEvent(RecommendationEventCommand command) {
        try {
            if (command == null || command.eventType() == null || command.occurredAt() == null
                    || !EVENT_TYPE_ALLOW_LIST.contains(command.eventType())) {
                return;
            }
            LocalDate date = command.occurredAt().atZone(clock.getZone())
                    .withZoneSameInstant(SHANGHAI).toLocalDate();
            increment(eventKey(date, command.eventType()), 1L);
        } catch (RuntimeException exception) {
            logWriteFailure("event");
        }
    }

    public DailySnapshot dailySnapshot(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        try {
            Map<String, Long> deliveries = new LinkedHashMap<>();
            for (String source : DELIVERY_SOURCES) {
                deliveries.put(source, readCounter(deliveryKey(date, source)));
            }
            Map<String, Long> events = new LinkedHashMap<>();
            for (RecommendationEventType eventType : EVENT_TYPES) {
                events.put(eventType.name(), readCounter(eventKey(date, eventType)));
            }
            return new DailySnapshot(date, deliveries, events);
        } catch (RuntimeException exception) {
            throw new MetricsUnavailableException("Recommendation metrics unavailable for " + date, exception);
        }
    }

    private void increment(String key, long count) {
        if (count <= 0L) {
            return;
        }
        redisTemplate.execute(INCREMENT_AND_EXPIRE_SCRIPT, Collections.singletonList(key),
                Long.toString(count), Long.toString(TTL_SECONDS));
    }

    private long readCounter(String key) {
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null) {
            return 0L;
        }
        long value = Long.parseLong(raw);
        if (value < 0L) {
            throw new NumberFormatException("Recommendation metric counter must be non-negative");
        }
        return value;
    }

    private static String deliveryKey(LocalDate date, String source) {
        return KEY_PREFIX + date + ":delivery:" + source;
    }

    private static String eventKey(LocalDate date, RecommendationEventType eventType) {
        return KEY_PREFIX + date + ":event:" + eventType.name();
    }

    private void logInvalidSource() {
        int diagnostic = invalidSourceDiagnostics.getAndIncrement();
        if (diagnostic < DIAGNOSTIC_LOG_LIMIT) {
            log.warn("Ignoring invalid recommendation delivery metric source ({}/{})",
                    diagnostic + 1, DIAGNOSTIC_LOG_LIMIT);
        }
    }

    private void logWriteFailure(String category) {
        int diagnostic = writeFailureDiagnostics.getAndIncrement();
        if (diagnostic < DIAGNOSTIC_LOG_LIMIT) {
            log.warn("Recommendation {} metrics unavailable; telemetry was dropped ({}/{})",
                    category, diagnostic + 1, DIAGNOSTIC_LOG_LIMIT);
        }
    }

    public record DailySnapshot(
            LocalDate date,
            Map<String, Long> deliveryCounts,
            Map<String, Long> eventCounts) {

        public DailySnapshot {
            Objects.requireNonNull(date, "date must not be null");
            deliveryCounts = Collections.unmodifiableMap(new LinkedHashMap<>(deliveryCounts));
            eventCounts = Collections.unmodifiableMap(new LinkedHashMap<>(eventCounts));
        }
    }

    public static class MetricsUnavailableException extends RuntimeException {
        public MetricsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
