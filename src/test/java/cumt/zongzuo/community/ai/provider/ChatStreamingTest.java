package cumt.zongzuo.community.ai.provider;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** 服务端故意不结束响应：收到首段的断言必须在放行尾段前成立，防止全量切片伪流式。 */
class ChatStreamingTest {
    @Test
    void platformStreamRejectsActualModelMismatchBeforeForwardingText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (var out = exchange.getResponseBody()) {
                        out.write(
                                ("data:"
                                     + " {\"model\":\"wrong-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"wrong\"},\"finish_reason\":\"stop\"}]}\n\n"
                                     + "data: [DONE]\n\n")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                });
        server.start();
        try {
            var gateway =
                    new OpenAiCompatibleAiChatGateway(
                            Map.of(
                                    AiCapability.AGENT,
                                    OpenAiCompatibleAiChatGateway.httpTransport(
                                            Duration.ofSeconds(1), Duration.ofSeconds(3))),
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "key",
                            "test",
                            "test-model",
                            800);
            var parts = new java.util.ArrayList<String>();
            assertThatThrownBy(
                            () ->
                                    gateway.stream(
                                            new AiChatCommand(
                                                    AiCapability.AGENT,
                                                    List.of(
                                                            new AiPromptMessage(
                                                                    AiPromptRole.USER, "hello")),
                                                    AiResponseMode.TEXT),
                                            parts::add))
                    .isInstanceOfSatisfying(
                            AiProviderException.class,
                            error ->
                                    assertThat(error.reason())
                                            .isEqualTo(AiProviderErrorReason.MALFORMED_RESPONSE));
            assertThat(parts).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {302, 429, 500})
    void streamingHttpErrorsKeepStatusAndNeverExposeProviderBody(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/plain");
                    byte[] body =
                            "provider-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            var gateway =
                    new OpenAiCompatibleAiChatGateway(
                            Map.of(
                                    AiCapability.AGENT,
                                    OpenAiCompatibleAiChatGateway.httpTransport(
                                            Duration.ofSeconds(1), Duration.ofSeconds(3))),
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "key",
                            "test",
                            "test-model",
                            800);
            assertThatThrownBy(
                            () ->
                                    gateway.stream(
                                            new AiChatCommand(
                                                    AiCapability.AGENT,
                                                    List.of(
                                                            new AiPromptMessage(
                                                                    AiPromptRole.USER, "hello")),
                                                    AiResponseMode.TEXT),
                                            text -> {}))
                    .isInstanceOfSatisfying(
                            AiProviderException.class,
                            error -> {
                                assertThat(error.httpStatus()).contains(status);
                                assertThat(error.getMessage()).doesNotContain("provider-secret");
                            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void finalUsageOnlyFrameDoesNotEraseFinishReasonOrRepeatText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (var out = exchange.getResponseBody()) {
                        out.write(
                                ("data:"
                                     + " {\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"正文\"},\"finish_reason\":\"length\"}]}\n\n"
                                     + "data:"
                                     + " {\"model\":\"test-model\",\"choices\":[],\"usage\":{\"prompt_tokens\":13,\"completion_tokens\":7,\"total_tokens\":20}}\n\n"
                                     + "data: [DONE]\n\n")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                });
        server.start();
        try {
            var gateway =
                    new OpenAiCompatibleAiChatGateway(
                            Map.of(
                                    AiCapability.AGENT,
                                    OpenAiCompatibleAiChatGateway.httpTransport(
                                            Duration.ofSeconds(1), Duration.ofSeconds(3))),
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "key",
                            "test",
                            "test-model",
                            800);
            var parts = new java.util.ArrayList<String>();
            var result =
                    gateway.stream(
                            new AiChatCommand(
                                    AiCapability.AGENT,
                                    List.of(new AiPromptMessage(AiPromptRole.USER, "hello")),
                                    AiResponseMode.TEXT),
                            parts::add);
            assertThat(parts).containsExactly("正文");
            assertThat(result.finishReason()).isEqualTo("length");
            assertThat(result.inputTokens()).isEqualTo(13);
            assertThat(result.outputTokens()).isEqualTo(7);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancelledSilentProviderIsClosedWithoutWaitingForNextToken() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.start();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var gateway =
                    new OpenAiCompatibleAiChatGateway(
                            Map.of(
                                    AiCapability.AGENT,
                                    OpenAiCompatibleAiChatGateway.httpTransport(
                                            Duration.ofSeconds(2), Duration.ofSeconds(10))),
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "key",
                            "test",
                            "test-model",
                            1024);
            var result =
                    pool.submit(
                            () ->
                                    gateway.stream(
                                            new AiChatCommand(
                                                    AiCapability.AGENT,
                                                    List.of(
                                                            new AiPromptMessage(
                                                                    AiPromptRole.USER, "hello")),
                                                    AiResponseMode.TEXT),
                                            new AiStreamObserver() {
                                                public void onDelta(String text) {
                                                    throw new AssertionError("No content expected");
                                                }

                                                public void checkActive() {
                                                    if (!active.get())
                                                        throw new CancellationException(
                                                                "cancelled");
                                                }
                                            }));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                active.set(false);
                assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(CancellationException.class);
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void truncatedStreamDoesNotBecomeSuccessfulWholeAnswer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (var out = exchange.getResponseBody()) {
                        out.write(
                                "data: {\"choices\":[{\"delta\":{\"content\":\"部分正文\"}}]}\n\n"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                });
        server.start();
        try {
            var gateway =
                    new OpenAiCompatibleAiChatGateway(
                            Map.of(
                                    AiCapability.AGENT,
                                    OpenAiCompatibleAiChatGateway.httpTransport(
                                            Duration.ofSeconds(2), Duration.ofSeconds(5))),
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "key",
                            "test",
                            "test-model",
                            1024);
            var pieces = new java.util.ArrayList<String>();
            assertThatThrownBy(
                            () ->
                                    gateway.stream(
                                            new AiChatCommand(
                                                    AiCapability.AGENT,
                                                    List.of(
                                                            new AiPromptMessage(
                                                                    AiPromptRole.USER, "hello")),
                                                    AiResponseMode.TEXT),
                                            pieces::add))
                    .isInstanceOf(AiProviderException.class);
            assertThat(pieces).containsExactly("部分正文");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deliversContentBeforeProviderFinishes() throws Exception {
        var first = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var request = new CompletableFuture<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    request.complete(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (var out = exchange.getResponseBody()) {
                        out.write(
                                "data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}\n\n"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        out.flush();
                        // 官方模型存在一帧前瞻缓冲；第二帧后暂停，仍必须在流结束前交付正文。
                        out.write(
                                "data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":null}]}\n\n"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        out.flush();
                        try {
                            finish.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        out.write(
                                "data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"content\":\"世界\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                });
        server.start();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var gateway =
                    new OpenAiCompatibleAiChatGateway(
                            Map.of(
                                    AiCapability.AGENT,
                                    OpenAiCompatibleAiChatGateway.httpTransport(
                                            Duration.ofSeconds(2), Duration.ofSeconds(10))),
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "test-key",
                            "test",
                            "test-model",
                            1024);
            var pieces = new CopyOnWriteArrayList<String>();
            var result =
                    pool.submit(
                            () ->
                                    gateway.stream(
                                            new AiChatCommand(
                                                    AiCapability.AGENT,
                                                    List.of(
                                                            new AiPromptMessage(
                                                                    AiPromptRole.USER, "hello")),
                                                    AiResponseMode.TEXT),
                                            new AiStreamObserver() {
                                                public void onDelta(String text) {
                                                    pieces.add(text);
                                                    first.countDown();
                                                }
                                            }));
            try {
                assertThat(first.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(result.isDone()).isFalse();
                assertThat(pieces).containsExactly("你好");
                assertThat(request.get()).contains("\"stream\":true");
            } finally {
                finish.countDown();
            }
            assertThat(result.get(5, TimeUnit.SECONDS).text()).isEqualTo("你好世界");
        } finally {
            finish.countDown();
            server.stop(0);
        }
    }
}
