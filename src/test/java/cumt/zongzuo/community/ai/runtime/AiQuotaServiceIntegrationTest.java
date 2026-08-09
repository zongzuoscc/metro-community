package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiCapability;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AiQuotaServiceIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void connect() {
        connectionFactory = connectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379), Duration.ofSeconds(2));
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (meterRegistry != null) {
            meterRegistry.close();
        }
    }

    @Test
    void atomicallyAllowsEightAgentCallsPerMinuteAndHydeSharesThatQuota() throws Exception {
        AiCapabilityPolicyResolver resolver = defaultResolver();
        RedisAiQuotaService quota = quota(resolver);
        quota.acquire(context(AiCapability.HYDE, 501L));
        int callers = 19;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger(1);
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        quota.acquire(context(AiCapability.AGENT, 501L));
                        accepted.incrementAndGet();
                    }
                    catch (AiExecutionException error) {
                        assertThat(error.reason()).isEqualTo(AiExecutionErrorReason.QUOTA_EXCEEDED);
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(accepted).hasValue(8);
        assertThat(rejected).hasValue(12);
        assertThat(meterRegistry.get("ai.quota.rejected").tag("outcome", "short_window")
                .counter().count()).isEqualTo(12.0);
    }

    @Test
    void enforcesSummaryAndWritingFixedWindowsFromApprovedConfiguration() {
        AiCapabilityPolicyResolver resolver = defaultResolver();
        RedisAiQuotaService quota = quota(resolver);

        acquire(quota, AiCapability.ARTICLE_SUMMARY, 601L, 5);
        assertQuota("short-window", Duration.ofMinutes(1),
                () -> quota.acquire(context(AiCapability.ARTICLE_SUMMARY, 601L)));
        acquire(quota, AiCapability.WRITING, 602L, 10);
        assertQuota("short-window", Duration.ofMinutes(10),
                () -> quota.acquire(context(AiCapability.WRITING, 602L)));

        assertThat(resolver.resolve(AiCapability.WRITING).quotaWindow()).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void enforcesSummaryAndWritingDailyLimitsFromApprovedConfiguration() {
        AiCapabilityPolicyResolver defaults = defaultResolver();
        AiCapabilityPolicy summary = withRaisedShortLimit(defaults.resolve(AiCapability.ARTICLE_SUMMARY));
        AiCapabilityPolicy writing = withRaisedShortLimit(defaults.resolve(AiCapability.WRITING));
        Map<AiCapability, AiCapabilityPolicy> policies = new EnumMap<>(AiCapability.class);
        policies.put(AiCapability.ARTICLE_SUMMARY, summary);
        policies.put(AiCapability.WRITING, writing);
        RedisAiQuotaService quota = quota(new AiCapabilityPolicyResolver(
                policies, new MetroAiProperties.RuntimeProperties()));

        acquire(quota, AiCapability.ARTICLE_SUMMARY, 651L, 30);
        assertQuota("daily", Duration.ofDays(1),
                () -> quota.acquire(context(AiCapability.ARTICLE_SUMMARY, 651L)));
        acquire(quota, AiCapability.WRITING, 652L, 60);
        assertQuota("daily", Duration.ofDays(1),
                () -> quota.acquire(context(AiCapability.WRITING, 652L)));

        assertThat(summary.dailyLimit()).isEqualTo(30);
        assertThat(writing.dailyLimit()).isEqualTo(60);
    }

    @Test
    void systemCapabilitiesWithZeroUserLimitsBypassRedisAndDoNotRequireAUserId() {
        RedisAiQuotaService quota = quota(defaultResolver());

        quota.acquire(new AiInvocationContext(AiCapability.MODERATION, null, "system-task",
                10, Instant.now().plusSeconds(5), true));
    }

    @Test
    void shortWindowRejectionDoesNotConsumeDailyQuota() {
        AiCapabilityPolicy policy = new AiCapabilityPolicy(AiCapability.AGENT, AiCapability.AGENT,
                true, 4_000, 1, 2, Duration.ofMinutes(1), Duration.ofSeconds(1), 1,
                "deepseek", "test-model");
        RedisAiQuotaService quota = quota(resolver(policy));
        AiInvocationContext context = context(AiCapability.AGENT, 701L);

        quota.acquire(context);
        assertQuota("short-window", Duration.ofMinutes(1), () -> quota.acquire(context));
        resetShortBucket(quota.quotaKey(context));
        quota.acquire(context);
        resetShortBucket(quota.quotaKey(context));

        assertQuota("daily", Duration.ofDays(1), () -> quota.acquire(context));
        assertThat(redisTemplate.opsForHash().get(quota.quotaKey(context), "day_count")).isEqualTo("2");
    }

    @Test
    void rejectsTheOneHundredAndFirstDailyAgentCallWhenShortWindowIsRaised() {
        AiCapabilityPolicy policy = new AiCapabilityPolicy(AiCapability.AGENT, AiCapability.AGENT,
                true, 4_000, 1_000, 100, Duration.ofMinutes(1), Duration.ofSeconds(1), 1,
                "deepseek", "test-model");
        RedisAiQuotaService quota = quota(resolver(policy));

        acquire(quota, AiCapability.AGENT, 801L, 100);

        assertQuota("daily", Duration.ofDays(1),
                () -> quota.acquire(context(AiCapability.AGENT, 801L)));
    }

    @Test
    void resetsStaleBucketsAndKeepsBoundedTtlAndServerDerivedRetryAfter() {
        AiCapabilityPolicy policy = new AiCapabilityPolicy(AiCapability.AGENT, AiCapability.AGENT,
                true, 4_000, 1, 2, Duration.ofMinutes(10), Duration.ofSeconds(1), 1,
                "deepseek", "test-model");
        RedisAiQuotaService quota = quota(resolver(policy));
        AiInvocationContext context = context(AiCapability.AGENT, 901L);
        String key = quota.quotaKey(context);
        redisTemplate.opsForHash().putAll(key, Map.of(
                "short_bucket", "-1", "short_count", "99",
                "day_bucket", "-1", "day_count", "99"));

        quota.acquire(context);

        assertThat(redisTemplate.opsForHash().get(key, "short_count")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(key, "day_count")).isEqualTo("1");
        Long ttlSeconds = redisTemplate.getExpire(key);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(Duration.ofDays(2).toSeconds());
        long redisNow = redisTimeSeconds();
        long expected = 600 - Math.floorMod(redisNow, 600);
        assertThatThrownBy(() -> quota.acquire(context))
                .isInstanceOfSatisfying(AiExecutionException.class, error -> {
                    assertThat(error.reason()).isEqualTo(AiExecutionErrorReason.QUOTA_EXCEEDED);
                    assertThat(error.retryAfter()).isPresent();
                    assertThat(error.retryAfter().orElseThrow().toSeconds())
                            .isBetween(Math.max(1, expected - 1), expected + 1);
                });
    }

    @Test
    void failsClosedForNullMalformedAndRealConnectionFailures() {
        AiCapabilityPolicyResolver resolver = defaultResolver();
        RedisAiQuotaService nullResult = new RedisAiQuotaService(redisTemplate, resolver,
                new AiMetrics(meterRegistry), namespace(), new DefaultRedisScript<>("return nil", List.class));
        RedisAiQuotaService malformedResult = new RedisAiQuotaService(redisTemplate, resolver,
                new AiMetrics(meterRegistry), namespace(), new DefaultRedisScript<>("return {1}", List.class));

        assertRuntimeUnavailable(() -> nullResult.acquire(context(AiCapability.AGENT, 1_001L)));
        assertRuntimeUnavailable(() -> malformedResult.acquire(context(AiCapability.AGENT, 1_002L)));

        LettuceConnectionFactory unavailableFactory = connectionFactory(
                "127.0.0.1", 1, Duration.ofMillis(150));
        try {
            StringRedisTemplate unavailableRedis = new StringRedisTemplate(unavailableFactory);
            unavailableRedis.afterPropertiesSet();
            RedisAiQuotaService unavailable = new RedisAiQuotaService(unavailableRedis, resolver,
                    new AiMetrics(meterRegistry), namespace());
            assertRuntimeUnavailable(() -> unavailable.acquire(context(AiCapability.AGENT, 1_003L)));
        }
        finally {
            unavailableFactory.destroy();
        }
    }

    private RedisAiQuotaService quota(AiCapabilityPolicyResolver resolver) {
        return new RedisAiQuotaService(redisTemplate, resolver, new AiMetrics(meterRegistry), namespace());
    }

    private static AiCapabilityPolicyResolver defaultResolver() {
        MetroAiProperties properties = new MetroAiProperties();
        properties.setEnabled(true);
        properties.getAgent().setEnabled(true);
        properties.getWriting().setEnabled(true);
        return new AiCapabilityPolicyResolver(properties);
    }

    private static AiCapabilityPolicyResolver resolver(AiCapabilityPolicy policy) {
        MetroAiProperties.RuntimeProperties runtime = new MetroAiProperties.RuntimeProperties();
        return new AiCapabilityPolicyResolver(Map.of(policy.capability(), policy), runtime);
    }

    private static AiCapabilityPolicy withRaisedShortLimit(AiCapabilityPolicy policy) {
        return new AiCapabilityPolicy(policy.capability(), policy.quotaGroup(), policy.enabled(),
                policy.maxInputCharacters(), 1_000, policy.dailyLimit(), policy.quotaWindow(),
                policy.timeout(), policy.maxConcurrency(), policy.provider(), policy.model());
    }

    private static void acquire(AiQuotaService quota, AiCapability capability, long userId, int count) {
        for (int attempt = 0; attempt < count; attempt++) {
            quota.acquire(context(capability, userId));
        }
    }

    private void resetShortBucket(String key) {
        redisTemplate.opsForHash().put(key, "short_bucket", "-1");
        redisTemplate.opsForHash().put(key, "short_count", "0");
    }

    private long redisTimeSeconds() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                "local now = redis.call('TIME'); return tonumber(now[1])", Long.class);
        Long value = redisTemplate.execute(script, List.of());
        assertThat(value).isNotNull();
        return value;
    }

    private static void assertQuota(String kind, Duration maximumRetryAfter, Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(AiExecutionException.class, error -> {
                    assertThat(error.reason()).isEqualTo(AiExecutionErrorReason.QUOTA_EXCEEDED);
                    assertThat(error.getMessage()).contains(kind);
                    assertThat(error.retryAfter()).isPresent();
                    assertThat(error.retryAfter().orElseThrow())
                            .isPositive().isLessThanOrEqualTo(maximumRetryAfter);
                });
    }

    private static void assertRuntimeUnavailable(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(AiExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(AiExecutionErrorReason.AGENT_RUNTIME_UNAVAILABLE));
    }

    private static AiInvocationContext context(AiCapability capability, long userId) {
        return new AiInvocationContext(capability, userId, "quota-" + userId,
                10, Instant.now().plusSeconds(5), false);
    }

    private static String namespace() {
        return "test:ai:quota:" + UUID.randomUUID();
    }

    private static LettuceConnectionFactory connectionFactory(String host, int port, Duration timeout) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(timeout)
                .shutdownTimeout(Duration.ZERO)
                .clientOptions(clientOptions)
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
        factory.afterPropertiesSet();
        return factory;
    }
}
