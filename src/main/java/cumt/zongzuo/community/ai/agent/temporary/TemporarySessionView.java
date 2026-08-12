package cumt.zongzuo.community.ai.agent.temporary;

import java.time.Instant;
import java.util.UUID;

/**
 * 临时会话窗口的对外描述，其中不含任何对话内容。
 *
 * <p>expiresAt 是创建时确定的绝对截止时间；查询、重复创建和发送新消息都不能延长它。</p>
 */
public record TemporarySessionView(UUID sessionId, Instant createdAt, Instant expiresAt) {
}
