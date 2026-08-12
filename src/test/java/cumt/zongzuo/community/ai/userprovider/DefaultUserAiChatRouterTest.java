package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.AiChatResult;
import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.provider.AiPromptRole;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultUserAiChatRouterTest {

    private static final AiChatCommand COMMAND = new AiChatCommand(AiCapability.WRITING,
            List.of(new AiPromptMessage(AiPromptRole.USER, "请润色")), AiResponseMode.TEXT);

    @Test
    void enabledUserCredentialPaysForTheCallAndPlatformIsNotInvoked() {
        AiChatGateway platform = mock(AiChatGateway.class);
        UserAiProviderService settings = mock(UserAiProviderService.class);
        UserOpenAiCompatibleGateway userGateway = mock(UserOpenAiCompatibleGateway.class);
        UserAiProviderRecord row = configured();
        when(settings.findEnabledRecord(7L)).thenReturn(Optional.of(row));
        when(settings.decryptApiKey(row)).thenReturn("secret");
        AiChatResult answer = new AiChatResult("完成", "stop", 10, 4, "openai", "gpt-4.1-mini");
        when(userGateway.generate(row, "secret", COMMAND)).thenReturn(answer);
        DefaultUserAiChatRouter router = new DefaultUserAiChatRouter(platform, settings, userGateway);

        UserAiRoutedResult result = router.generate(7L, COMMAND);

        assertThat(result.fundingSource()).isEqualTo(UserAiFundingSource.USER);
        assertThat(result.result()).isSameAs(answer);
        verify(platform, never()).generate(COMMAND);
    }

    @Test
    void missingOrDisabledCredentialUsesThePlatformBasicQuotaPath() {
        AiChatGateway platform = mock(AiChatGateway.class);
        UserAiProviderService settings = mock(UserAiProviderService.class);
        UserOpenAiCompatibleGateway userGateway = mock(UserOpenAiCompatibleGateway.class);
        when(settings.findEnabledRecord(7L)).thenReturn(Optional.empty());
        AiChatResult answer = new AiChatResult("平台回答", "stop", 10, 4,
                "deepseek", "deepseek-chat");
        when(platform.generate(COMMAND)).thenReturn(answer);
        DefaultUserAiChatRouter router = new DefaultUserAiChatRouter(platform, settings, userGateway);

        UserAiRoutedResult result = router.generate(7L, COMMAND);

        assertThat(result.fundingSource()).isEqualTo(UserAiFundingSource.PLATFORM);
        assertThat(result.result()).isSameAs(answer);
        verify(userGateway, never()).generate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static UserAiProviderRecord configured() {
        UserAiProviderRecord row = new UserAiProviderRecord();
        row.setUserId(7L);
        row.setProvider("OPENAI");
        row.setBaseUrl("https://api.openai.com/v1");
        row.setModel("gpt-4.1-mini");
        row.setEncryptedApiKey("ciphertext");
        row.setEnabled(true);
        return row;
    }
}
