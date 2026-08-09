package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.provider.AiProviderErrorReason;
import cumt.zongzuo.community.ai.provider.AiProviderException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AiMetrics {

    private final MeterRegistry registry;
    private final AiTokenUsageExtractor tokenUsageExtractor = new AiTokenUsageExtractor();
    private final Set<String> registeredCircuitGauges = ConcurrentHashMap.newKeySet();
    private final Map<MeterKey, Counter> counters = new ConcurrentHashMap<>();
    private final Map<MeterKey, Timer> timers = new ConcurrentHashMap<>();
    private final Map<MeterKey, DistributionSummary> summaries = new ConcurrentHashMap<>();

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRequest(AiCapabilityPolicy policy, String outcome,
                              Duration latency, Object result) {
        counter("ai.request.count", policy, outcome).increment();
        timer("ai.request.latency", policy, outcome).record(latency);
        if ("success".equals(outcome)) {
            tokenUsageExtractor.totalTokens(result).ifPresent(tokens ->
                    summary("ai.request.tokens", policy, outcome).record(tokens));
        }
    }

    public void recordQuotaRejected(AiCapabilityPolicy policy, String outcome) {
        counter("ai.quota.rejected", policy, outcome).increment();
    }

    public void recordBulkheadRejected(AiCapabilityPolicy policy) {
        counter("ai.bulkhead.rejected", policy, "rejected").increment();
    }

    public void recordProviderFailure(AiCapabilityPolicy policy, AiProviderException error) {
        String metric = switch (error.reason()) {
            case RATE_LIMITED -> "provider.429";
            case RETRYABLE_PROVIDER_FAILURE -> error.httpStatus()
                    .filter(status -> status >= 500 && status <= 599)
                    .map(status -> "provider.5xx")
                    .orElse(null);
            case TIMEOUT -> "provider.timeout";
            case AI_DISABLED, AI_UNAVAILABLE, CONNECTION_FAILURE,
                    NON_RETRYABLE_PROVIDER_FAILURE, MALFORMED_RESPONSE, EMPTY_RESPONSE -> null;
        };
        if (metric != null) {
            counter(metric, policy, null).increment();
        }
    }

    public void recordProviderTimeout(AiCapabilityPolicy policy) {
        counter("provider.timeout", policy, null).increment();
    }

    public void registerCircuitGauge(AiCapabilityPolicy policy, CircuitBreaker circuitBreaker) {
        String gaugeKey = policy.capability().name();
        if (registeredCircuitGauges.add(gaugeKey)) {
            Gauge.builder("ai.circuit.state", circuitBreaker,
                            circuit -> circuit.getState().getOrder())
                    .tags(baseTags(policy))
                    .register(registry);
        }
    }

    private static List<Tag> tags(AiCapabilityPolicy policy, String outcome) {
        return List.of(
                Tag.of("capability", policy.capability().name().toLowerCase(Locale.ROOT)),
                Tag.of("provider", policy.provider()),
                Tag.of("model", policy.model()),
                Tag.of("outcome", outcome));
    }

    private static List<Tag> baseTags(AiCapabilityPolicy policy) {
        return List.of(
                Tag.of("capability", policy.capability().name().toLowerCase(Locale.ROOT)),
                Tag.of("provider", policy.provider()),
                Tag.of("model", policy.model()));
    }

    private Counter counter(String name, AiCapabilityPolicy policy, String outcome) {
        MeterKey key = MeterKey.of(name, policy, outcome);
        return counters.computeIfAbsent(key, ignored -> Counter.builder(name)
                .tags(tagsFor(policy, outcome)).register(registry));
    }

    private Timer timer(String name, AiCapabilityPolicy policy, String outcome) {
        MeterKey key = MeterKey.of(name, policy, outcome);
        return timers.computeIfAbsent(key, ignored -> Timer.builder(name)
                .tags(tagsFor(policy, outcome)).register(registry));
    }

    private DistributionSummary summary(String name, AiCapabilityPolicy policy, String outcome) {
        MeterKey key = MeterKey.of(name, policy, outcome);
        return summaries.computeIfAbsent(key, ignored -> DistributionSummary.builder(name)
                .tags(tagsFor(policy, outcome)).register(registry));
    }

    private static List<Tag> tagsFor(AiCapabilityPolicy policy, String outcome) {
        return outcome == null ? baseTags(policy) : tags(policy, outcome);
    }

    private record MeterKey(String name, String capability, String provider, String model, String outcome) {

        private static MeterKey of(String name, AiCapabilityPolicy policy, String outcome) {
            return new MeterKey(name, policy.capability().name(), policy.provider(), policy.model(), outcome);
        }
    }
}
