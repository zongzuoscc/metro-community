package cumt.zongzuo.community.ai.agent.turn;

import java.util.UUID;

public record AgentTurnAdmission(long turnId, UUID runId, long runFence, boolean created,
                                 String state, boolean webSearchEnabled) {

    /** 兼容不关心联网开关的既有内部调用，历史默认行为为开启联网。 */
    public AgentTurnAdmission(long turnId, UUID runId, long runFence, boolean created,
                              String state) {
        this(turnId, runId, runFence, created, state, true);
    }
}
