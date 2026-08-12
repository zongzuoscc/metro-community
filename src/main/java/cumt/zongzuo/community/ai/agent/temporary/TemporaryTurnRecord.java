package cumt.zongzuo.community.ai.agent.temporary;

import java.time.Instant;
import java.util.UUID;

/**
 * 单个临时 Agent turn 的 Redis-only 快照。
 * question/answer 不会写入 MySQL；errorCode 只保存稳定的脱敏错误码。
 */
public record TemporaryTurnRecord(long turnId, long userId, UUID sessionId, UUID clientRequestId,
                                  UUID runId, long runFence, String requestHash, String state,
                                  String question, String answer, String errorCode,
                                  int citationCount, Instant createdAt, Instant completedAt) {
}
