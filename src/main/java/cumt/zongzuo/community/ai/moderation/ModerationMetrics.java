package cumt.zongzuo.community.ai.moderation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;

@Component
public final class ModerationMetrics {

    private final Clock clock;
    private final EnumMap<FallbackOutcome, Counter> fallbackCounters = new EnumMap<>(FallbackOutcome.class);
    private final DistributionSummary pendingAge;

    public ModerationMetrics(MeterRegistry meterRegistry, Clock clock) {
        this.clock = clock;
        for (FallbackOutcome outcome : FallbackOutcome.values()) {
            fallbackCounters.put(outcome, Counter.builder("moderation.fallback.count")
                    .tag("outcome", outcome.tagValue)
                    .register(meterRegistry));
        }
        pendingAge = DistributionSummary.builder("moderation.pending.age")
                .baseUnit("seconds")
                .tag("route", "legacy")
                .register(meterRegistry);
    }

    void record(FallbackOutcome outcome, LocalDateTime submittedAt) {
        fallbackCounters.get(outcome).increment();
        if (outcome == FallbackOutcome.MANUAL_PENDING && submittedAt != null) {
            long ageSeconds = Math.max(0L,
                    Duration.between(submittedAt.atZone(clock.getZone()).toInstant(), clock.instant()).toSeconds());
            pendingAge.record(ageSeconds);
        }
    }

    enum FallbackOutcome {
        MANUAL_PENDING("manual_pending"),
        IGNORED_DELETED("ignored_deleted"),
        IGNORED_NOT_PENDING("ignored_not_pending");

        private final String tagValue;

        FallbackOutcome(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
