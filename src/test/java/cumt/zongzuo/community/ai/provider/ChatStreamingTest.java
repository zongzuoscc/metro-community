package cumt.zongzuo.community.ai.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 服务端故意不结束响应：收到首段的断言必须在放行尾段前成立，防止全量切片伪流式。 */
class ChatStreamingTest {
    @Test
    void cancelledSilentProviderIsClosedWithoutWaitingForNextToken() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var gateway = new OpenAiCompatibleAiChatGateway(Map.of(AiCapability.AGENT,
                    OpenAiCompatibleAiChatGateway.httpTransport(Duration.ofSeconds(2), Duration.ofSeconds(10))),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "key", "test", "test-model", 1024);
            var result = pool.submit(() -> gateway.stream(new AiChatCommand(AiCapability.AGENT,
                    List.of(new AiPromptMessage(AiPromptRole.USER, "hello")), AiResponseMode.TEXT),
                    new AiStreamObserver() {
                        public void onDelta(String text) { throw new AssertionError("No content expected"); }
                        public void checkActive() { if (!active.get()) throw new CancellationException("cancelled"); }
                    }));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                active.set(false);
                assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CancellationException.class);
            } finally { release.countDown(); }
        } finally { release.countDown(); server.stop(0); }
    }

    @Test
    void truncatedStreamDoesNotBecomeSuccessfulWholeAnswer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"部分正文\"}}]}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            var gateway = new OpenAiCompatibleAiChatGateway(Map.of(AiCapability.AGENT,
                    OpenAiCompatibleAiChatGateway.httpTransport(Duration.ofSeconds(2), Duration.ofSeconds(5))),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "key", "test", "test-model", 1024);
            var pieces = new java.util.ArrayList<String>();
            assertThatThrownBy(() -> gateway.stream(new AiChatCommand(AiCapability.AGENT,
                    List.of(new AiPromptMessage(AiPromptRole.USER, "hello")), AiResponseMode.TEXT), pieces::add))
                    .isInstanceOf(AiProviderException.class);
            assertThat(pieces).containsExactly("部分正文");
        } finally { server.stop(0); }
    }

    @Test
    void deliversContentBeforeProviderFinishes() throws Exception {
        var first = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var request = new CompletableFuture<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.complete(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                out.write("data: {\"model\":\"test-model\",\"choices\":[{\"delta\":{\"content\":\"世界\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        server.start();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var gateway = new OpenAiCompatibleAiChatGateway(Map.of(AiCapability.AGENT,
                    OpenAiCompatibleAiChatGateway.httpTransport(Duration.ofSeconds(2), Duration.ofSeconds(10))),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", "test", "test-model", 1024);
            var pieces = new CopyOnWriteArrayList<String>();
            var result = pool.submit(() -> gateway.stream(new AiChatCommand(AiCapability.AGENT,
                    List.of(new AiPromptMessage(AiPromptRole.USER, "hello")), AiResponseMode.TEXT),
                    new AiStreamObserver() {
                        public void onDelta(String text) { pieces.add(text); first.countDown(); }
                    }));
            try {
                assertThat(first.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(result.isDone()).isFalse();
                assertThat(pieces).containsExactly("你好");
                assertThat(request.get()).contains("\"stream\":true");
            } finally { finish.countDown(); }
            assertThat(result.get(5, TimeUnit.SECONDS).text()).isEqualTo("你好世界");
        } finally { finish.countDown(); server.stop(0); }
    }
}
