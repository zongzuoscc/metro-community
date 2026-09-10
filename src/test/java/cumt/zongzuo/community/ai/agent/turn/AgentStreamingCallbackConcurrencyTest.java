package cumt.zongzuo.community.ai.agent.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 回调已经转到虚拟线程；路由栅栏/事件存储的阻塞等待不能持有 monitor。 */
class AgentStreamingCallbackConcurrencyTest {
    @Test
    void failedGuardReleasesLockAndDoesNotAdvanceThrottle() {
        var checks = new AtomicInteger();
        var route =
                new PreparedUserAiChat(
                        "test",
                        UserAiFundingSource.PLATFORM,
                        command -> null,
                        () -> {
                            if (checks.incrementAndGet() == 2)
                                throw new java.util.concurrent.CancellationException("changed");
                        },
                        (command, observer) -> {
                            assertThatThrownBy(observer::checkActive)
                                    .isInstanceOf(java.util.concurrent.CancellationException.class);
                            var next = new java.util.concurrent.CompletableFuture<Void>();
                            Thread.startVirtualThread(
                                    () -> {
                                        try {
                                            observer.checkActive();
                                            next.complete(null);
                                        } catch (Throwable error) {
                                            next.completeExceptionally(error);
                                        }
                                    });
                            assertThat(next).succeedsWithin(Duration.ofSeconds(2));
                            return null;
                        });
        route.stream(null, text -> {});
        assertThat(checks).hasValue(4);
    }

    @Test
    void failedEventWriteReleasesLockAndKeepsUnpersistedBuffer() {
        var events = mock(AgentTurnEventStore.class);
        when(events.append(anyLong(), anyLong(), any(), anyLong(), eq("delta"), anyMap()))
                .thenThrow(new IllegalStateException("unavailable"))
                .thenReturn("1-0");
        var answer = new AgentAnswerStream(events, 1, 2, UUID.randomUUID(), 3);
        assertThatThrownBy(() -> answer.accept("pending"))
                .isInstanceOf(IllegalStateException.class);
        var next = new java.util.concurrent.CompletableFuture<Void>();
        Thread.startVirtualThread(
                () -> {
                    try {
                        answer.flush();
                        next.complete(null);
                    } catch (Throwable error) {
                        next.completeExceptionally(error);
                    }
                });
        assertThat(next).succeedsWithin(Duration.ofSeconds(2));
        verify(events, times(2))
                .append(
                        anyLong(),
                        anyLong(),
                        any(),
                        anyLong(),
                        eq("delta"),
                        argThat(payload -> "pending".equals(payload.get("textAppend"))));
    }

    @Test
    void guardedChecksAndEventWritesDoNotPinAndKeepThrottlingAndBuffering() throws Exception {
        var checks = new AtomicInteger();
        var route =
                new PreparedUserAiChat(
                        "test",
                        UserAiFundingSource.PLATFORM,
                        command -> null,
                        () -> {
                            checks.incrementAndGet();
                            slowIo();
                        },
                        (command, observer) -> {
                            observer.checkActive();
                            observer.checkActive();
                            observer.onDelta("one");
                            return null;
                        });
        var events = mock(AgentTurnEventStore.class);
        when(events.append(anyLong(), anyLong(), any(), anyLong(), eq("delta"), anyMap()))
                .thenAnswer(
                        invocation -> {
                            slowIo();
                            return "1-0";
                        });
        var answer = new AgentAnswerStream(events, 1, 2, UUID.randomUUID(), 3);
        var recordingPath = Files.createTempFile("native-callback-pinning-", ".jfr");
        try (var recording = new Recording()) {
            recording
                    .enable("jdk.VirtualThreadPinned")
                    .withThreshold(Duration.ofMillis(10))
                    .withStackTrace();
            recording.start();
            Thread task =
                    Thread.startVirtualThread(
                            () -> {
                                route.stream(null, text -> {});
                                answer.accept("first");
                                answer.accept("tail");
                                answer.flush();
                            });
            task.join(Duration.ofSeconds(5));
            assertThat(task.isAlive()).isFalse();
            recording.stop();
            recording.dump(recordingPath);
            var pins =
                    RecordingFile.readAllEvents(recordingPath).stream()
                            .filter(event -> event.getStackTrace() != null)
                            .filter(
                                    event ->
                                            event.getStackTrace().getFrames().stream()
                                                    .anyMatch(
                                                            frame -> {
                                                                String type =
                                                                        frame.getMethod()
                                                                                .getType()
                                                                                .getName();
                                                                return type.startsWith(
                                                                                PreparedUserAiChat
                                                                                        .class
                                                                                        .getName())
                                                                        || type.equals(
                                                                                AgentAnswerStream
                                                                                        .class
                                                                                        .getName());
                                                            }))
                            .toList();
            assertThat(pins).as("流式回调的运行权检查与 Redis 写入不得 pin carrier").isEmpty();
            assertThat(checks).hasValue(3);
            verify(events, times(2))
                    .append(anyLong(), anyLong(), any(), anyLong(), eq("delta"), anyMap());
        } finally {
            Files.deleteIfExists(recordingPath);
        }
    }

    private static void slowIo() {
        try {
            Thread.sleep(40);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(error);
        }
    }
}
