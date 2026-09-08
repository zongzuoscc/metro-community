package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiChatCommand;

/** 为一次用户交互选择平台模型或用户自有模型。 */
public interface UserAiChatRouter {

    UserAiRoutedResult generate(long userId, AiChatCommand command);

    /** 平台固定路由的默认实现；可变 BYOK 路由需覆盖此方法以冻结配置。 */
    default PreparedUserAiChat prepare(long userId, String platformModel) {
        return new PreparedUserAiChat(platformModel, UserAiFundingSource.PLATFORM,
                command -> generate(userId, command));
    }
}
