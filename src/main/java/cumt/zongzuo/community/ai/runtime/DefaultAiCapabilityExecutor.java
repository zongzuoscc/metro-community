package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiProviderException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultAiCapabilityExecutor implements AiCapabilityExecutor, AutoCloseable {

    private final AiCapabilityPolicyResolver policyResolver;
    private final AiQuotaService quotaService;
    private final AiMetrics metrics;
    private final Clock clock;
    private final MetroAiProperties.RuntimeProperties runtime;
    private final Map<cumt.zongzuo.community.ai.provider.AiCapability, CapabilityLane> lanes;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultAiCapabilityExecutor(AiCapabilityPolicyResolver policyResolver,
                                       AiQuotaService quotaService,
                                       AiMetrics metrics,
                                       Clock clock) {
        this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
        this.quotaService = Objects.requireNonNull(quotaService, "quotaService");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtime = Objects.requireNonNull(policyResolver.runtime(), "runtime");
        validateRuntime(runtime);
        EnumMap<cumt.zongzuo.community.ai.provider.AiCapability, CapabilityLane> configured =
                new EnumMap<>(cumt.zongzuo.community.ai.provider.AiCapability.class);
        policyResolver.policies().forEach((capability, policy) -> {
            CapabilityLane lane = createLane(policy);
            configured.put(capability, lane);
            metrics.registerCircuitGauge(policy, lane.circuitBreaker());
        });
        this.lanes = Map.copyOf(configured);
    }

    @Override
    public <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operation, "operation");
        AiCapabilityPolicy policy = policyResolver.resolve(context.capability());
        Instant invocationStarted = clock.instant();
        long startedNanos = System.nanoTime();
        T result = null;
        String outcome = "failure";
        try {
            validateInvocation(context, policy);
            if (closed.get()) {
                throw new AiExecutionException(AiExecutionErrorReason.AGENT_RUNTIME_UNAVAILABLE,
                        "AI capability executor is shut down");
            }
            quotaService.acquire(context);
            Instant capabilityDeadline = minimum(context.deadline(), invocationStarted.plus(policy.timeout()));
            CapabilityLane lane = lane(context);
            Retry retry = retry(context, capabilityDeadline);
            CheckedSupplier<T> timed = () -> executeTimedAttempt(lane, capabilityDeadline, operation);
            CheckedSupplier<T> bulkheaded = Bulkhead.decorateCheckedSupplier(lane.bulkhead(), timed);
            CheckedSupplier<T> circuitProtected = CircuitBreaker.decorateCheckedSupplier(
                    lane.circuitBreaker(), bulkheaded);
            CheckedSupplier<T> resilient = Retry.decorateCheckedSupplier(retry, circuitProtected);
            result = resilient.get();
            outcome = "success";
            return result;
        }
        catch (Throwable error) {
            RuntimeException translated = translate(error, policy);
            outcome = outcome(translated);
            throw translated;
        }
        finally {
            metrics.recordRequest(policy, outcome,
                    Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)), result);
        }
    }

    private <T> T executeTimedAttempt(CapabilityLane lane, Instant deadline,
                                      CheckedSupplier<T> operation) throws Throwable {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new AiExecutionException(AiExecutionErrorReason.DEADLINE_EXCEEDED,
                    "AI invocation deadline has elapsed");
        }
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(remaining)
                .cancelRunningFuture(true)
                .build();
        TimeLimiter limiter = TimeLimiter.of("ai-" + lane.policy().capability().name().toLowerCase(Locale.ROOT),
                config);
        AtomicReference<Future<T>> submitted = new AtomicReference<>();
        try {
            return TimeLimiter.decorateFutureSupplier(limiter, () -> {
                Future<T> future = lane.executor().submit(() -> invokeProvider(lane.policy(), operation));
                submitted.set(future);
                return future;
            }).call();
        }
        catch (TimeoutException error) {
            cancel(submitted.get());
            metrics.recordProviderTimeout(lane.policy());
            throw new AiExecutionException(AiExecutionErrorReason.TIMEOUT,
                    "AI capability execution timed out", error);
        }
        catch (InterruptedException error) {
            Future<T> future = submitted.get();
            cancel(future);
            Thread.currentThread().interrupt();
            throw new AiExecutionException(AiExecutionErrorReason.CANCELLED,
                    "AI capability execution was cancelled", error);
        }
        catch (OperationThrowable error) {
            throw error.getCause();
        }
    }

    private <T> T invokeProvider(AiCapabilityPolicy policy, CheckedSupplier<T> operation) throws Exception {
        try {
            return operation.get();
        }
        catch (AiProviderException error) {
            metrics.recordProviderFailure(policy, error);
            throw error;
        }
        catch (RuntimeException | Error error) {
            throw error;
        }
        catch (Exception error) {
            throw error;
        }
        catch (Throwable error) {
            throw new OperationThrowable(error);
        }
    }

    private Retry retry(AiInvocationContext context, Instant deadline) {
        int attempts = context.background()
                ? runtime.getBackgroundMaxAttempts() : runtime.getInteractiveMaxAttempts();
        RetryConfig config = RetryConfig.<Object>custom()
                .maxAttempts(attempts)
                .intervalBiFunction((attempt, outcome) -> retryIntervalMillis(
                        deadline, context.background(), attempt))
                .retryOnException(AiProviderExceptionClassifier::isRetryable)
                .failAfterMaxAttempts(false)
                .build();
        return Retry.of("ai-" + context.capability().name().toLowerCase(Locale.ROOT), config);
    }

    long retryIntervalMillis(Instant deadline, boolean background, int attempt) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return 0L;
        }
        long configured = runtime.getRetryDelay().toMillis();
        if (background && attempt > 1) {
            long multiplier = 1L << Math.min(attempt - 1, 30);
            configured = configured > Long.MAX_VALUE / multiplier
                    ? Long.MAX_VALUE : configured * multiplier;
        }
        return Math.min(configured, remaining.toMillis());
    }

    private CapabilityLane createLane(AiCapabilityPolicy policy) {
        CircuitBreakerConfig circuitConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(runtime.getCircuitSlidingWindowSize())
                .minimumNumberOfCalls(runtime.getCircuitMinimumCalls())
                .failureRateThreshold(runtime.getCircuitFailureRateThreshold())
                .waitDurationInOpenState(runtime.getCircuitOpenStateWaitDuration())
                .permittedNumberOfCallsInHalfOpenState(runtime.getCircuitPermittedCallsInHalfOpen())
                .recordException(AiProviderExceptionClassifier::shouldRecordInCircuit)
                .ignoreException(error -> !AiProviderExceptionClassifier.shouldRecordInCircuit(error))
                .build();
        CircuitBreaker circuitBreaker = CircuitBreaker.of(
                "ai-" + policy.capability().name().toLowerCase(Locale.ROOT), circuitConfig);
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(policy.maxConcurrency())
                .maxWaitDuration(Duration.ZERO)
                .build();
        Bulkhead bulkhead = Bulkhead.of(
                "ai-" + policy.capability().name().toLowerCase(Locale.ROOT), bulkheadConfig);
        return new CapabilityLane(policy, executor(policy), bulkhead, circuitBreaker);
    }

    private static ThreadPoolExecutor executor(AiCapabilityPolicy policy) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory delegate = Executors.defaultThreadFactory();
        ThreadFactory namedFactory = task -> {
            Thread worker = delegate.newThread(task);
            worker.setName("ai-" + policy.capability().name().toLowerCase(Locale.ROOT)
                    .replace('_', '-') + '-' + sequence.incrementAndGet());
            worker.setDaemon(false);
            return worker;
        };
        return new ThreadPoolExecutor(policy.maxConcurrency(), policy.maxConcurrency(),
                0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), namedFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private CapabilityLane lane(AiInvocationContext context) {
        CapabilityLane lane = lanes.get(context.capability());
        if (lane == null) {
            throw new AiExecutionException(AiExecutionErrorReason.AGENT_RUNTIME_UNAVAILABLE,
                    "AI capability executor is not configured");
        }
        return lane;
    }

    private void validateInvocation(AiInvocationContext context, AiCapabilityPolicy policy) {
        if (!policy.enabled()) {
            throw new AiExecutionException(AiExecutionErrorReason.AI_DISABLED,
                    "AI capability is disabled");
        }
        if (context.inputCharacters() > policy.maxInputCharacters()) {
            throw new AiExecutionException(AiExecutionErrorReason.INPUT_TOO_LARGE,
                    "AI input exceeds the configured capability limit");
        }
        if (!context.deadline().isAfter(clock.instant())) {
            throw new AiExecutionException(AiExecutionErrorReason.DEADLINE_EXCEEDED,
                    "AI invocation deadline has elapsed");
        }
    }

    private RuntimeException translate(Throwable error, AiCapabilityPolicy policy) {
        if (Thread.currentThread().isInterrupted()
                && !(error instanceof AiExecutionException executionError
                && executionError.reason() == AiExecutionErrorReason.TIMEOUT)) {
            return new AiExecutionException(AiExecutionErrorReason.CANCELLED,
                    "AI invocation was interrupted", error);
        }
        if (error instanceof AiExecutionException executionError) {
            return executionError;
        }
        if (error instanceof AiProviderException providerError) {
            return providerError;
        }
        if (error instanceof BulkheadFullException || error instanceof RejectedExecutionException) {
            metrics.recordBulkheadRejected(policy);
            return new AiExecutionException(AiExecutionErrorReason.BULKHEAD_FULL,
                    "AI capability concurrency limit is full", error);
        }
        if (error instanceof CallNotPermittedException) {
            return new AiExecutionException(AiExecutionErrorReason.CIRCUIT_OPEN,
                    "AI provider circuit is open", error);
        }
        if (error instanceof CancellationException || error instanceof InterruptedException) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new AiExecutionException(AiExecutionErrorReason.CANCELLED,
                    "AI invocation was cancelled", error);
        }
        if (error instanceof Error fatal) {
            throw fatal;
        }
        return new AiExecutionException(AiExecutionErrorReason.PROVIDER_FAILURE,
                "AI provider operation failed", error);
    }

    private static String outcome(RuntimeException error) {
        if (error instanceof AiExecutionException executionError) {
            return executionError.reason().name().toLowerCase(Locale.ROOT);
        }
        if (error instanceof AiProviderException providerError) {
            return providerError.reason().name().toLowerCase(Locale.ROOT);
        }
        return "failure";
    }

    private static Instant minimum(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static void cancel(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private static void validateRuntime(MetroAiProperties.RuntimeProperties runtime) {
        if (runtime.getRetryDelay() == null || runtime.getRetryDelay().isNegative()
                || runtime.getInteractiveMaxAttempts() != 2 || runtime.getBackgroundMaxAttempts() != 3
                || runtime.getCircuitSlidingWindowSize() <= 0
                || runtime.getCircuitMinimumCalls() <= 0
                || runtime.getCircuitMinimumCalls() > runtime.getCircuitSlidingWindowSize()
                || runtime.getCircuitFailureRateThreshold() <= 0
                || runtime.getCircuitFailureRateThreshold() > 100
                || runtime.getCircuitOpenStateWaitDuration() == null
                || runtime.getCircuitOpenStateWaitDuration().isNegative()
                || runtime.getCircuitOpenStateWaitDuration().isZero()
                || runtime.getCircuitPermittedCallsInHalfOpen() <= 0
                || runtime.getShutdownTimeout() == null || runtime.getShutdownTimeout().isNegative()) {
            throw new IllegalArgumentException("Invalid metro.ai.runtime resilience configuration");
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<ExecutorService> executors = new ArrayList<>();
        lanes.values().forEach(lane -> executors.add(lane.executor()));
        executors.forEach(ExecutorService::shutdown);
        long remainingNanos = runtime.getShutdownTimeout().toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        boolean interrupted = false;
        for (ExecutorService executor : executors) {
            if (remainingNanos <= 0) {
                break;
            }
            try {
                executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
            }
            catch (InterruptedException error) {
                interrupted = true;
                break;
            }
            remainingNanos = deadline - System.nanoTime();
        }
        executors.stream().filter(executor -> !executor.isTerminated()).forEach(ExecutorService::shutdownNow);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record CapabilityLane(AiCapabilityPolicy policy, ThreadPoolExecutor executor,
                                  Bulkhead bulkhead, CircuitBreaker circuitBreaker) {
    }

    private static final class OperationThrowable extends Exception {

        private OperationThrowable(Throwable cause) {
            super(cause);
        }
    }
}
