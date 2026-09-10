package cumt.zongzuo.community.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 真实阻塞 HTTP 读取不能钉住 carrier，否则模型虽持续产出，客户端 SSE 仍会被饿死。 */
class SpringAiHttpTransportConcurrencyTest {
    @Test
    void officialModelCallbacksCanWaitWithoutPinningCarriers() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (var body = exchange.getResponseBody()) {
                        body.write(
                                ("data:"
                                     + " {\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"first\"}}]}\n\n"
                                     + "data:"
                                     + " {\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"last\"},\"finish_reason\":\"stop\"}]}\n\n"
                                     + "data: [DONE]\n\n")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                });
        server.start();
        var recordingPath = Files.createTempFile("native-model-callback-pinning-", ".jfr");
        try (var recording = new Recording()) {
            recording
                    .enable("jdk.VirtualThreadPinned")
                    .withThreshold(Duration.ofMillis(10))
                    .withStackTrace();
            recording.start();
            var gateway =
                    new OpenAiCompatibleAiChatGateway(
                            java.util.Map.of(
                                    AiCapability.AGENT,
                                    OpenAiCompatibleAiChatGateway.httpTransport(
                                            Duration.ofSeconds(1), Duration.ofSeconds(3))),
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "key",
                            "test",
                            "test-model",
                            800);
            gateway.stream(
                    new AiChatCommand(
                            AiCapability.AGENT,
                            java.util.List.of(new AiPromptMessage(AiPromptRole.USER, "hello")),
                            AiResponseMode.TEXT),
                    text -> {
                        java.util.concurrent.locks.LockSupport.parkNanos(40_000_000L);
                    });
            recording.stop();
            recording.dump(recordingPath);
            var pins =
                    RecordingFile.readAllEvents(recordingPath).stream()
                            .filter(event -> event.getStackTrace() != null)
                            .filter(
                                    event ->
                                            event.getStackTrace().getFrames().stream()
                                                    .anyMatch(
                                                            frame ->
                                                                    frame.getMethod()
                                                                            .getType()
                                                                            .getName()
                                                                            .equals(
                                                                                    SpringAiModels
                                                                                            .class
                                                                                            .getName())))
                            .toList();
            assertThat(
                            pins.stream()
                                    .map(event -> event.getStackTrace().getFrames().toString())
                                    .toList())
                    .as("官方模型回调边界不能把业务等待包在 monitor 内")
                    .isEmpty();
        } finally {
            server.stop(0);
            Files.deleteIfExists(recordingPath);
        }
    }

    @Test
    void concurrentSilentReadsDoNotPinVirtualThreadCarriers() throws Exception {
        var firstBytes = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var complete = new CountDownLatch(4);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext(
                "/stream",
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    try (var body = exchange.getResponseBody()) {
                        body.write(1);
                        body.flush();
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.start();
        var recordingPath = Files.createTempFile("native-stream-pinning-", ".jfr");
        try (var recording = new Recording()) {
            recording
                    .enable("jdk.VirtualThreadPinned")
                    .withThreshold(Duration.ofMillis(10))
                    .withStackTrace();
            recording.start();
            var client = SpringAiHttpTransport.clientBuilder().build();
            var transport = new SpringAiHttpTransport(uri -> client);
            var endpoint =
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stream");
            for (int i = 0; i < 4; i++) {
                transport
                        .connect(HttpMethod.POST, endpoint, request -> request.setComplete())
                        .flatMapMany(response -> response.getBody())
                        .doFinally(signal -> complete.countDown())
                        .subscribe(
                                buffer -> {
                                    DataBufferUtils.release(buffer);
                                    firstBytes.countDown();
                                });
            }
            assertThat(firstBytes.await(3, TimeUnit.SECONDS)).isTrue();
            // 让下一次真实 socket read 保持阻塞，JFR 只在其返回后写出 pinning 事件。
            Thread.sleep(150);
            release.countDown();
            assertThat(complete.await(3, TimeUnit.SECONDS)).isTrue();
            recording.stop();
            recording.dump(recordingPath);
            var transportPins =
                    RecordingFile.readAllEvents(recordingPath).stream()
                            .filter(event -> event.getStackTrace() != null)
                            .filter(
                                    event ->
                                            event.getStackTrace().getFrames().stream()
                                                    .anyMatch(
                                                            frame ->
                                                                    frame.getMethod()
                                                                            .getType()
                                                                            .getName()
                                                                            .startsWith(
                                                                                    SpringAiHttpTransport
                                                                                            .class
                                                                                            .getName())))
                            .toList();
            assertThat(transportPins).as("HTTP 桥接阻塞读取不得持有 monitor 钉住 carrier").isEmpty();
        } finally {
            release.countDown();
            server.stop(0);
            handlers.shutdownNow();
            Files.deleteIfExists(recordingPath);
        }
    }
}
