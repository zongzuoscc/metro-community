package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.provider.AiChatResult;

import java.util.OptionalLong;

public final class AiTokenUsageExtractor {

    public OptionalLong totalTokens(Object result) {
        if (result instanceof AiChatResult chatResult) {
            return OptionalLong.of(Math.addExact(chatResult.inputTokens(), chatResult.outputTokens()));
        }
        return OptionalLong.empty();
    }
}
