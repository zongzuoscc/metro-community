package cumt.zongzuo.community.ai.agent.web;

import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record AgentTurnCreateRequest(@NotNull UUID clientRequestId,
                                     @NotBlank @Size(max = 4_000) String message,
                                     @AssertFalse boolean temporary,
                                     Map<String, Object> context) {
}
