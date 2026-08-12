package cumt.zongzuo.community.ai.agent.web;

import jakarta.validation.constraints.PositiveOrZero;

public record AgentMemorySettingRequest(boolean enabled, @PositiveOrZero long expectedVersion) {
}
