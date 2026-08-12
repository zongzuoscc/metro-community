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
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.web.client.ResourceAccessException;

class OpenAiCompatibleAiChatGatewayContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOfficialProtocolAndMapsTextFinishReasonAndUsage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            requests.incrementAndGet();
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(readBody(exchange));
            respond(exchange, 200, successJson("answer", "stop", 12, 4));
        });

        withGateway(gateway -> {
            AiChatResult result = gateway.generate(new AiChatCommand(AiCapability.AGENT, List.of(
                    new AiPromptMessage(AiPromptRole.SYSTEM, "be concise"),
                    new AiPromptMessage(AiPromptRole.USER, "question"),
                    new AiPromptMessage(AiPromptRole.ASSISTANT, "earlier answer")), AiResponseMode.TEXT));

            assertThat(result).isEqualTo(new AiChatResult(
                    "answer", "stop", 12, 4, "qwen", "contract-chat"));
            assertThat(requests).hasValue(1);
            assertThat(method).hasValue("POST");
            assertThat(path).hasValue("/chat/completions");
            assertThat(authorization).hasValue("Bearer contract-key");
            assertThat(body.get()).doesNotContain("\"tools\"");
            JsonNode requestJson = readJson(body.get());
            assertThat(requestJson.path("model").asText()).isEqualTo("contract-chat");
            assertThat(requestJson.path("stream").asBoolean()).isFalse();
            assertThat(requestJson.path("messages")).hasSize(3);
            assertThat(requestJson.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(requestJson.path("messages").get(1).path("role").asText()).isEqualTo("user");
            assertThat(requestJson.path("messages").get(2).path("role").asText()).isEqualTo("assistant");
            assertThat(requestJson.has("temperature")).isFalse();
            assertThat(requestJson.has("max_tokens")).isFalse();
        });
    }

    @Test
    void mapsJsonObjectModeToOpenAiCompatibleResponseFormatWithoutTools() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 200, successJson("{}", "stop", 2, 1));
        });

        withGateway(gateway -> gateway.generate(new AiChatCommand(AiCapability.MODERATION,
                List.of(new AiPromptMessage(AiPromptRole.USER, "return json")), AiResponseMode.JSON_OBJECT)));

        JsonNode requestJson = readJson(body.get());
        assertThat(requestJson.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(requestJson.has("temperature")).isTrue();
        assertThat(requestJson.path("temperature").decimalValue()).isEqualByComparingTo("0.0");
        assertThat(requestJson.has("max_tokens")).isTrue();
        assertThat(requestJson.path("max_tokens").asInt()).isEqualTo(800);
        assertThat(requestJson.has("tools")).isFalse();
        assertThat(requestJson.has("tool_choice")).isFalse();
    }

    @Test
    void moderationOptionsFollowCapabilityWhileJsonFormatFollowsResponseMode() throws Exception {
        List<String> bodies = new ArrayList<>();
        start(exchange -> {
            bodies.add(readBody(exchange));
            respond(exchange, 200, successJson("{}", "stop", 2, 1));
        });

        withGateway(gateway -> {
            gateway.generate(new AiChatCommand(AiCapability.MODERATION,
                    List.of(new AiPromptMessage(AiPromptRole.USER, "classify")), AiResponseMode.TEXT));
            gateway.generate(new AiChatCommand(AiCapability.AGENT,
                    List.of(new AiPromptMessage(AiPromptRole.USER, "json")), AiResponseMode.JSON_OBJECT));
        });

        JsonNode moderationText = readJson(bodies.get(0));
        assertThat(moderationText.path("temperature").decimalValue()).isEqualByComparingTo("0.0");
        assertThat(moderationText.path("max_tokens").asInt()).isEqualTo(800);
        assertThat(moderationText.has("response_format")).isFalse();
        assertThat(moderationText.has("tools")).isFalse();

        JsonNode agentJson = readJson(bodies.get(1));
        assertThat(agentJson.path("response_format").path("type").asText())
                .isEqualTo("json_object");
        assertThat(agentJson.has("temperature")).isFalse();
        assertThat(agentJson.has("max_tokens")).isFalse();
        assertThat(agentJson.has("tools")).isFalse();
    }

    @Test
    void preservesLengthFinishReason() throws Exception {
        start(exchange -> respond(exchange, 200, successJson("partial", "length", 5, 6)));

        withGateway(gateway -> assertThat(gateway.generate(command()).finishReason()).isEqualTo("length"));
    }

    @Test
    void rejectsBlankChatTextAsEmptyResponse() throws Exception {
        start(exchange -> respond(exchange, 200, successJson("   ", "stop", 5, 1)));

        withGateway(gateway -> assertThatThrownBy(() -> gateway.generate(command()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.EMPTY_RESPONSE)));
    }

    @Test
    void rejectsMissingFinishReasonAsMalformedResponse() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"id":"chat-1","created":1,"model":"provider-model","choices":[
                  {"index":0,"message":{"role":"assistant","content":"answer"}}
                ],"usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}
                """));

        withGateway(gateway -> assertThatThrownBy(() -> gateway.generate(command()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.MALFORMED_RESPONSE)));
    }

    @Test
    void rejectsNegativeTokenMetadataAsMalformedResponse() throws Exception {
        start(exchange -> respond(exchange, 200, successJson("answer", "stop", -1, 2)));

        withGateway(gateway -> assertThatThrownBy(() -> gateway.generate(command()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.MALFORMED_RESPONSE)));
    }

    @Test
    void mapsHttpStatusesWithoutRetryOrProviderBodyLeakage() throws Exception {
        assertHttpFailure(429, AiProviderErrorReason.RATE_LIMITED);
        stopServer();
        server = null;
        assertHttpFailure(500, AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE);
        stopServer();
        server = null;
        assertHttpFailure(501, AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE);
        stopServer();
        server = null;
        assertHttpFailure(505, AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE);
        stopServer();
        server = null;
        assertHttpFailure(400, AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE);
    }

    @Test
    void mapsTruncatedSuccessBodyToMalformedWithoutRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            respondTruncated(exchange, "{\"id\":\"truncated\"");
        });

        withGateway(gateway -> assertThatThrownBy(() -> gateway.generate(command()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.MALFORMED_RESPONSE)));
        assertThat(requests).hasValue(1);
    }

    @Test
    void mapsConnectionFailure() throws IOException {
        withGateway("http://127.0.0.1:1", gateway ->
                assertThatThrownBy(() -> gateway.generate(command()))
                        .isInstanceOfSatisfying(AiProviderException.class,
                                error -> assertThat(error.reason())
                                        .isEqualTo(AiProviderErrorReason.CONNECTION_FAILURE)));
    }

    @Test
    void appliesCapabilitySpecificReadTimeoutWithoutShorteningLongerChatCapabilities() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            readBody(exchange);
            awaitDelay(400);
            respond(exchange, 200, successJson("answer", "stop", 2, 1));
        });

        withGateway(baseUrl(), gateway -> {
            long started = System.nanoTime();
            assertThatThrownBy(() -> gateway.generate(command()))
                    .isInstanceOfSatisfying(AiProviderException.class,
                            error -> assertThat(error.reason()).isEqualTo(AiProviderErrorReason.TIMEOUT));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofMillis(500));

            AiChatResult summary = gateway.generate(new AiChatCommand(AiCapability.ARTICLE_SUMMARY,
                    List.of(new AiPromptMessage(AiPromptRole.USER, "summarize")), AiResponseMode.TEXT));
            assertThat(summary.text()).isEqualTo("answer");
        },
                "metro.ai.runtime.provider-connect-timeout=PT0.1S",
                "metro.ai.runtime.provider-timeout-margin=PT0.1S",
                "metro.ai.agent.timeout=PT0.3S",
                "metro.ai.article-summary.timeout=PT1S");
        assertThat(requests).hasValue(2);
    }

    @Test
    void mapsTimeoutByThrowableTypeWithoutParsingExceptionText() {
        AiProviderException error = AiProviderException.fromTransport(
                new ResourceAccessException("misleading status 429", new SocketTimeoutException("read timed out")));

        assertThat(error.reason()).isEqualTo(AiProviderErrorReason.TIMEOUT);
        assertThat(error.httpStatus()).isEmpty();
    }

    @Test
    void mapsPrematureEofByThrowableTypeWithoutParsingExceptionText() {
        AiProviderException error = AiProviderException.fromTransport(
                new ResourceAccessException("misleading connection failure", new EOFException("truncated")));

        assertThat(error.reason()).isEqualTo(AiProviderErrorReason.MALFORMED_RESPONSE);
        assertThat(error.httpStatus()).isEmpty();
    }

    @Test
    void rejectsPlainHttpForNonLoopbackPlatformEndpoints() {
        OpenAiCompatibleAiChatGateway.HttpTransport transport = (uri, headers, body) ->
                new OpenAiCompatibleAiChatGateway.HttpResponse(200, "{}");

        assertThatThrownBy(() -> new OpenAiCompatibleAiChatGateway(
                Map.of(AiCapability.AGENT, transport), "http://example.com/v1",
                "secret", "qwen", "qwen-plus", 800))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsMalformedEmptyAndMissingChoicesResponses() throws Exception {
        assertResponseFailure("{not-json", AiProviderErrorReason.MALFORMED_RESPONSE);
        restart();
        assertResponseFailure("", AiProviderErrorReason.EMPTY_RESPONSE);
        restart();
        assertResponseFailure("{\"id\":\"x\",\"model\":\"contract-chat\"}",
                AiProviderErrorReason.EMPTY_RESPONSE);
        restart();
        assertResponseFailure("{\"id\":\"x\",\"model\":\"contract-chat\",\"choices\":[]}",
                AiProviderErrorReason.EMPTY_RESPONSE);
    }

    private void assertHttpFailure(int status, AiProviderErrorReason expectedReason) throws IOException {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            respond(exchange, status, "provider-secret-body");
        });

        withGateway(gateway -> assertThatThrownBy(() -> gateway.generate(command()))
                .isInstanceOfSatisfying(AiProviderException.class, error -> {
                    assertThat(error.reason()).isEqualTo(expectedReason);
                    assertThat(error.httpStatus()).contains(status);
                    assertThat(error.getMessage()).doesNotContain("provider-secret-body");
                }));
        assertThat(requests).hasValue(1);
    }

    private void assertResponseFailure(String response, AiProviderErrorReason expectedReason) throws IOException {
        start(exchange -> respond(exchange, 200, response));
        withGateway(gateway -> assertThatThrownBy(() -> gateway.generate(command()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        error -> assertThat(error.reason()).isEqualTo(expectedReason)));
    }

    private void restart() {
        stopServer();
        server = null;
    }

    private AiChatCommand command() {
        return new AiChatCommand(AiCapability.AGENT,
                List.of(new AiPromptMessage(AiPromptRole.USER, "hello")), AiResponseMode.TEXT);
    }

    private void withGateway(GatewayAssertion assertion) {
        withGateway(baseUrl(), assertion);
    }

    private void withGateway(String baseUrl, GatewayAssertion assertion, String... additionalProperties) {
        List<String> properties = new ArrayList<>(List.of(
                "metro.ai.enabled=true",
                "metro.ai.agent.enabled=true",
                "metro.ai.moderation.enabled=true",
                "metro.ai.platform.provider=qwen",
                "metro.ai.platform.base-url=" + baseUrl,
                "metro.ai.platform.api-key=contract-key",
                "metro.ai.platform.model=contract-chat"));
        properties.addAll(Arrays.asList(additionalProperties));
        new ApplicationContextRunner().withUserConfiguration(AiProviderConfiguration.class)
                .withPropertyValues(properties.toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertion.accept(context.getBean(AiChatGateway.class));
                });
    }

    private static void awaitDelay(long millis) {
        try {
            new CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
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

    private static String successJson(String text, String finishReason, int inputTokens, int outputTokens) {
        return """
                {"id":"chat-1","created":1,"model":"provider-model","choices":[
                  {"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"%s"}
                ],"usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}
                """.formatted(text, finishReason, inputTokens, outputTokens, inputTokens + outputTokens);
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
        void accept(AiChatGateway gateway) throws Exception;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
