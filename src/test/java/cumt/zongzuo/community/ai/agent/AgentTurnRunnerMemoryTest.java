package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmission;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnEventStore;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnFailureService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnFinalizer;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnLeaseService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRunner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTurnRunnerMemoryTest {

    @Test
    void successfulPersistentTurnUsesPreAnswerContextPreparation() {
        GroundedAnswerService answers = mock(GroundedAnswerService.class);
        AgentTurnFinalizer finalizer = mock(AgentTurnFinalizer.class);
        AgentTurnFailureService failures = mock(AgentTurnFailureService.class);
        AgentTurnEventStore events = mock(AgentTurnEventStore.class);
        AgentTurnLeaseService leases = mock(AgentTurnLeaseService.class);
        ExecutorService executor = mock(ExecutorService.class);
        ScheduledExecutorService heartbeat = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
        UUID runId = UUID.randomUUID();
        AgentTurnAdmission admission = new AgentTurnAdmission(41L, runId, 7L, true, "RUNNING");
        GroundedAgentAnswer answer = new GroundedAgentAnswer("answer", List.of(), "stop");
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        doReturn(heartbeatFuture).when(heartbeat).scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), any());
        when(leases.renew(41L, 9L, runId, 7L)).thenReturn(true);
        when(answers.answerPersistent(eq(9L), eq(runId), eq("我喜欢简洁回答"),
                eq(true), any(), any(), any()))
                .thenAnswer(call -> {
                    call.<java.util.function.Consumer<String>>getArgument(6).accept("answer");
                    org.mockito.Mockito.verifyNoInteractions(finalizer);
                    verify(events).append(eq(41L),eq(9L),eq(runId),eq(7L),eq("delta"),any());
                    return answer;
                });
        when(finalizer.complete(41L, runId, 7L, answer)).thenReturn(true);

        new AgentTurnRunner(answers, finalizer, failures, events, executor, heartbeat, leases,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC))
                .submit(admission, 9L, "我喜欢简洁回答");

        verify(events).append(41L, 9L, runId, 7L, "done", java.util.Map.of(
                "finalMessage", "answer", "finishReason", "stop", "citationCount", 0,
                "citations", java.util.List.of(), "webSources", java.util.List.of(),
                "fundingSource", "PLATFORM", "provider", "", "model", ""));
        var order = org.mockito.Mockito.inOrder(finalizer,events);
        order.verify(finalizer).complete(41L,runId,7L,answer);
        order.verify(events).append(eq(41L),eq(9L),eq(runId),eq(7L),eq("done"),any());
    }

    @Test
    void committedAnswerPublishesDoneWithoutEnteringTheFailurePath() {
        GroundedAnswerService answers = mock(GroundedAnswerService.class);
        AgentTurnFinalizer finalizer = mock(AgentTurnFinalizer.class);
        AgentTurnFailureService failures = mock(AgentTurnFailureService.class);
        AgentTurnEventStore events = mock(AgentTurnEventStore.class);
        AgentTurnLeaseService leases = mock(AgentTurnLeaseService.class);
        ExecutorService executor = mock(ExecutorService.class);
        ScheduledExecutorService heartbeat = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
        UUID runId = UUID.randomUUID();
        AgentTurnAdmission admission = new AgentTurnAdmission(42L, runId, 8L, true, "RUNNING");
        GroundedAgentAnswer answer = new GroundedAgentAnswer("answer", List.of(), "stop");
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        doReturn(heartbeatFuture).when(heartbeat).scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), any());
        when(leases.renew(42L, 9L, runId, 8L)).thenReturn(true);
        when(answers.answerPersistent(eq(9L), eq(runId), eq("question"), eq(true), any(), any(), any()))
                .thenReturn(answer);
        when(finalizer.complete(42L, runId, 8L, answer)).thenReturn(true);
        new AgentTurnRunner(answers, finalizer, failures, events, executor, heartbeat, leases,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC))
                .submit(admission, 9L, "question");

        verify(events).append(42L, 9L, runId, 8L, "done", java.util.Map.of(
                "finalMessage", "answer", "finishReason", "stop", "citationCount", 0,
                "citations", java.util.List.of(), "webSources", java.util.List.of(),
                "fundingSource", "PLATFORM", "provider", "", "model", ""));
        verify(failures, org.mockito.Mockito.never()).fail(anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void databaseFailureAfterDeltaNeverPublishesDone() {
        var answers = mock(GroundedAnswerService.class);
        var finalizer = mock(AgentTurnFinalizer.class);
        var failures = mock(AgentTurnFailureService.class);
        var events = mock(AgentTurnEventStore.class);
        var leases = mock(AgentTurnLeaseService.class);
        var executor = mock(ExecutorService.class);
        var heartbeat = mock(ScheduledExecutorService.class);
        UUID run = UUID.randomUUID();
        when(leases.renew(43,9,run,1)).thenReturn(true);
        when(failures.fail(eq(43L),eq(9L),eq(run),eq(1L),any())).thenReturn(true);
        when(answers.answerPersistent(eq(9L),eq(run),eq("question"),eq(true),any(),any(),any()))
                .thenAnswer(call -> {
                    call.<java.util.function.Consumer<String>>getArgument(6).accept("先显示");
                    return new GroundedAgentAnswer("先显示", List.of(), "stop");
                });
        when(finalizer.complete(eq(43L),eq(run),eq(1L),any())).thenThrow(new IllegalStateException("Database unavailable"));
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
        new AgentTurnRunner(answers,finalizer,failures,events,executor,heartbeat,leases,Clock.systemUTC())
                .submit(new AgentTurnAdmission(43,run,1,true,"RUNNING"),9,"question");
        verify(events).append(eq(43L),eq(9L),eq(run),eq(1L),eq("delta"),any());
        verify(events).append(eq(43L),eq(9L),eq(run),eq(1L),eq("error"),any());
        verify(events,org.mockito.Mockito.never()).append(anyLong(),anyLong(),any(),anyLong(),eq("done"),any());
    }
}
