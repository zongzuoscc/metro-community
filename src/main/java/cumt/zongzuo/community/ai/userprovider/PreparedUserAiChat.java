package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiChatCommand;
import java.util.function.Function;

/** 一次回答的路由快照。先按该模型计算预算，再用同一份配置发送，避免设置变更造成错配。 */
public record PreparedUserAiChat(String model, UserAiFundingSource fundingSource,
                                Function<AiChatCommand, UserAiRoutedResult> invocation) {
    public UserAiRoutedResult generate(AiChatCommand command) { return invocation.apply(command); }
}
