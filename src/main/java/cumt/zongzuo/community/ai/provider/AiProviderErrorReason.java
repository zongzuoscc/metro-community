package cumt.zongzuo.community.ai.provider;

public enum AiProviderErrorReason {
    AI_DISABLED,
    AI_UNAVAILABLE,
    CONNECTION_FAILURE,
    TIMEOUT,
    RATE_LIMITED,
    RETRYABLE_PROVIDER_FAILURE,
    NON_RETRYABLE_PROVIDER_FAILURE,
    MALFORMED_RESPONSE,
    EMPTY_RESPONSE
}
