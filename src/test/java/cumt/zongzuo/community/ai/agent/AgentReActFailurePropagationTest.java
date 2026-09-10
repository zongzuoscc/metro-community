package cumt.zongzuo.community.ai.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cumt.zongzuo.community.ai.agent.react.ReActDecisionException;
import cumt.zongzuo.community.ai.agent.temporary.DefaultTemporaryTurnRunner;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnAdmission;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnLifecycleService;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnStore;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmission;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnEventStore;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnFailureService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnFinalizer;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnLeaseService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRunner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证两个真实 runner 的错误落库参数、SSE 事件和日志契约。 */
class AgentReActFailurePropagationTest {

    @ParameterizedTest
    @MethodSource("failures")
    void keepsTypedCodesForSnapshotStorageAndSseWhileKeepingGenericMapping(
            boolean temporary, RuntimeException failure, String storageCode, String eventCode) {
        var logger = (Logger) LoggerFactory.getLogger(temporary
                ? DefaultTemporaryTurnRunner.class : AgentTurnRunner.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var fixture = fixture(temporary, failure, true);
            fixture.run().run();

            fixture.verifyStoredCode().accept(storageCode);
            verify(fixture.events()).append(fixture.turnId(), 9L, fixture.runId(), 1L,
                    "error", Map.of("code", eventCode, "retryable", true, "partialRetained", false));
            verify(fixture.events(), never()).append(anyLong(), anyLong(), any(), anyLong(), eq("done"), any());
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .doesNotContain("private question", "secret-key", "raw-provider-response");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
            if (failure instanceof ReActDecisionException) {
                assertThat(appender.list.getFirst().getFormattedMessage()).contains(storageCode);
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Stream<Arguments> failures() {
        return Stream.of(false, true).flatMap(temporary -> Stream.concat(
                Arrays.stream(ReActDecisionException.Code.values()).map(code -> Arguments.of(
                        temporary, new ReActDecisionException(code), code.name(), code.name())),
                Stream.of(Arguments.of(temporary,
                        new IllegalStateException("private question secret-key raw-provider-response"),
                        "AGENT_EXECUTION_FAILED", "AI_UNAVAILABLE"))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void losingTheFenceDoesNotPublishTypedFailure(boolean temporary) {
        var fixture = fixture(temporary,
                new ReActDecisionException(ReActDecisionException.Code.REACT_FORBIDDEN_TOOL), false);

        fixture.run().run();

        verify(fixture.events(), never()).append(anyLong(), anyLong(), any(), anyLong(), eq("error"), any());
    }

    private Fixture fixture(boolean temporary, RuntimeException failure, boolean accepted) {
        var answers = mock(GroundedAnswerService.class);
        var events = mock(AgentTurnEventStore.class);
        var executor = mock(ExecutorService.class);
        var heartbeat = mock(ScheduledExecutorService.class);
        var runId = UUID.randomUUID();
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        if (temporary) {
            var turns = mock(TemporaryTurnStore.class);
            var lifecycle = mock(TemporaryTurnLifecycleService.class);
            var admission = new TemporaryTurnAdmission(-7L, UUID.randomUUID(), runId, 1L, true, "RUNNING");
            when(lifecycle.renew(9L, runId, 1L)).thenReturn(true);
            when(turns.previousContext(9L, admission.sessionId(), "private question")).thenReturn(List.of());
            when(answers.answerTemporary(eq(9L), eq(runId.toString()), eq("private question"),
                    any(), eq(true), any(), any(), any())).thenThrow(failure);
            when(lifecycle.fail(eq(admission), eq(9L), any())).thenReturn(accepted);
            var runner = new DefaultTemporaryTurnRunner(answers, turns, lifecycle, events,
                    executor, heartbeat, Clock.systemUTC());
            return new Fixture(-7L, runId, events, () -> runner.submit(admission, 9L, "private question"),
                    code -> {
                        verify(lifecycle).fail(admission, 9L, code);
                        verify(lifecycle, never()).complete(any(), anyLong(), any());
                    });
        }
        var finalizer = mock(AgentTurnFinalizer.class);
        var failures = mock(AgentTurnFailureService.class);
        var leases = mock(AgentTurnLeaseService.class);
        var admission = new AgentTurnAdmission(7L, runId, 1L, true, "RUNNING");
        when(leases.renew(7L, 9L, runId, 1L)).thenReturn(true);
        when(answers.answerPersistent(eq(9L), eq(runId), eq("private question"), eq(true), any(), any(), any()))
                .thenThrow(failure);
        when(failures.fail(eq(7L), eq(9L), eq(runId), eq(1L), any())).thenReturn(accepted);
        var runner = new AgentTurnRunner(answers, finalizer, failures, events, executor, heartbeat,
                leases, Clock.systemUTC());
        return new Fixture(7L, runId, events, () -> runner.submit(admission, 9L, "private question"),
                code -> {
                    verify(failures).fail(7L, 9L, runId, 1L, code);
                    verifyNoInteractions(finalizer);
                });
    }

    private record Fixture(long turnId, UUID runId, AgentTurnEventStore events, Runnable run,
                           java.util.function.Consumer<String> verifyStoredCode) { }
}
