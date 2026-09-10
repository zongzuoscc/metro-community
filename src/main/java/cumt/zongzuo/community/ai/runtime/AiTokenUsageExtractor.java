package cumt.zongzuo.community.ai.runtime;

import cumt.zongzuo.community.ai.provider.AiChatResult;

import java.util.OptionalLong;

public final class AiTokenUsageExtractor {

    public OptionalLong totalTokens(Object result) {
        if (result instanceof cumt.zongzuo.community.ai.userprovider.UserAiRoutedResult routed)
            return totalTokens(routed.result());
        if (result instanceof org.springframework.ai.chat.model.ChatResponse response)
            return OptionalLong.of(response.getMetadata().getUsage().getTotalTokens());
        if (result instanceof cumt.zongzuo.community.ai.provider.EmbeddingResult embedding)
            return OptionalLong.of(embedding.totalTokens());
        if (result instanceof AiChatResult chatResult) {
            return OptionalLong.of(
                    Math.addExact(chatResult.inputTokens(), chatResult.outputTokens()));
        }
        return OptionalLong.empty();
    }
}
