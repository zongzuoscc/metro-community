package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.provider.AiPromptRole;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserOpenAiCompatibleGatewayTest {

    @Test
    void sendsACompatibleRequestWithoutLoggingOrReturningTheApiKey() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {"choices":[{"message":{"content":"润色完成"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":5},"model":"gpt-4.1-mini"}
                """);
        AiProviderEndpointPolicy endpointPolicy = new AiProviderEndpointPolicy(host ->
                List.of(InetAddress.getByName("203.0.113.20")));
        UserOpenAiCompatibleGateway gateway = new UserOpenAiCompatibleGateway(
                endpointPolicy, transport);
        UserAiProviderRecord setting = setting("https://api.openai.com/v1", "gpt-4.1-mini");

        var result = gateway.generate(setting, "sk-user-secret", new AiChatCommand(
                AiCapability.WRITING,
                List.of(new AiPromptMessage(AiPromptRole.SYSTEM, "系统"),
                        new AiPromptMessage(AiPromptRole.USER, "原文")),
                AiResponseMode.TEXT));

        assertThat(transport.uri).isEqualTo(URI.create("https://api.openai.com/v1/chat/completions"));
        assertThat(transport.approvedAddresses)
                .containsExactly(InetAddress.getByName("203.0.113.20"));
        assertThat(transport.headers).containsEntry("Authorization", "Bearer sk-user-secret");
        assertThat(transport.body).contains("\"model\":\"gpt-4.1-mini\"")
                .contains("\"role\":\"system\"").contains("\"content\":\"原文\"");
        assertThat(result.text()).isEqualTo("润色完成");
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(5);
        assertThat(result.toString()).doesNotContain("sk-user-secret");
    }

    @Test
    void jsonCapabilitiesRequestJsonObjectMode() throws Exception {
        CapturingTransport transport = new CapturingTransport(200, """
                {"choices":[{"message":{"content":"{}"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1},"model":"custom-model"}
                """);
        UserOpenAiCompatibleGateway gateway = new UserOpenAiCompatibleGateway(
                new AiProviderEndpointPolicy(host ->
                        List.of(InetAddress.getByName("203.0.113.20"))), transport);

        gateway.generate(setting("https://example.com/openai/v1", "custom-model"), "secret",
                new AiChatCommand(AiCapability.AGENT,
                        List.of(new AiPromptMessage(AiPromptRole.USER, "问题")),
                        AiResponseMode.JSON_OBJECT));

        assertThat(transport.body).contains("\"response_format\":{\"type\":\"json_object\"}");
    }

    private static UserAiProviderRecord setting(String baseUrl, String model) {
        UserAiProviderRecord row = new UserAiProviderRecord();
        row.setProvider("CUSTOM");
        row.setBaseUrl(baseUrl);
        row.setModel(model);
        return row;
    }

    private static final class CapturingTransport implements UserOpenAiCompatibleGateway.HttpTransport {
        private final int status;
        private final String response;
        private URI uri;
        private List<InetAddress> approvedAddresses;
        private Map<String, String> headers;
        private String body;

        private CapturingTransport(int status, String response) {
            this.status = status;
            this.response = response;
        }

        @Override
        public UserOpenAiCompatibleGateway.HttpResponse post(URI uri,
                                                             List<InetAddress> approvedAddresses,
                                                             Map<String, String> headers,
                                                             String body) {
            this.uri = uri;
            this.approvedAddresses = List.copyOf(approvedAddresses);
            this.headers = Map.copyOf(headers);
            this.body = body;
            return new UserOpenAiCompatibleGateway.HttpResponse(status, response);
        }
    }
}
