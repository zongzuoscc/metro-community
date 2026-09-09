package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiChatCommand;
import java.util.function.Function;

/** 一次回答的路由快照。先按该模型计算预算，再用同一份配置发送，避免设置变更造成错配。 */
public record PreparedUserAiChat(String model, UserAiFundingSource fundingSource,
                                Function<AiChatCommand, UserAiRoutedResult> invocation,
                                Runnable guard) {
    /** 普通路由快照保留原有构造方式；Agent 可额外绑定运行权及记忆版本检查。 */
    public PreparedUserAiChat(String model, UserAiFundingSource fundingSource,
                              Function<AiChatCommand, UserAiRoutedResult> invocation) {
        this(model, fundingSource, invocation, () -> {});
    }

    public PreparedUserAiChat {
        java.util.Objects.requireNonNull(invocation, "invocation");
        java.util.Objects.requireNonNull(guard, "guard");
    }

    /** 检索内的 Embedding 也能复用此检查，而不必发起一次聊天模型调用。 */
    public void validate() { guard.run(); }

    public UserAiRoutedResult generate(AiChatCommand command) {
        validate();
        UserAiRoutedResult result = invocation.apply(command);
        validate();
        return result;
    }
}
