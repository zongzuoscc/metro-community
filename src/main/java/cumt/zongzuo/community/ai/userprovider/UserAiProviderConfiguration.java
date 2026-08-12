package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.agent.AgentPageCapabilityService;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.article.service.PublishedArticleReadService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 组装平台回退、用户凭据和页面快捷能力，不改变原有模型网关的默认启动条件。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserAiProviderProperties.class)
public class UserAiProviderConfiguration {

    @Bean
    AiProviderEndpointPolicy aiProviderEndpointPolicy() {
        return new AiProviderEndpointPolicy();
    }

    @Bean
    UserAiProviderService userAiProviderService(UserAiProviderMapper mapper,
                                                AiProviderEndpointPolicy endpoints,
                                                UserAiProviderProperties properties) {
        properties.validate();
        if (!properties.isEnabled()) {
            return new DisabledUserAiProviderService(mapper, endpoints);
        }
        return new UserAiProviderService(mapper,
                new UserAiCredentialCipher(properties.getCredentialMasterKey()), endpoints);
    }

    @Bean
    UserOpenAiCompatibleGateway userOpenAiCompatibleGateway(
            AiProviderEndpointPolicy endpoints, UserAiProviderProperties properties) {
        return new UserOpenAiCompatibleGateway(endpoints,
                UserOpenAiCompatibleGateway.pinnedTransport(properties.getConnectTimeout(),
                        properties.getRequestTimeout()));
    }

    @Bean
    UserAiChatRouter userAiChatRouter(AiChatGateway platformGateway,
                                      UserAiProviderService settings,
                                      UserOpenAiCompatibleGateway userGateway,
                                      UserAiProviderProperties properties) {
        if (!properties.isEnabled()) {
            return (userId, command) -> new UserAiRoutedResult(platformGateway.generate(command),
                    UserAiFundingSource.PLATFORM);
        }
        return new DefaultUserAiChatRouter(platformGateway, settings, userGateway);
    }

    @Bean
    AgentPageCapabilityService agentPageCapabilityService(PublishedArticleReadService articles,
                                                          UserAiChatRouter router,
                                                          AiCapabilityExecutor executor,
                                                          MetroAiProperties properties,
                                                          Clock clock) {
        return new AgentPageCapabilityService(articles, router, executor, clock,
                properties.getArticleSummary().getTimeout(), properties.getWriting().getTimeout(),
                properties.getArticleSummary().getMaxInputCharacters(),
                properties.getWriting().getMaxInputCharacters());
    }

    /**
     * 关闭 BYOK 时仍保留只读“平台额度”视图，但任何保存动作都明确失败，避免生成不可解密密文。
     */
    public static class DisabledUserAiProviderService extends UserAiProviderService {
        public DisabledUserAiProviderService(UserAiProviderMapper mapper,
                                             AiProviderEndpointPolicy endpoints) {
            super(mapper, new UserAiCredentialCipher(java.util.Base64.getEncoder()
                    .encodeToString(new byte[32])), endpoints);
        }

        @Override
        public UserAiProviderView find(long userId) {
            return UserAiProviderView.platformDefault();
        }

        @Override
        public java.util.Optional<UserAiProviderRecord> findEnabledRecord(long userId) {
            return java.util.Optional.empty();
        }

        @Override
        public UserAiProviderView save(long userId, UserAiProviderSaveRequest request) {
            throw new IllegalStateException("用户自带 AI API 功能尚未启用");
        }
    }
}
