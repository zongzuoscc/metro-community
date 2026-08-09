package cumt.zongzuo.community.ai.runtime;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RedisAiQuotaService implements AiQuotaService {

    private static final long SHANGHAI_OFFSET_SECONDS = 8 * 60 * 60;
    private static final long KEY_TTL_SECONDS = 2 * 24 * 60 * 60;
    private static final String QUOTA_SCRIPT = """
            local server_time = redis.call('TIME')
            local now = tonumber(server_time[1])
            local short_window = tonumber(ARGV[1])
            local short_limit = tonumber(ARGV[2])
            local daily_limit = tonumber(ARGV[3])
            local shanghai_offset = tonumber(ARGV[4])
            local key_ttl = tonumber(ARGV[5])

            local short_bucket = math.floor(now / short_window)
            local day_bucket = math.floor((now + shanghai_offset) / 86400)
            local saved_short_bucket = tonumber(redis.call('HGET', KEYS[1], 'short_bucket'))
            local saved_day_bucket = tonumber(redis.call('HGET', KEYS[1], 'day_bucket'))
            local short_count = tonumber(redis.call('HGET', KEYS[1], 'short_count')) or 0
            local day_count = tonumber(redis.call('HGET', KEYS[1], 'day_count')) or 0

            if saved_short_bucket ~= short_bucket then
                short_count = 0
            end
            if saved_day_bucket ~= day_bucket then
                day_count = 0
            end

            if short_count >= short_limit then
                local retry_after = ((short_bucket + 1) * short_window) - now
                return {0, 1, retry_after}
            end
            if day_count >= daily_limit then
                local retry_after = ((day_bucket + 1) * 86400) - (now + shanghai_offset)
                return {0, 2, retry_after}
            end

            redis.call('HSET', KEYS[1],
                'short_bucket', short_bucket,
                'short_count', short_count + 1,
                'day_bucket', day_bucket,
                'day_count', day_count + 1)
            redis.call('EXPIRE', KEYS[1], key_ttl)
            return {1, 0, 0}
            """;

    private final StringRedisTemplate redisTemplate;
    private final AiCapabilityPolicyResolver policyResolver;
    private final AiMetrics metrics;
    private final String namespace;
    private final RedisScript<List> script;

    public RedisAiQuotaService(StringRedisTemplate redisTemplate,
                               AiCapabilityPolicyResolver policyResolver,
                               AiMetrics metrics,
                               String namespace) {
        this(redisTemplate, policyResolver, metrics, namespace,
                new DefaultRedisScript<>(QUOTA_SCRIPT, List.class));
    }

    RedisAiQuotaService(StringRedisTemplate redisTemplate,
                        AiCapabilityPolicyResolver policyResolver,
                        AiMetrics metrics,
                        String namespace,
                        RedisScript<List> script) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("AI quota namespace must not be blank");
        }
        this.namespace = namespace;
        this.script = Objects.requireNonNull(script, "script");
    }

    @Override
    public void acquire(AiInvocationContext context) {
        Objects.requireNonNull(context, "context");
        AiCapabilityPolicy policy = policyResolver.resolve(context.capability());
        if (policy.shortWindowLimit() <= 0 || policy.dailyLimit() <= 0) {
            return;
        }
        if (context.userId() == null) {
            throw new AiExecutionException(AiExecutionErrorReason.INVALID_INVOCATION,
                    "User-scoped AI quota requires a user id");
        }
        long quotaWindowSeconds = policy.quotaWindow().toSeconds();
        if (quotaWindowSeconds <= 0) {
            throw new AiExecutionException(AiExecutionErrorReason.AGENT_RUNTIME_UNAVAILABLE,
                    "AI quota window is invalid");
        }

        final List result;
        try {
            result = redisTemplate.execute(script, List.of(quotaKey(context)),
                    Long.toString(quotaWindowSeconds),
                    Integer.toString(policy.shortWindowLimit()),
                    Integer.toString(policy.dailyLimit()),
                    Long.toString(SHANGHAI_OFFSET_SECONDS),
                    Long.toString(KEY_TTL_SECONDS));
        }
        catch (RuntimeException error) {
            throw unavailable(error);
        }
        if (result == null || result.size() != 3
                || !(result.get(0) instanceof Number allowed)
                || !(result.get(1) instanceof Number limitKind)
                || !(result.get(2) instanceof Number retryAfter)) {
            throw unavailable(null);
        }
        if (allowed.longValue() == 1L) {
            return;
        }
        if (allowed.longValue() != 0L || retryAfter.longValue() <= 0) {
            throw unavailable(null);
        }

        String outcome;
        String message;
        Duration maximum;
        if (limitKind.longValue() == 1L) {
            outcome = "short_window";
            message = "AI short-window quota exceeded";
            maximum = policy.quotaWindow();
        }
        else if (limitKind.longValue() == 2L) {
            outcome = "daily";
            message = "AI daily quota exceeded";
            maximum = Duration.ofDays(1);
        }
        else {
            throw unavailable(null);
        }
        Duration retry = Duration.ofSeconds(retryAfter.longValue());
        if (retry.compareTo(maximum) > 0) {
            throw unavailable(null);
        }
        metrics.recordQuotaRejected(policy, outcome);
        throw new AiExecutionException(AiExecutionErrorReason.QUOTA_EXCEEDED,
                message, null, retry);
    }

    String quotaKey(AiInvocationContext context) {
        AiCapabilityPolicy policy = policyResolver.resolve(context.capability());
        return namespace + ":{" + policy.quotaGroup().name().toLowerCase(Locale.ROOT)
                + ':' + context.userId() + '}';
    }

    private static AiExecutionException unavailable(Throwable cause) {
        return new AiExecutionException(AiExecutionErrorReason.AGENT_RUNTIME_UNAVAILABLE,
                "AI quota service is unavailable", cause);
    }
}
