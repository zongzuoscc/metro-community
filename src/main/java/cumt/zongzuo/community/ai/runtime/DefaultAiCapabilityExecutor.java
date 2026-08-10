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
        Objects.requireNonNull(operation, "operation");
        return execute(context, new AttemptObserver<>() {
            @Override
            public Object begin() {
                return new Object();
            }

            @Override
            public void complete(Object attempt, T result, Throwable error) {
                // The original API intentionally has no per-attempt observer.
            }
        }, ignored -> operation.get());
    }

    @Override
    public <A, T> T execute(AiInvocationContext context, AttemptObserver<A, T> observer,
                            AttemptOperation<A, T> operation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(observer, "observer");
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
            EffectiveDeadline effectiveDeadline = effectiveDeadline(
                    context.deadline(), invocationStarted.plus(policy.timeout()));
            CapabilityLane lane = lane(context);
            RetryDeadlineGuard retryDeadlineGuard = new RetryDeadlineGuard();
            Retry retry = retry(context, effectiveDeadline, retryDeadlineGuard);
            CheckedSupplier<T> timed = () -> executeTimedAttempt(lane, effectiveDeadline,
                    observer, operation);
            CheckedSupplier<T> bulkheaded = Bulkhead.decorateCheckedSupplier(lane.bulkhead(), timed);
            CheckedSupplier<T> circuitProtected = CircuitBreaker.decorateCheckedSupplier(
                    lane.circuitBreaker(), bulkheaded);
            CheckedSupplier<T> retryableAttempt = () -> {
                retryDeadlineGuard.verifyRetryMayStart();
                return circuitProtected.get();
            };
            CheckedSupplier<T> resilient = Retry.decorateCheckedSupplier(retry, retryableAttempt);
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

    private <A, T> T executeTimedAttempt(CapabilityLane lane, EffectiveDeadline effectiveDeadline,
                                         AttemptObserver<A, T> observer,
                                         AttemptOperation<A, T> operation) throws Throwable {
        Duration remaining = Duration.between(clock.instant(), effectiveDeadline.instant());
        if (remaining.isZero() || remaining.isNegative()) {
            throw expired(effectiveDeadline);
        }
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(remaining)
                .cancelRunningFuture(true)
                .build();
        TimeLimiter limiter = TimeLimiter.of("ai-" + lane.policy().capability().name().toLowerCase(Locale.ROOT),
                config);
        AtomicReference<Future<T>> submitted = new AtomicReference<>();
        AttemptScope<A, T> attempt = new AttemptScope<>(observer);
        try {
            return TimeLimiter.decorateFutureSupplier(limiter, () -> {
                Future<T> future = lane.executor().submit(() -> invokeProvider(lane.policy(),
                        () -> attempt.execute(operation)));
                submitted.set(future);
                return future;
            }).call();
        }
        catch (TimeoutException error) {
            AiExecutionException expired = expired(effectiveDeadline, error);
            try {
                attempt.forceFailure(expired);
            }
            finally {
                cancel(submitted.get());
                if (effectiveDeadline.reason() == AiExecutionErrorReason.TIMEOUT) {
                    metrics.recordProviderTimeout(lane.policy());
                }
            }
            throw expired;
        }
        catch (InterruptedException error) {
            AiExecutionException cancelled = new AiExecutionException(
                    AiExecutionErrorReason.CANCELLED,
                    "AI capability execution was cancelled", error);
            try {
                attempt.forceFailure(cancelled);
            }
            finally {
                cancel(submitted.get());
                Thread.currentThread().interrupt();
            }
            throw cancelled;
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

    private Retry retry(AiInvocationContext context, EffectiveDeadline effectiveDeadline,
                        RetryDeadlineGuard retryDeadlineGuard) {
        int attempts = context.background()
                ? runtime.getBackgroundMaxAttempts() : runtime.getInteractiveMaxAttempts();
        RetryConfig config = RetryConfig.<Object>custom()
                .maxAttempts(attempts)
                .intervalBiFunction((attempt, outcome) -> guardedRetryIntervalMillis(
                        effectiveDeadline, context.background(), attempt, retryDeadlineGuard))
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
        long configured = configuredRetryIntervalMillis(background, attempt);
        return Math.min(configured, remaining.toMillis());
    }

    private long guardedRetryIntervalMillis(EffectiveDeadline effectiveDeadline, boolean background,
                                            int attempt, RetryDeadlineGuard retryDeadlineGuard) {
        Duration remaining = Duration.between(clock.instant(), effectiveDeadline.instant());
        if (remaining.isNegative() || remaining.isZero()) {
            retryDeadlineGuard.preventRetry(effectiveDeadline.reason());
            return 0L;
        }
        long configured = configuredRetryIntervalMillis(background, attempt);
        if (Duration.ofMillis(configured).compareTo(remaining) >= 0) {
            retryDeadlineGuard.preventRetry(effectiveDeadline.reason());
            return 0L;
        }
        return configured;
    }

    private long configuredRetryIntervalMillis(boolean background, int attempt) {
        long configured = runtime.getRetryDelay().toMillis();
        if (background && attempt > 1) {
            long multiplier = 1L << Math.min(attempt - 1, 30);
            configured = configured > Long.MAX_VALUE / multiplier
                    ? Long.MAX_VALUE : configured * multiplier;
        }
        return configured;
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

    private static EffectiveDeadline effectiveDeadline(Instant callerDeadline, Instant capabilityDeadline) {
        if (!callerDeadline.isAfter(capabilityDeadline)) {
            return new EffectiveDeadline(callerDeadline, AiExecutionErrorReason.DEADLINE_EXCEEDED);
        }
        return new EffectiveDeadline(capabilityDeadline, AiExecutionErrorReason.TIMEOUT);
    }

    private static AiExecutionException expired(EffectiveDeadline effectiveDeadline) {
        return expired(effectiveDeadline, null);
    }

    private static AiExecutionException expired(EffectiveDeadline effectiveDeadline, Throwable cause) {
        String message = effectiveDeadline.reason() == AiExecutionErrorReason.DEADLINE_EXCEEDED
                ? "AI invocation deadline has elapsed" : "AI capability execution timed out";
        return new AiExecutionException(effectiveDeadline.reason(), message, cause);
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

    private record EffectiveDeadline(Instant instant, AiExecutionErrorReason reason) {
    }

    private static final class RetryDeadlineGuard {

        private AiExecutionErrorReason preventedReason;

        private void preventRetry(AiExecutionErrorReason reason) {
            preventedReason = reason;
        }

        private void verifyRetryMayStart() {
            if (preventedReason != null) {
                throw new AiExecutionException(preventedReason,
                        preventedReason == AiExecutionErrorReason.DEADLINE_EXCEEDED
                                ? "AI invocation deadline cannot accommodate another retry"
                                : "AI capability timeout cannot accommodate another retry");
            }
        }
    }

    private static final class OperationThrowable extends Exception {

        private OperationThrowable(Throwable cause) {
            super(cause);
        }
    }

    private static final class AttemptScope<A, T> {

        private final AttemptObserver<A, T> observer;
        private AttemptState state = AttemptState.NEW;
        private A attempt;
        private Throwable forcedFailure;
        private Throwable completionFailure;

        private AttemptScope(AttemptObserver<A, T> observer) {
            this.observer = observer;
        }

        private T execute(AttemptOperation<A, T> operation) throws Throwable {
            synchronized (this) {
                if (state != AttemptState.NEW) {
                    throw forcedFailure == null
                            ? new CancellationException("observed attempt was cancelled before start")
                            : forcedFailure;
                }
                state = AttemptState.ACTIVE;
            }
            A started;
            try {
                started = Objects.requireNonNull(observer.begin(),
                        "observed attempt must not be null");
            }
            catch (Throwable error) {
                synchronized (this) {
                    state = AttemptState.FINALIZED;
                    completionFailure = error;
                    notifyAll();
                }
                throw error;
            }
            Throwable forced;
            synchronized (this) {
                attempt = started;
                forced = forcedFailure;
                notifyAll();
            }
            if (forced != null) {
                finish(started, null, forced);
                throw forced;
            }
            try {
                T result = operation.execute(started);
                finish(started, result, null);
                return result;
            }
            catch (Throwable error) {
                finish(started, null, error);
                throw error;
            }
        }

        private void forceFailure(Throwable error) {
            A started;
            synchronized (this) {
                if (state == AttemptState.NEW) {
                    forcedFailure = error;
                    state = AttemptState.CANCELLED;
                    notifyAll();
                    return;
                }
                if (forcedFailure == null) {
                    forcedFailure = error;
                }
                while (state == AttemptState.ACTIVE && attempt == null) {
                    waitUninterruptibly();
                }
                if (state == AttemptState.FINALIZING) {
                    awaitFinalized();
                    rethrowCompletionFailure();
                    return;
                }
                if (state == AttemptState.FINALIZED || state == AttemptState.CANCELLED) {
                    rethrowCompletionFailure();
                    return;
                }
                started = attempt;
            }
            finish(started, null, forcedFailure);
        }

        private void finish(A started, T result, Throwable error) {
            synchronized (this) {
                if (state == AttemptState.FINALIZING) {
                    awaitFinalized();
                    rethrowCompletionFailure();
                    return;
                }
                if (state == AttemptState.FINALIZED || state == AttemptState.CANCELLED) {
                    rethrowCompletionFailure();
                    return;
                }
                state = AttemptState.FINALIZING;
                if (forcedFailure != null) {
                    result = null;
                    error = forcedFailure;
                }
            }
            Throwable callbackFailure = null;
            try {
                observer.complete(started, result, error);
            }
            catch (Throwable failure) {
                callbackFailure = failure;
            }
            synchronized (this) {
                completionFailure = callbackFailure;
                state = AttemptState.FINALIZED;
                notifyAll();
            }
            rethrowCompletionFailure();
        }

        private void awaitFinalized() {
            while (state == AttemptState.FINALIZING) {
                waitUninterruptibly();
            }
        }

        private void waitUninterruptibly() {
            boolean interrupted = false;
            while (true) {
                try {
                    wait();
                    break;
                }
                catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void rethrowCompletionFailure() {
            if (completionFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (completionFailure instanceof Error fatal) {
                throw fatal;
            }
            if (completionFailure != null) {
                throw new IllegalStateException("observed attempt completion failed",
                        completionFailure);
            }
        }
    }

    private enum AttemptState {
        NEW,
        ACTIVE,
        FINALIZING,
        FINALIZED,
        CANCELLED
    }
}
