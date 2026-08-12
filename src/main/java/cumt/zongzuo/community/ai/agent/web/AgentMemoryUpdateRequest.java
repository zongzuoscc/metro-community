package cumt.zongzuo.community.ai.agent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AgentMemoryUpdateRequest(@NotBlank @Size(max = 1000) String content,
                                       @Positive long expectedVersion) {
}
