package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiChatGateway;

/** 按用户设置选择费用来源，未配置或已关闭时保留平台基础能力。 */
public final class DefaultUserAiChatRouter implements UserAiChatRouter {

    private final AiChatGateway platformGateway;
    private final UserAiProviderService settings;
    private final UserOpenAiCompatibleGateway userGateway;

    public DefaultUserAiChatRouter(AiChatGateway platformGateway, UserAiProviderService settings,
                                   UserOpenAiCompatibleGateway userGateway) {
        this.platformGateway = platformGateway;
        this.settings = settings;
        this.userGateway = userGateway;
    }

    @Override
    public UserAiRoutedResult generate(long userId, AiChatCommand command) {
        return settings.findEnabledRecord(userId)
                .map(record -> new UserAiRoutedResult(userGateway.generate(record,
                        settings.decryptApiKey(record), command), UserAiFundingSource.USER))
                .orElseGet(() -> new UserAiRoutedResult(platformGateway.generate(command),
                        UserAiFundingSource.PLATFORM));
    }

    @Override
    public PreparedUserAiChat prepare(long userId, String platformModel) {
        return settings.findEnabledRecord(userId).map(record -> {
            // Mapper 对象可变，复制调用所需字段，避免检索期间切换模型后预算与实际模型不一致。
            UserAiProviderRecord frozen = new UserAiProviderRecord();
            frozen.setUserId(record.getUserId());
            frozen.setProvider(record.getProvider());
            frozen.setBaseUrl(record.getBaseUrl());
            frozen.setModel(record.getModel());
            frozen.setEncryptedApiKey(record.getEncryptedApiKey());
            frozen.setEnabled(record.isEnabled());
            return new PreparedUserAiChat(frozen.getModel(), UserAiFundingSource.USER,
                    command -> new UserAiRoutedResult(userGateway.generate(frozen,
                            settings.decryptApiKey(frozen), command), UserAiFundingSource.USER));
        }).orElseGet(() -> new PreparedUserAiChat(platformModel, UserAiFundingSource.PLATFORM,
                command -> new UserAiRoutedResult(platformGateway.generate(command),
                        UserAiFundingSource.PLATFORM)));
    }
}
