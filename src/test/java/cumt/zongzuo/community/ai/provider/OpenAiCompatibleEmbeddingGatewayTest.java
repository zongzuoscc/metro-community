package cumt.zongzuo.community.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import cumt.zongzuo.community.ai.config.AiProviderConfiguration;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleEmbeddingGatewayTest {

    @Test
    void providerConfigurationCanSelectThePlatformEmbeddingGateway() {
        new ApplicationContextRunner().withUserConfiguration(AiProviderConfiguration.class)
                .withPropertyValues(
                        "metro.ai.enabled=true",
                        "metro.ai.embedding.enabled=true",
                        "metro.ai.embedding.provider=platform",
                        "metro.ai.embedding.model=text-embedding-v4",
                        "metro.ai.embedding.dimensions=1024",
                        "metro.ai.platform.provider=qwen",
                        "metro.ai.platform.base-url=https://workspace.example.com/compatible-mode/v1",
                        "metro.ai.platform.api-key=secret-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmbeddingGateway.class))
                            .isInstanceOf(OpenAiCompatibleEmbeddingGateway.class);
                });
    }

    @Test
    void sendsTheOfficialBatchRequestAndRestoresProviderIndexOrder() throws Exception {
        AtomicReference<URI> uri = new AtomicReference<>();
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        OpenAiCompatibleEmbeddingGateway.HttpTransport transport = (target, requestHeaders, json) -> {
            uri.set(target);
            headers.set(requestHeaders);
            body.set(json);
            return new OpenAiCompatibleEmbeddingGateway.HttpResponse(200, """
                    {"model":"text-embedding-v4","data":[
                      {"object":"embedding","index":1,"embedding":[0.0,1.0]},
                      {"object":"embedding","index":0,"embedding":[1.0,0.0]}
                    ]}
                    """);
        };
        OpenAiCompatibleEmbeddingGateway gateway = new OpenAiCompatibleEmbeddingGateway(
                transport, "https://workspace.example.com/compatible-mode/v1",
                "secret-key", "qwen", "text-embedding-v4", 2);

        EmbeddingResult result = gateway.embed(new EmbeddingCommand(
                AiCapability.EMBEDDING, List.of("first", "second")));

        assertThat(uri.get().toString()).isEqualTo(
                "https://workspace.example.com/compatible-mode/v1/embeddings");
        assertThat(headers.get()).containsEntry("Authorization", "Bearer secret-key");
        JsonNode request = new ObjectMapper().readTree(body.get());
        assertThat(request.path("model").asText()).isEqualTo("text-embedding-v4");
        assertThat(request.path("dimensions").asInt()).isEqualTo(2);
        assertThat(request.path("encoding_format").asText()).isEqualTo("float");
        assertThat(request.path("input").get(0).asText()).isEqualTo("first");
        assertThat(result.vectors().get(0)).containsExactly(1F, 0F);
        assertThat(result.vectors().get(1)).containsExactly(0F, 1F);
        assertThat(result.provider()).isEqualTo("qwen");
    }
}
