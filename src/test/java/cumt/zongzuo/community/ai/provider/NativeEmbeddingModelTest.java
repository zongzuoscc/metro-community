package cumt.zongzuo.community.ai.provider;

import static org.assertj.core.api.Assertions.*;

import cumt.zongzuo.community.ai.runtime.AiTokenUsageExtractor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.embedding.*;

import java.util.List;

class NativeEmbeddingModelTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "[{\"index\":0,\"embedding\":[1]}]",
                "[{\"index\":1,\"embedding\":[1,0]}]",
                "[{\"index\":0,\"embedding\":[\"1\",0]}]",
                "[{\"index\":0,\"embedding\":[1e100,0]}]",
                "[{\"index\":0,\"embedding\":null}]"
            })
    void malformedVectorsNeverEnterTheIndex(String data) {
        var gateway =
                new OpenAiCompatibleEmbeddingGateway(
                        (uri, headers, body) ->
                                new OpenAiCompatibleEmbeddingGateway.HttpResponse(
                                        200,
                                        "{\"model\":\"embedding-model\",\"data\":" + data + "}"),
                        "https://example.com/v1",
                        "key",
                        "test",
                        "embedding-model",
                        2);
        assertThatThrownBy(
                        () ->
                                gateway.embed(
                                        new EmbeddingCommand(
                                                AiCapability.EMBEDDING, List.of("text"))))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    void standardEmbeddingRetainsUsageAndConfiguredDimensions() {
        EmbeddingGateway gateway =
                new OpenAiCompatibleEmbeddingGateway(
                        (uri, headers, body) ->
                                new OpenAiCompatibleEmbeddingGateway.HttpResponse(
                                        200,
                                        "{\"model\":\"embedding-model\",\"data\":[{\"index\":0,\"embedding\":[1,0]}],\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}"),
                        "https://example.com/v1",
                        "key",
                        "test",
                        "embedding-model",
                        2);
        assertThat(gateway).isInstanceOf(EmbeddingModel.class);
        var response =
                ((EmbeddingModel) gateway)
                        .call(
                                new EmbeddingRequest(
                                        List.of("text"), EmbeddingOptions.builder().build()));
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(7);
        assertThat(response.getResult().getOutput()).containsExactly(1, 0);
        assertThat(((EmbeddingModel) gateway).dimensions()).isEqualTo(2);
    }

    @Test
    void nativeChatUsageParticipatesInExistingBudgetAccounting() {
        var response =
                new ChatResponse(
                        List.of(),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(3, 5, 8)).build());
        assertThat(new AiTokenUsageExtractor().totalTokens(response)).hasValue(8);
    }
}
