package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.agent.temporary.DefaultTemporaryTurnRunner;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnAdmission;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnLifecycleService;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnStore;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnEventStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证临时 runner 只使用 Redis session 上下文，不进入持久 finalizer 或长期记忆捕获路径。 */
class TemporaryTurnRunnerTest {

    @Test
    void completesWithOnlyRedisSessionContext() {
        GroundedAnswerService answers = mock(GroundedAnswerService.class);
        TemporaryTurnStore turns = mock(TemporaryTurnStore.class);
        TemporaryTurnLifecycleService lifecycle = mock(TemporaryTurnLifecycleService.class);
        AgentTurnEventStore events = mock(AgentTurnEventStore.class);
        ExecutorService executor = mock(ExecutorService.class);
        ScheduledExecutorService heartbeat = mock(ScheduledExecutorService.class);
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TemporaryTurnAdmission admission = new TemporaryTurnAdmission(-7L, sessionId, runId,
                3L, true, "RUNNING");
        when(lifecycle.renew(9L, runId, 3L)).thenReturn(true);
        when(turns.previousContext(9L, sessionId, "current"))
                .thenReturn(List.of("USER\tprior temporary message"));
        GroundedAgentAnswer answer = new GroundedAgentAnswer("answer", List.of(), "stop");
        when(answers.answerTemporary(eq(9L), eq(runId.toString()), eq("current"), any(),
                eq(true), any()))
                .thenReturn(answer);
        when(lifecycle.complete(admission, 9L, answer)).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        new DefaultTemporaryTurnRunner(answers, turns, lifecycle, events, executor, heartbeat,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC))
                .submit(admission, 9L, "current");

        verify(answers).answerTemporary(eq(9L), eq(runId.toString()), eq("current"),
                eq(List.of("USER\tprior temporary message")), eq(true), any());
        verify(lifecycle).complete(admission, 9L, answer);
        verify(lifecycle, never()).fail(any(), eq(9L), any());
    }
}
