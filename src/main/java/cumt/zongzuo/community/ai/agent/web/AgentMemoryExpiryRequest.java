package cumt.zongzuo.community.ai.agent.web;

import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

/** expiresAt 为 null 时表示永不过期，expectedVersion 防止旧页面覆盖新内容。 */
public record AgentMemoryExpiryRequest(LocalDateTime expiresAt,
                                       @Positive long expectedVersion) {
}
