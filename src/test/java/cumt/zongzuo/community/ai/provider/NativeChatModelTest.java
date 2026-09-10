package cumt.zongzuo.community.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

class NativeChatModelTest {
    @Test
    void platformSynchronousCallKeepsExistingConfiguredModelContract() {
        var gateway =
                new OpenAiCompatibleAiChatGateway(
                        Map.of(
                                AiCapability.AGENT,
                                (uri, headers, body) ->
                                        new OpenAiCompatibleAiChatGateway.HttpResponse(
                                                200,
                                                "{\"model\":\"provider-alias\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"answer\"}}]}")),
                        "https://example.com/v1",
                        "key",
                        "test",
                        "configured-model",
                        800);
        var result =
                gateway.generate(
                        new AiChatCommand(
                                AiCapability.AGENT,
                                java.util.List.of(new AiPromptMessage(AiPromptRole.USER, "hello")),
                                AiResponseMode.TEXT));
        assertThat(result.model()).isEqualTo("configured-model");
        assertThat(gateway.call(new Prompt("hello")).getMetadata().getModel())
                .isEqualTo("provider-alias");
    }

    @Test
    void standardToolCallIsReturnedIntactWithoutRequiringTextContent() {
        AiChatGateway gateway =
                new OpenAiCompatibleAiChatGateway(
                        Map.of(
                                AiCapability.AGENT,
                                (uri, headers, body) ->
                                        new OpenAiCompatibleAiChatGateway.HttpResponse(
                                                200,
                                                """
{"id":"native","model":"model","choices":[{"index":0,"finish_reason":"tool_calls",
"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function",
"function":{"name":"COMMUNITY_ARTICLES","arguments":"{\\\"query\\\":\\\"test\\\"}"}}]}}],
"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
""")),
                        "https://example.com/v1",
                        "key",
                        "test",
                        "model",
                        800);
        assertThat(gateway).isInstanceOf(ChatModel.class);
        var response = ((ChatModel) gateway).call(new Prompt("question"));
        assertThat(response.getResult().getOutput().getToolCalls())
                .singleElement()
                .satisfies(
                        call -> {
                            assertThat(call.id()).isEqualTo("call-1");
                            assertThat(call.name()).isEqualTo("COMMUNITY_ARTICLES");
                        });
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(8);
    }
}
