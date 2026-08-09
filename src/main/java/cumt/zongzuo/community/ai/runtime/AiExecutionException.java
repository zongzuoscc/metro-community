package cumt.zongzuo.community.ai.runtime;

import java.time.Duration;
import java.util.Optional;

public class AiExecutionException extends RuntimeException {

    private final AiExecutionErrorReason reason;
    private final Duration retryAfter;

    public AiExecutionException(AiExecutionErrorReason reason, String message) {
        this(reason, message, null, null);
    }

    public AiExecutionException(AiExecutionErrorReason reason, String message, Throwable cause) {
        this(reason, message, cause, null);
    }

    public AiExecutionException(AiExecutionErrorReason reason, String message,
                                Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.reason = reason;
        this.retryAfter = retryAfter;
    }

    public AiExecutionErrorReason reason() {
        return reason;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
