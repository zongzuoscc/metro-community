package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiProviderErrorReason;
import cumt.zongzuo.community.ai.provider.AiProviderException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class AiCapabilityExecutorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AiMetrics metrics = new AiMetrics(meterRegistry);
    private DefaultAiCapabilityExecutor executor;

    @AfterEach
    void closeExecutor() {
        if (executor != null) {
            executor.close();
        }
        meterRegistry.close();
    }

    @Test
    void rejectsOversizeAndExpiredInvocationBeforeQuotaOrOperation() {
        AtomicInteger quotaCalls = new AtomicInteger();
        AtomicInteger operationCalls = new AtomicInteger();
        executor = executor(defaultPolicies(), context -> quotaCalls.incrementAndGet(), runtimeDefaults());

        assertReason(AiExecutionErrorReason.INPUT_TOO_LARGE, () -> executor.execute(
                context(AiCapability.AGENT, 4_001, Instant.now().plusSeconds(5), false),
                () -> {
                    operationCalls.incrementAndGet();
                    return "unexpected";
                }));
        assertReason(AiExecutionErrorReason.DEADLINE_EXCEEDED, () -> executor.execute(
                context(AiCapability.AGENT, 1, Instant.now().minusSeconds(1), false),
                () -> {
                    operationCalls.incrementAndGet();
                    return "unexpected";
                }));

        assertThat(quotaCalls).hasValue(0);
        assertThat(operationCalls).hasValue(0);
    }

    @Test
    void acquiresQuotaOnceAndLimitsInteractiveAndBackgroundTotalAttempts() {
        AtomicInteger quotaCalls = new AtomicInteger();
        AtomicInteger interactiveAttempts = new AtomicInteger();
        AtomicInteger backgroundAttempts = new AtomicInteger();
        executor = executor(defaultPolicies(), context -> quotaCalls.incrementAndGet(), runtimeDefaults());

        assertThatThrownBy(() -> executor.execute(
                context(AiCapability.AGENT, 10, Instant.now().plusSeconds(5), false),
                () -> failTransiently(interactiveAttempts, 3)))
                .isInstanceOf(AiProviderException.class);
        assertThatThrownBy(() -> executor.execute(
                context(AiCapability.ARTICLE_SUMMARY, 10, Instant.now().plusSeconds(5), true),
                () -> failTransiently(backgroundAttempts, 4)))
                .isInstanceOf(AiProviderException.class);

        assertThat(interactiveAttempts).hasValue(2);
        assertThat(backgroundAttempts).hasValue(3);
        assertThat(quotaCalls).hasValue(2);
    }

    @Test
    void quotaFailureStopsBeforeOperationAndIsNeverRetried() {
        AtomicInteger quotaCalls = new AtomicInteger();
        AtomicInteger operationCalls = new AtomicInteger();
        executor = executor(defaultPolicies(), context -> {
            quotaCalls.incrementAndGet();
            throw new AiExecutionException(AiExecutionErrorReason.AGENT_RUNTIME_UNAVAILABLE,
                    "quota unavailable");
        }, runtimeDefaults());

        assertReason(AiExecutionErrorReason.AGENT_RUNTIME_UNAVAILABLE, () -> executor.execute(
                context(AiCapability.AGENT, 10, Instant.now().plusSeconds(5), false), () -> {
                    operationCalls.incrementAndGet();
                    return "unexpected";
                }));

        assertThat(quotaCalls).hasValue(1);
        assertThat(operationCalls).hasValue(0);
    }

    @Test
    void retriesOnlyTypedConnectionRateLimitAndSelectedProviderFailures() {
        for (AiProviderException error : new AiProviderException[]{
                provider(AiProviderErrorReason.CONNECTION_FAILURE, null),
                provider(AiProviderErrorReason.RATE_LIMITED, 429),
                provider(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 500),
                provider(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 502),
                provider(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 503),
                provider(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 504)
        }) {
            AtomicInteger attempts = new AtomicInteger();
            executor = executor(defaultPolicies(), context -> { }, runtimeDefaults());

            String result = executor.execute(context(AiCapability.AGENT, 10,
                    Instant.now().plusSeconds(5), false), () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw error;
                }
                return "ok";
            });

            assertThat(result).isEqualTo("ok");
            assertThat(attempts).hasValue(2);
            executor.close();
            executor = null;
        }
    }

    @ParameterizedTest
    @EnumSource(value = AiProviderErrorReason.class, names = {
            "AI_DISABLED", "AI_UNAVAILABLE", "TIMEOUT", "NON_RETRYABLE_PROVIDER_FAILURE",
            "MALFORMED_RESPONSE", "EMPTY_RESPONSE"
    })
    void doesNotRetryNonRetryableTypedProviderFailures(AiProviderErrorReason reason) {
        AtomicInteger attempts = new AtomicInteger();
        executor = executor(defaultPolicies(), context -> { }, runtimeDefaults());

        assertThatThrownBy(() -> executor.execute(
                context(AiCapability.AGENT, 10, Instant.now().plusSeconds(5), false), () -> {
                    attempts.incrementAndGet();
                    throw provider(reason, reason == AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE ? 400 : null);
                })).isInstanceOf(AiProviderException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void defensivelyDoesNotRetryAnUnselectedFiveHundredStatus() {
        AtomicInteger attempts = new AtomicInteger();
        executor = executor(defaultPolicies(), context -> { }, runtimeDefaults());

        assertThatThrownBy(() -> executor.execute(
                context(AiCapability.AGENT, 10, Instant.now().plusSeconds(5), false), () -> {
                    attempts.incrementAndGet();
                    throw provider(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 501);
                })).isInstanceOf(AiProviderException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void shortAbsoluteDeadlinePreventsASecondAttempt() {
        Instant startedAt = Instant.parse("2026-08-10T00:00:00Z");
        Instant callerDeadline = startedAt.plusMillis(20);
        Clock clock = new SequenceClock(List.of(
                startedAt,
                startedAt,
                startedAt,
                callerDeadline.minusMillis(10),
                callerDeadline.minusNanos(1)));
        AtomicInteger attempts = new AtomicInteger();
        MetroAiProperties.RuntimeProperties runtime = runtimeDefaults();
        runtime.setRetryDelay(Duration.ofMillis(10));
        AiCapabilityPolicyResolver resolver = new AiCapabilityPolicyResolver(defaultPolicies(), runtime);
        executor = new DefaultAiCapabilityExecutor(resolver, context -> { }, metrics, clock);

        assertReason(AiExecutionErrorReason.DEADLINE_EXCEEDED, () -> executor.execute(
                context(AiCapability.AGENT, 10, callerDeadline, false), () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw provider(AiProviderErrorReason.CONNECTION_FAILURE, null);
                    }
                    new CountDownLatch(1).await();
                    return "unexpected";
                }));

        assertThat(attempts).hasValue(1);
    }

    @Test
    void backgroundRetryBackoffIsExponentialAndCappedByTheEffectiveDeadline() {
        MetroAiProperties.RuntimeProperties runtime = runtimeDefaults();
        runtime.setRetryDelay(Duration.ofMillis(100));
        Clock clock = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), java.time.ZoneOffset.UTC);
        AiCapabilityPolicyResolver resolver = new AiCapabilityPolicyResolver(defaultPolicies(), runtime);
        executor = new DefaultAiCapabilityExecutor(resolver, context -> { }, metrics, clock);
        Instant farDeadline = clock.instant().plusSeconds(1);

        assertThat(executor.retryIntervalMillis(farDeadline, true, 1)).isEqualTo(100);
        assertThat(executor.retryIntervalMillis(farDeadline, true, 2)).isEqualTo(200);
        assertThat(executor.retryIntervalMillis(clock.instant().plusMillis(150), true, 2)).isEqualTo(150);
        assertThat(executor.retryIntervalMillis(farDeadline, false, 2)).isEqualTo(100);
    }

    @Test
    void timeLimiterCancelsAndInterruptsTheCapabilityWorker() {
        Map<AiCapability, AiCapabilityPolicy> policies = defaultPolicies();
        policies.put(AiCapability.AGENT, policy(AiCapability.AGENT, 4_000, Duration.ofMillis(60), 1));
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        executor = executor(policies, context -> { }, runtimeDefaults());

        assertReason(AiExecutionErrorReason.TIMEOUT, () -> executor.execute(
                context(AiCapability.AGENT, 10, Instant.now().plusSeconds(2), false), () -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    }
                    catch (InterruptedException error) {
                        interrupted.set(true);
                        throw error;
                    }
                    return "unexpected";
                }));

        assertThat(entered.getCount()).isZero();
        await().atMost(Duration.ofSeconds(2)).untilTrue(interrupted);
    }

    @Test
    void observedTimeoutCompletesTheStartedAttemptExactlyOnceAndDiscardsLateResult() {
        Map<AiCapability, AiCapabilityPolicy> policies = defaultPolicies();
        policies.put(AiCapability.AGENT, policy(AiCapability.AGENT, 4_000,
                Duration.ofMillis(60), 1));
        AtomicInteger began = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<Throwable> completionError = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor = executor(policies, context -> { }, runtimeDefaults());

        assertReason(AiExecutionErrorReason.TIMEOUT, () -> executor.execute(
                context(AiCapability.AGENT, 10, Instant.now().plusSeconds(2), false),
                new AiCapabilityExecutor.AttemptObserver<Integer, String>() {
                    @Override
                    public Integer begin() {
                        return began.incrementAndGet();
                    }

                    @Override
                    public void complete(Integer attempt, String result, Throwable error) {
                        completed.incrementAndGet();
                        completionError.set(error);
                    }
                }, attempt -> {
                    entered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = release.await(2, TimeUnit.SECONDS);
                        }
                        catch (InterruptedException ignored) {
                            // Model a transport that returns after cancellation.
                        }
                    }
                    return "late";
                }));

        assertThat(entered.getCount()).isZero();
        assertThat(began).hasValue(1);
        assertThat(completed).hasValue(1);
        assertThat(completionError.get()).isInstanceOf(AiExecutionException.class);
        release.countDown();
        await().during(Duration.ofMillis(150)).atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(completed).hasValue(1));
    }

    @Test
    void saturatedLaneRejectsObservedInvocationWithoutBeginningAnAttempt() throws Exception {
        Map<AiCapability, AiCapabilityPolicy> policies = defaultPolicies();
        policies.put(AiCapability.AGENT, policy(AiCapability.AGENT, 4_000,
                Duration.ofSeconds(3), 1));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger began = new AtomicInteger();
        ExecutorService callers = Executors.newSingleThreadExecutor();
        executor = executor(policies, context -> { }, runtimeDefaults());
        try {
            Future<String> active = callers.submit(() -> executor.execute(
                    context(AiCapability.AGENT, 10, Instant.now().plusSeconds(5), false), () -> {
                        entered.countDown();
                        release.await();
                        return "active";
                    }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertReason(AiExecutionErrorReason.BULKHEAD_FULL, () -> executor.execute(
                    context(AiCapability.AGENT, 10, Instant.now().plusSeconds(1), false),
                    new AiCapabilityExecutor.AttemptObserver<Integer, String>() {
                        @Override
                        public Integer begin() {
                            return began.incrementAndGet();
                        }

                        @Override
                        public void complete(Integer attempt, String result, Throwable error) {
                        }
                    }, attempt -> "unexpected"));

            assertThat(began).hasValue(0);
            release.countDown();
            assertThat(active.get(2, TimeUnit.SECONDS)).isEqualTo("active");
        }
        finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void observedAuditFailureWinsOverConcurrentTimeoutInsteadOfBeingLost() throws Exception {
        Map<AiCapability, AiCapabilityPolicy> policies = defaultPolicies();
        policies.put(AiCapability.AGENT, policy(AiCapability.AGENT, 4_000,
                Duration.ofMillis(60), 1));
        CountDownLatch auditEntered = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        ExecutorService releaser = Executors.newSingleThreadExecutor();
        executor = executor(policies, context -> { }, runtimeDefaults());
        try {
            releaser.submit(() -> {
                auditEntered.await(2, TimeUnit.SECONDS);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(120));
                releaseAudit.countDown();
                return null;
            });

            assertThatThrownBy(() -> executor.execute(
                    context(AiCapability.AGENT, 10, Instant.now().plusSeconds(2), false),
                    new AiCapabilityExecutor.AttemptObserver<Integer, String>() {
                        @Override
                        public Integer begin() {
                            return 1;
                        }

                        @Override
                        public void complete(Integer attempt, String result, Throwable error) {
                            completions.incrementAndGet();
                            auditEntered.countDown();
                            try {
                                releaseAudit.await(2, TimeUnit.SECONDS);
                            }
                            catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            throw new IllegalStateException("audit write failed");
                        }
                    }, attempt -> "ok"))
                    .isInstanceOf(AiExecutionException.class)
                    .satisfies(error -> assertThat(error)
                            .hasRootCauseMessage("audit write failed"));

            assertThat(completions).hasValue(1);
        }
        finally {
            releaseAudit.countDown();
            releaser.shutdownNow();
        }
    }

    @Test
    void observedRetriesCompleteOneLifecycleForEachRealProviderCall() {
        AtomicInteger began = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        List<Throwable> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();
        executor = executor(defaultPolicies(), context -> { }, runtimeDefaults());

        String result = executor.execute(context(AiCapability.AGENT, 10,
                        Instant.now().plusSeconds(2), false),
                new AiCapabilityExecutor.AttemptObserver<Integer, String>() {
                    @Override
                    public Integer begin() {
                        return began.incrementAndGet();
                    }

                    @Override
                    public void complete(Integer attempt, String value, Throwable error) {
                        outcomes.add(error);
                    }
                }, attempt -> {
                    if (providerCalls.incrementAndGet() == 1) {
                        throw provider(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 503);
                    }
                    return "ok";
                });

        assertThat(result).isEqualTo("ok");
        assertThat(providerCalls).hasValue(2);
        assertThat(began).hasValue(2);
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0)).isInstanceOf(AiProviderException.class);
        assertThat(outcomes.get(1)).isNull();
    }

    @Test
    void observedBackgroundExhaustionAuditsAllThreeProviderCalls() {
        AtomicInteger began = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        executor = executor(defaultPolicies(), context -> { }, runtimeDefaults());

        assertThatThrownBy(() -> executor.execute(context(AiCapability.ARTICLE_SUMMARY, 10,
                        Instant.now().plusSeconds(2), true),
                new AiCapabilityExecutor.AttemptObserver<Integer, String>() {
                    @Override
                    public Integer begin() {
                        return began.incrementAndGet();
                    }

                    @Override
                    public void complete(Integer attempt, String result, Throwable error) {
                        completions.incrementAndGet();
                        assertThat(error).isInstanceOf(AiProviderException.class);
                    }
                }, attempt -> {
                    providerCalls.incrementAndGet();
                    throw provider(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 503);
                })).isInstanceOf(AiProviderException.class);

        assertThat(providerCalls).hasValue(3);
        assertThat(began).hasValue(3);
        assertThat(completions).hasValue(3);
    }

    @Test
    void observedAuditFailureStopsRetryBeforeAnotherProviderCall() {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        executor = executor(defaultPolicies(), context -> { }, runtimeDefaults());

        assertThatThrownBy(() -> executor.execute(context(AiCapability.ARTICLE_SUMMARY, 10,
                        Instant.now().plusSeconds(2), true),
                new AiCapabilityExecutor.AttemptObserver<Integer, String>() {
                    @Override
                    public Integer begin() {
                        return 1;
                    }

                    @Override
                    public void complete(Integer attempt, String result, Throwable error) {
                        completions.incrementAndGet();
                        throw new IllegalStateException("audit persistence failed");
                    }
                }, attempt -> {
                    providerCalls.incrementAndGet();
                    throw provider(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 503);
                }))
                .isInstanceOf(AiExecutionException.class)
                .hasRootCauseMessage("audit persistence failed");

        assertThat(providerCalls).hasValue(1);
        assertThat(completions).hasValue(1);
    }

    @Test
    void callerInterruptCancelsWorkerAndPreservesCallerInterruptStatus() throws Exception {
        AtomicBoolean workerInterrupted = new AtomicBoolean();
        AtomicBoolean callerInterrupted = new AtomicBoolean();
        AtomicReference<AiExecutionErrorReason> reason = new AtomicReference<>();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        CountDownLatch workerEntered = new CountDownLatch(1);
        ExecutorService callers = Executors.newSingleThreadExecutor();
        executor = executor(defaultPolicies(), context -> { }, runtimeDefaults());
        try {
            Future<?> invocation = callers.submit(() -> {
                callerThread.set(Thread.currentThread());
                try {
                    executor.execute(context(AiCapability.AGENT, 10,
                            Instant.now().plusSeconds(5), false), () -> {
                        workerEntered.countDown();
                        try {
                            new CountDownLatch(1).await();
                        }
                        catch (InterruptedException error) {
                            workerInterrupted.set(true);
                            throw error;
                        }
                        return "unexpected";
                    });
                }
                catch (AiExecutionException error) {
                    reason.set(error.reason());
                    callerInterrupted.set(Thread.currentThread().isInterrupted());
                }
            });

            assertThat(workerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            callerThread.get().interrupt();
            invocation.get(2, TimeUnit.SECONDS);

            assertThat(reason).hasValue(AiExecutionErrorReason.CANCELLED);
            assertThat(callerInterrupted).isTrue();
            await().atMost(Duration.ofSeconds(2)).untilTrue(workerInterrupted);
        }
        finally {
            callers.shutdownNow();
        }
    }

    @Test
    void saturationRejectsImmediatelyWithoutCrossCapabilityStarvation() throws Exception {
        Map<AiCapability, AiCapabilityPolicy> policies = defaultPolicies();
        policies.put(AiCapability.AGENT, policy(AiCapability.AGENT, 4_000, Duration.ofSeconds(3), 1));
        policies.put(AiCapability.WRITING, policy(AiCapability.WRITING, 20_000, Duration.ofSeconds(3), 1));
        CountDownLatch agentEntered = new CountDownLatch(1);
        CountDownLatch releaseAgent = new CountDownLatch(1);
        ExecutorService callers = Executors.newSingleThreadExecutor();
        executor = executor(policies, context -> { }, runtimeDefaults());
        try {
            Future<String> active = callers.submit(() -> executor.execute(
                    context(AiCapability.AGENT, 10, Instant.now().plusSeconds(5), false), () -> {
                        agentEntered.countDown();
                        releaseAgent.await();
                        return "agent";
                    }));
            assertThat(agentEntered.await(2, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            assertReason(AiExecutionErrorReason.BULKHEAD_FULL, () -> executor.execute(
                    context(AiCapability.AGENT, 10, Instant.now().plusSeconds(5), false), () -> "queued"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(250));
            assertThat(executor.execute(context(AiCapability.WRITING, 10,
                    Instant.now().plusSeconds(5), false), () -> "writing")).isEqualTo("writing");

            releaseAgent.countDown();
            assertThat(active.get(2, TimeUnit.SECONDS)).isEqualTo("agent");
        }
        finally {
            releaseAgent.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void repeatedRecordedFailuresOpenOnlyThatCapabilityCircuit() {
        MetroAiProperties.RuntimeProperties runtime = runtimeDefaults();
        runtime.setCircuitMinimumCalls(2);
        runtime.setCircuitSlidingWindowSize(2);
        runtime.setCircuitFailureRateThreshold(50.0f);
        executor = executor(defaultPolicies(), context -> { }, runtime);

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> executor.execute(context(AiCapability.AGENT, 10,
                    Instant.now().plusSeconds(5), false), () -> {
                throw provider(AiProviderErrorReason.MALFORMED_RESPONSE, null);
            })).isInstanceOf(AiProviderException.class);
        }

        assertReason(AiExecutionErrorReason.CIRCUIT_OPEN, () -> executor.execute(
                context(AiCapability.AGENT, 10, Instant.now().plusSeconds(5), false), () -> "blocked"));
        assertThat(executor.execute(context(AiCapability.WRITING, 10,
                Instant.now().plusSeconds(5), false), () -> "healthy")).isEqualTo("healthy");
    }

    @Test
    void closeTerminatesNamedCapabilityWorkers() {
        executor = executor(defaultPolicies(), context -> { }, runtimeDefaults());
        assertThat(executor.execute(context(AiCapability.AGENT, 10,
                Instant.now().plusSeconds(5), false), () -> "ok")).isEqualTo("ok");

        executor.close();
        executor = null;

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(Thread.getAllStackTraces()
                .keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("ai-")))
                .isEmpty());
    }

    private DefaultAiCapabilityExecutor executor(Map<AiCapability, AiCapabilityPolicy> policies,
                                                   AiQuotaService quotaService,
                                                   MetroAiProperties.RuntimeProperties runtime) {
        AiCapabilityPolicyResolver resolver = new AiCapabilityPolicyResolver(policies, runtime);
        return new DefaultAiCapabilityExecutor(resolver, quotaService, metrics, Clock.systemUTC());
    }

    private static Map<AiCapability, AiCapabilityPolicy> defaultPolicies() {
        Map<AiCapability, AiCapabilityPolicy> policies = new EnumMap<>(AiCapability.class);
        for (AiCapability capability : AiCapability.values()) {
            int maxInput = capability == AiCapability.AGENT || capability == AiCapability.HYDE ? 4_000 : 100_000;
            policies.put(capability, policy(capability, maxInput, Duration.ofSeconds(2), 2));
        }
        policies.put(AiCapability.WRITING, policy(AiCapability.WRITING, 20_000, Duration.ofSeconds(2), 2));
        return policies;
    }

    private static AiCapabilityPolicy policy(AiCapability capability, int maxInput,
                                             Duration timeout, int concurrency) {
        AiCapability quotaGroup = capability == AiCapability.HYDE ? AiCapability.AGENT : capability;
        return new AiCapabilityPolicy(capability, quotaGroup, true, maxInput,
                1_000, 10_000, Duration.ofMinutes(1), timeout, concurrency,
                capability == AiCapability.EMBEDDING ? "ollama" : "deepseek",
                capability == AiCapability.EMBEDDING ? "bge-m3" : "deepseek-v4-flash");
    }

    private static MetroAiProperties.RuntimeProperties runtimeDefaults() {
        MetroAiProperties.RuntimeProperties runtime = new MetroAiProperties.RuntimeProperties();
        runtime.setRetryDelay(Duration.ZERO);
        runtime.setCircuitSlidingWindowSize(10);
        runtime.setCircuitMinimumCalls(10);
        runtime.setCircuitFailureRateThreshold(50.0f);
        runtime.setCircuitOpenStateWaitDuration(Duration.ofMinutes(1));
        runtime.setCircuitPermittedCallsInHalfOpen(1);
        runtime.setShutdownTimeout(Duration.ofSeconds(1));
        return runtime;
    }

    private static AiInvocationContext context(AiCapability capability, int inputCharacters,
                                               Instant deadline, boolean background) {
        return new AiInvocationContext(capability, 42L, "request-42", inputCharacters, deadline, background);
    }

    private static String failTransiently(AtomicInteger attempts, int successAttempt) {
        if (attempts.incrementAndGet() < successAttempt) {
            throw provider(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 503);
        }
        return "ok";
    }

    private static AiProviderException provider(AiProviderErrorReason reason, Integer status) {
        return new AiProviderException(reason, status, "typed provider failure", null);
    }

    private static void assertReason(AiExecutionErrorReason expected, Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(AiExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(expected));
    }

    private static final class SequenceClock extends Clock {

        private final List<Instant> instants;
        private final AtomicInteger index = new AtomicInteger();

        private SequenceClock(List<Instant> instants) {
            this.instants = List.copyOf(instants);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            int position = index.getAndUpdate(current -> Math.min(current + 1, instants.size() - 1));
            return instants.get(position);
        }
    }
}
