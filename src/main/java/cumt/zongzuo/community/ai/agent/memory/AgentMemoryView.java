package cumt.zongzuo.community.ai.agent.memory;

import java.time.LocalDateTime;

/**
 * 向当前所有者展示的长期记忆快照。
 *
 * <p>sourceType 只暴露“手动添加”或“来自主对话”这种粗粒度来源，
 * 不把内部 messageId、turnId 或向量投影状态暴露给前端。expiresAt 为 null 表示永不过期。</p>
 */
public record AgentMemoryView(long id, String category, String content, long version,
                              String state, LocalDateTime expiresAt, String sourceType) {
}
