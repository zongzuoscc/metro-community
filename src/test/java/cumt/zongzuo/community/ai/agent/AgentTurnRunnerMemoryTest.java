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
    void successfulPersistentTurnAutomaticallyCapturesTheUserMessage() {
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
        when(answers.answer(eq(9L), eq(runId.toString()), eq("我喜欢简洁回答"),
                eq(true), any()))
                .thenReturn(answer);
        when(finalizer.complete(41L, runId, 7L, answer)).thenReturn(true);

        new AgentTurnRunner(answers, finalizer, failures, events, executor, heartbeat, leases,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC))
                .submit(admission, 9L, "我喜欢简洁回答");

        verify(events).append(41L, 9L, runId, 7L, "done", java.util.Map.of(
                "finalMessage", "answer", "finishReason", "stop", "citationCount", 0,
                "citations", java.util.List.of(), "webSources", java.util.List.of(),
                "fundingSource", "PLATFORM", "provider", "", "model", ""));
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
        when(answers.answer(eq(9L), eq(runId.toString()), eq("question"), eq(true), any()))
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
}
