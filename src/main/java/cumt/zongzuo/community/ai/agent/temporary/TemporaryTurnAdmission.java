package cumt.zongzuo.community.ai.agent.temporary;

import java.util.UUID;

/** 临时 turn 同时拿到 MySQL 共享栅栏和 Redis 租约后返回的接纳结果。 */
public record TemporaryTurnAdmission(long turnId, UUID sessionId, UUID runId, long runFence,
                                     boolean created, String state) {
}
