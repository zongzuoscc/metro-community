package cumt.zongzuo.community.ai.agent.react;

import java.util.Objects;

/**
 * ReAct 响应被拒绝时的稳定诊断契约，保留 IllegalStateException 的既有兼容性。
 * 异常仅允许预定义错误码，不保存模型原文、用户查询、供应商信息或底层解析异常。
 */
public final class ReActDecisionException extends IllegalStateException {

    public enum Code {
        REACT_INVALID_JSON,
        REACT_INVALID_SHAPE,
        REACT_UNKNOWN_TOOL,
        REACT_FORBIDDEN_TOOL,
        REACT_INVALID_QUERY,
        REACT_RESPONSE_TRUNCATED,
        REACT_ROUTE_MISMATCH,
        REACT_RESPONSE_TOO_LARGE,
        REACT_RESPONSE_INCOMPLETE,
        REACT_INVALID_RESPONSE
    }

    private final Code code;

    public ReActDecisionException(Code code) {
        super(Objects.requireNonNull(code, "code must not be null").name(), null);
        this.code = code;
    }

    public String errorCode() {
        return code.name();
    }
}
