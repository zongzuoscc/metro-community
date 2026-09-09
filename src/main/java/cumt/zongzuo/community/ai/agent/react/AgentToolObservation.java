package cumt.zongzuo.community.ai.agent.react;

import java.util.Objects;

/**
 * 后端执行一次只读工具后产生的真实观察。
 *
 * <p>{@code status} 是后端状态，{@code content} 是不可信资料；决策提示词必须
 * 将内容当作证据而非指令。</p>
 */
public record AgentToolObservation(AgentToolCall call, String status, String content) {
    public AgentToolObservation {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
