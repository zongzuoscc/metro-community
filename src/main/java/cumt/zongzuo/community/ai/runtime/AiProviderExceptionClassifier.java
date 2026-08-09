package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.provider.AiProviderErrorReason;
import cumt.zongzuo.community.ai.provider.AiProviderException;

public final class AiProviderExceptionClassifier {

    private AiProviderExceptionClassifier() {
    }

    public static boolean isRetryable(Throwable error) {
        if (!(error instanceof AiProviderException providerError)) {
            return false;
        }
        return switch (providerError.reason()) {
            case CONNECTION_FAILURE, RATE_LIMITED -> true;
            case RETRYABLE_PROVIDER_FAILURE -> providerError.httpStatus()
                    .map(AiProviderExceptionClassifier::isSelectedRetryableStatus)
                    .orElse(false);
            case AI_DISABLED, AI_UNAVAILABLE, TIMEOUT, NON_RETRYABLE_PROVIDER_FAILURE,
                    MALFORMED_RESPONSE, EMPTY_RESPONSE -> false;
        };
    }

    public static boolean shouldRecordInCircuit(Throwable error) {
        if (error instanceof AiExecutionException executionError) {
            return executionError.reason() == AiExecutionErrorReason.TIMEOUT;
        }
        if (!(error instanceof AiProviderException providerError)) {
            return false;
        }
        return switch (providerError.reason()) {
            case CONNECTION_FAILURE, TIMEOUT, RATE_LIMITED, RETRYABLE_PROVIDER_FAILURE,
                    MALFORMED_RESPONSE, EMPTY_RESPONSE -> true;
            case AI_DISABLED, AI_UNAVAILABLE, NON_RETRYABLE_PROVIDER_FAILURE -> false;
        };
    }

    public static boolean isSelectedRetryableStatus(int status) {
        return status == 500 || status == 502 || status == 503 || status == 504;
    }
}
