package cumt.zongzuo.community.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cumt.zongzuo.community.ai.config.AiProviderConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaEmbeddingGatewayContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOneOfficialBatchRequestAndPreservesVectorOrder() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            requests.incrementAndGet();
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            body.set(readBody(exchange));
            respond(exchange, 200, embeddingsJson(vector(0.25f, 1024), vector(-0.5f, 1024)));
        });

        withGateway(gateway -> {
            EmbeddingResult result = gateway.embed(new EmbeddingCommand(
                    AiCapability.EMBEDDING, List.of("first", "second")));

            assertThat(result.provider()).isEqualTo("ollama");
            assertThat(result.model()).isEqualTo("contract-embed");
            assertThat(result.vectors()).hasSize(2);
            assertThat(result.vectors().get(0)).hasSize(1024).containsOnly(0.25f);
            assertThat(result.vectors().get(1)).hasSize(1024).containsOnly(-0.5f);
            assertThat(requests).hasValue(1);
            assertThat(method).hasValue("POST");
            assertThat(path).hasValue("/api/embed");
            JsonNode requestJson = readJson(body.get());
            assertThat(requestJson.path("model").asText()).isEqualTo("contract-embed");
            assertThat(requestJson.path("input").get(0).asText()).isEqualTo("first");
            assertThat(requestJson.path("input").get(1).asText()).isEqualTo("second");
            assertThat(requestJson.has("dimensions")).isFalse();
        });
    }

    @Test
    void mapsHttpStatusesWithoutRetryOrProviderBodyLeakage() throws Exception {
        assertHttpFailure(429, AiProviderErrorReason.RATE_LIMITED);
        restart();
        assertHttpFailure(503, AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE);
        restart();
        assertHttpFailure(501, AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE);
        restart();
        assertHttpFailure(505, AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE);
        restart();
        assertHttpFailure(404, AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE);
    }

    @Test
    void mapsTruncatedSuccessBodyToMalformedWithoutRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            respondTruncated(exchange, "{\"model\":\"contract-embed\"");
        });

        withGateway(gateway -> assertThatThrownBy(() -> gateway.embed(command("one")))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.MALFORMED_RESPONSE)));
        assertThat(requests).hasValue(1);
    }

    @Test
    void mapsConnectionFailure() throws IOException {
        withGateway("http://127.0.0.1:1", gateway ->
                assertThatThrownBy(() -> gateway.embed(command("one")))
                        .isInstanceOfSatisfying(AiProviderException.class,
                                error -> assertThat(error.reason())
                                        .isEqualTo(AiProviderErrorReason.CONNECTION_FAILURE)));
    }

    @Test
    void rejectsMalformedAndEmptyResponses() throws Exception {
        assertResponseFailure("{not-json", AiProviderErrorReason.MALFORMED_RESPONSE);
        restart();
        assertResponseFailure("", AiProviderErrorReason.EMPTY_RESPONSE);
        restart();
        assertResponseFailure("{\"model\":\"contract-embed\"}", AiProviderErrorReason.EMPTY_RESPONSE);
        restart();
        assertResponseFailure("{\"model\":\"contract-embed\",\"embeddings\":[]}",
                AiProviderErrorReason.EMPTY_RESPONSE);
    }

    @Test
    void rejectsEmbeddingCountMismatch() throws Exception {
        start(exchange -> respond(exchange, 200, embeddingsJson(vector(1, 1024))));

        withGateway(gateway -> assertThatThrownBy(() -> gateway.embed(command("one", "two")))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.MALFORMED_RESPONSE)));
    }

    @Test
    void rejectsAnyVectorThatIsNotExactly1024Dimensions() throws Exception {
        start(exchange -> respond(exchange, 200, embeddingsJson(vector(1, 1023))));

        withGateway(gateway -> assertThatThrownBy(() -> gateway.embed(command("one")))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.MALFORMED_RESPONSE)));
    }

    @Test
    void rejectsNonFiniteVectorValues() throws Exception {
        start(exchange -> respond(exchange, 200,
                "{\"model\":\"contract-embed\",\"embeddings\":[[\"NaN\"" + ",0".repeat(1023) + "]]}"));

        withGateway(gateway -> assertThatThrownBy(() -> gateway.embed(command("one")))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.MALFORMED_RESPONSE)));
    }

    private void assertHttpFailure(int status, AiProviderErrorReason expectedReason) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            respond(exchange, status, "provider-secret-body");
        });

        withGateway(gateway -> assertThatThrownBy(() -> gateway.embed(command("one")))
                .isInstanceOfSatisfying(AiProviderException.class, error -> {
                    assertThat(error.reason()).isEqualTo(expectedReason);
                    assertThat(error.httpStatus()).contains(status);
                    assertThat(error.getMessage()).doesNotContain("provider-secret-body");
                }));
        assertThat(requests).hasValue(1);
    }

    private void assertResponseFailure(String response, AiProviderErrorReason expectedReason) throws IOException {
        start(exchange -> respond(exchange, 200, response));
        withGateway(gateway -> assertThatThrownBy(() -> gateway.embed(command("one")))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(expectedReason)));
    }

    private EmbeddingCommand command(String... inputs) {
        return new EmbeddingCommand(AiCapability.EMBEDDING, List.of(inputs));
    }

    private void restart() {
        stopServer();
        server = null;
    }

    private void withGateway(GatewayAssertion assertion) {
        withGateway(baseUrl(), assertion);
    }

    private void withGateway(String baseUrl, GatewayAssertion assertion) {
        new ApplicationContextRunner().withUserConfiguration(AiProviderConfiguration.class)
                .withPropertyValues(
                        "metro.ai.enabled=true",
                        "metro.ai.embedding.enabled=true",
                        "metro.ai.ollama.base-url=" + baseUrl,
                        "metro.ai.ollama.model=contract-embed")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertion.accept(context.getBean(EmbeddingGateway.class));
                });
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            try {
                handler.handle(exchange);
            }
            finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        }
        catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static float[] vector(float value, int dimensions) {
        float[] vector = new float[dimensions];
        java.util.Arrays.fill(vector, value);
        return vector;
    }

    private String embeddingsJson(float[]... vectors) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "model", "provider-model", "embeddings", vectors));
        }
        catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void respondTruncated(HttpExchange exchange, String partialBody) throws IOException {
        byte[] bytes = partialBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length + 100L);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface GatewayAssertion {
        void accept(EmbeddingGateway gateway) throws Exception;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
