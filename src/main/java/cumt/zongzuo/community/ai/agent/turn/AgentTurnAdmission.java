package cumt.zongzuo.community.ai.agent.turn;

import java.util.UUID;

public record AgentTurnAdmission(long turnId, UUID runId, long runFence, boolean created,
                                 String state) {
}
