package cumt.zongzuo.community.ai.agent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AgentAnswerRequest(@NotNull UUID clientRequestId,
                                 @NotBlank @Size(max = 4_000) String message) {
}
