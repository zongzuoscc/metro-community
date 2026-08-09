package cumt.zongzuo.community.ai.web;

import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** A safe, stable error raised by an AI API before it reaches the Provider boundary. */
public final class AiApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final boolean retryable;
    private final Duration retryAfter;

    private AiApiException(HttpStatus status, String code, boolean retryable, Duration retryAfter) {
        super(code);
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
        this.retryable = retryable;
        if (retryAfter != null && (retryAfter.isZero() || retryAfter.isNegative())) {
            throw new IllegalArgumentException("retryAfter must be positive");
        }
        this.retryAfter = retryAfter;
    }

    public static AiApiException resourceNotFound() {
        return fixed(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    public static AiApiException idempotencyConflict() {
        return fixed(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
    }

    public static AiApiException activeTurnExists() {
        return fixed(HttpStatus.CONFLICT, "ACTIVE_TURN_EXISTS");
    }

    public static AiApiException optimisticLockConflict() {
        return fixed(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT");
    }

    public static AiApiException suggestionStateConflict() {
        return fixed(HttpStatus.CONFLICT, "SUGGESTION_STATE_CONFLICT");
    }

    public static AiApiException temporarySessionExpired() {
        return fixed(HttpStatus.GONE, "TEMPORARY_SESSION_EXPIRED");
    }

    public static AiApiException eventStreamExpired() {
        return fixed(HttpStatus.GONE, "EVENT_STREAM_EXPIRED");
    }

    public static AiApiException inputTooLarge() {
        return fixed(HttpStatus.PAYLOAD_TOO_LARGE, "AI_INPUT_TOO_LARGE");
    }

    public static AiApiException quotaExceeded(Duration retryAfter) {
        return retryable(HttpStatus.TOO_MANY_REQUESTS, "AI_QUOTA_EXCEEDED", retryAfter);
    }

    public static AiApiException concurrencyLimit(Duration retryAfter) {
        return retryable(HttpStatus.TOO_MANY_REQUESTS, "AI_CONCURRENCY_LIMIT", retryAfter);
    }

    public static AiApiException disabled() {
        return fixed(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED");
    }

    public static AiApiException unavailable(Duration retryAfter) {
        return retryable(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", retryAfter);
    }

    public static AiApiException runtimeUnavailable(Duration retryAfter) {
        return retryable(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_RUNTIME_UNAVAILABLE", retryAfter);
    }

    private static AiApiException fixed(HttpStatus status, String code) {
        return new AiApiException(status, code, false, null);
    }

    private static AiApiException retryable(HttpStatus status, String code, Duration retryAfter) {
        return new AiApiException(status, code, true, Objects.requireNonNull(retryAfter, "retryAfter"));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
