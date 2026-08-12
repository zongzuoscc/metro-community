package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiChatCommand;

/** 为一次用户交互选择平台模型或用户自有模型。 */
public interface UserAiChatRouter {

    UserAiRoutedResult generate(long userId, AiChatCommand command);
}
