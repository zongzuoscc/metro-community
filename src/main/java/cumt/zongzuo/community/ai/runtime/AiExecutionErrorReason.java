package cumt.zongzuo.community.ai.runtime;

public enum AiExecutionErrorReason {
    AI_DISABLED,
    INVALID_INVOCATION,
    INPUT_TOO_LARGE,
    DEADLINE_EXCEEDED,
    QUOTA_EXCEEDED,
    AGENT_RUNTIME_UNAVAILABLE,
    BULKHEAD_FULL,
    CIRCUIT_OPEN,
    TIMEOUT,
    CANCELLED,
    PROVIDER_FAILURE
}
