package cumt.zongzuo.community.ai.agent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * 持久与临时 turn 共用的入参。
 * temporary=true 时必须携带 temporarySessionId；该关联会在 admission 时再校验所有者与过期时间。
 */
public record AgentTurnCreateRequest(@NotNull UUID clientRequestId,
                                     @NotBlank @Size(max = 4_000) String message,
                                     boolean temporary,
                                     UUID temporarySessionId,
                                     Map<String, Object> context) {
}
