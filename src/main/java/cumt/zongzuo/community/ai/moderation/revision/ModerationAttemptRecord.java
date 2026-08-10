package cumt.zongzuo.community.ai.moderation.revision;

import cumt.zongzuo.community.ai.provider.AiChatResult;

record ModerationAttemptRecord(String inputHash, ModerationChunk chunk, long latencyMs,
                               AiChatResult result, ModerationModelOutput output,
                               String errorCode) {
}
