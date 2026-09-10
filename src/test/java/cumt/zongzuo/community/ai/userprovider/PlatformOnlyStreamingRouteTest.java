package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 捕获真实部署中 BYOK 关闭时仍应能够增量输出的平台路由分支。 */
class PlatformOnlyStreamingRouteTest {
    @Test
    void disabledByokKeepsPlatformStreamingWithoutCallingSynchronousGeneration() {
        var pieces = new ArrayList<String>();
        AiChatGateway platform = new AiChatGateway() {
            public AiChatResult generate(AiChatCommand command) {
                throw new AssertionError("流式对话不能进入同步生成");
            }
            public AiChatResult stream(AiChatCommand command, AiStreamObserver observer) {
                observer.onDelta("实时前缀");
                assertThat(pieces).containsExactly("实时前缀");
                observer.onDelta("后缀");
                return new AiChatResult("实时前缀后缀", "stop", 1, 2, "qwen", "qwen-plus");
            }
        };
        var config = new UserAiProviderConfiguration();
        var properties = new UserAiProviderProperties();
        properties.setEnabled(false);
        var settings = config.userAiProviderService(mock(UserAiProviderMapper.class),
                new AiProviderEndpointPolicy(), properties);
        var router = config.userAiChatRouter(platform, settings, mock(UserOpenAiCompatibleGateway.class), properties);
        var command = new AiChatCommand(AiCapability.AGENT,
                List.of(new AiPromptMessage(AiPromptRole.USER, "测试")), AiResponseMode.JSON_OBJECT);
        var result = router.prepare(2L, "qwen-plus").stream(command, pieces::add);
        assertThat(result.result().text()).isEqualTo("实时前缀后缀");
        assertThat(result.fundingSource()).isEqualTo(UserAiFundingSource.PLATFORM);
        assertThat(pieces).containsExactly("实时前缀", "后缀");
    }
}
