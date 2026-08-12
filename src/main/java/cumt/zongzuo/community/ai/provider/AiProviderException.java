package cumt.zongzuo.community.ai.provider;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.io.InterruptedIOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

public class AiProviderException extends RuntimeException {

    private final AiProviderErrorReason reason;
    private final Integer httpStatus;

    public AiProviderException(AiProviderErrorReason reason, String message) {
        this(reason, null, message, null);
    }

    public AiProviderException(AiProviderErrorReason reason, String message, Throwable cause) {
        this(reason, null, message, cause);
    }

    public AiProviderException(AiProviderErrorReason reason, Integer httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public AiProviderErrorReason reason() {
        return reason;
    }

    public Optional<Integer> httpStatus() {
        return Optional.ofNullable(httpStatus);
    }

    static AiProviderException fromHttpStatus(ProviderHttpStatusException error) {
        int status = error.status();
        AiProviderErrorReason reason;
        if (status == 429) {
            reason = AiProviderErrorReason.RATE_LIMITED;
        }
        else if (status == 500 || status == 502 || status == 503 || status == 504) {
            reason = AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE;
        }
        else {
            reason = AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE;
        }
        return new AiProviderException(reason, status,
                "AI provider request failed with HTTP status " + status, error);
    }

    static AiProviderException fromTransport(Throwable error) {
        AiProviderErrorReason reason;
        String message;
        if (hasTimeoutCause(error)) {
            reason = AiProviderErrorReason.TIMEOUT;
            message = "AI provider request timed out";
        }
        else if (hasConnectionCause(error)) {
            reason = AiProviderErrorReason.CONNECTION_FAILURE;
            message = "AI provider connection failed";
        }
        else {
            reason = AiProviderErrorReason.MALFORMED_RESPONSE;
            message = "AI provider response was truncated or unreadable";
        }
        return new AiProviderException(reason, message, error);
    }

    private static boolean hasTimeoutCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            // OkHttp 的整次调用超时类型是 InterruptedIOException；JDK HttpClient 则使用
            // HttpTimeoutException。两者都应进入相同的可观测 TIMEOUT 语义，不能因为替换
            // OpenAI 兼容传输实现而被误记成“供应商响应格式错误”。
            if (current instanceof SocketTimeoutException
                    || current instanceof InterruptedIOException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasConnectionCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof UnknownHostException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
