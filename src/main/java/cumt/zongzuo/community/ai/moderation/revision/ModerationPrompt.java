package cumt.zongzuo.community.ai.moderation.revision;

import cumt.zongzuo.community.ai.provider.AiPromptMessage;

import java.util.List;

public record ModerationPrompt(List<AiPromptMessage> messages, String promptVersion,
                               String inputHash, int inputCharacters) {

    public ModerationPrompt {
        messages = List.copyOf(messages);
    }
}
