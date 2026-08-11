package cumt.zongzuo.community.ai.agent;

public final class InvalidAgentAnswerException extends RuntimeException {

    public InvalidAgentAnswerException(String message) {
        super(message);
    }

    public InvalidAgentAnswerException(String message, Throwable cause) {
        super(message, cause);
    }
}
