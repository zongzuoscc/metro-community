package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.agent.turn.*;
import cumt.zongzuo.community.ai.agent.temporary.DefaultTemporaryTurnRunner;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnAdmission;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnLifecycleService;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 回归：模型的高频存活校验不能承担续租写库；定时心跳失败不能被忽略。 */
class AgentTurnLeaseHotpathTest {
    @Test
    void temporaryRunnerUsesReadChecksAndBlocksDeltasAfterHeartbeatException() {
        var answers = mock(GroundedAnswerService.class);
        var turns = mock(TemporaryTurnStore.class);
        var lifecycle = mock(TemporaryTurnLifecycleService.class);
        var events = mock(AgentTurnEventStore.class);
        var executor = mock(ExecutorService.class);
        var heartbeat = mock(ScheduledExecutorService.class);
        UUID run = UUID.randomUUID();
        var admission = new TemporaryTurnAdmission(-7, UUID.randomUUID(), run, 3, true, "RUNNING");
        when(lifecycle.renew(9, run, 3)).thenReturn(true).thenThrow(new IllegalStateException("续租中断"));
        when(lifecycle.isRunning(admission, 9)).thenReturn(true);
        Runnable[] task = new Runnable[1];
        doAnswer(call -> { task[0] = call.getArgument(0); return null; })
                .when(heartbeat).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
                .when(executor).execute(any());
        when(answers.answerTemporary(anyLong(), anyString(), anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenAnswer(call -> {
                    BooleanSupplier running = call.getArgument(6);
                    for (int i = 0; i < 20; i++) assertThat(running.getAsBoolean()).isTrue();
                    task[0].run();
                    assertThat(running.getAsBoolean()).isFalse();
                    var delta = call.<java.util.function.Consumer<String>>getArgument(7);
                    assertThatThrownBy(() -> delta.accept("不应发送"))
                            .isInstanceOf(java.util.concurrent.CancellationException.class);
                    return new GroundedAgentAnswer("不应完成", List.of(), "stop");
                });

        new DefaultTemporaryTurnRunner(answers, turns, lifecycle, events, executor, heartbeat, Clock.systemUTC())
                .submit(admission, 9, "问题");

        verify(lifecycle, times(20)).isRunning(admission, 9);
        verify(lifecycle, times(2)).renew(9, run, 3);
        verify(lifecycle, never()).complete(any(), anyLong(), any());
        verify(events, never()).append(anyLong(), anyLong(), any(), anyLong(), eq("delta"), any());
        verify(events, never()).append(anyLong(), anyLong(), any(), anyLong(), eq("done"), any());
    }

    @Test
    void repeatedRunningChecksDoNotRenewTheDatabaseLease() {
        var answers = mock(GroundedAnswerService.class);
        var finalizer = mock(AgentTurnFinalizer.class);
        var failures = mock(AgentTurnFailureService.class);
        var events = mock(AgentTurnEventStore.class);
        var executor = mock(ExecutorService.class);
        var heartbeat = mock(ScheduledExecutorService.class);
        // 对布尔只读检查返回 true，使测试同时兼容改造前后，红灯只由续租次数触发。
        var leases = mock(AgentTurnLeaseService.class, invocation ->
                invocation.getMethod().getReturnType() == boolean.class ? true : null);
        UUID run = UUID.randomUUID();
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
                .when(executor).execute(any());
        when(answers.answerPersistent(anyLong(), any(), anyString(), anyBoolean(), any(), any(), any()))
                .thenAnswer(call -> {
                    BooleanSupplier running = call.getArgument(5);
                    for (int i = 0; i < 20; i++) assertThat(running.getAsBoolean()).isTrue();
                    return new GroundedAgentAnswer("回答", List.of(), "stop");
                });

        new AgentTurnRunner(answers, finalizer, failures, events, executor, heartbeat, leases, Clock.systemUTC())
                .submit(new AgentTurnAdmission(41, run, 7, true, "RUNNING"), 9, "问题");

        // 只保留执行开始及最终提交前续租，20 次检查不产生任何额外续租事务。
        verify(leases, times(2)).renew(41, 9, run, 7);
    }

    @Test
    void failedScheduledHeartbeatPermanentlyInvalidatesTheRunningSupplier() {
        var answers = mock(GroundedAnswerService.class);
        var finalizer = mock(AgentTurnFinalizer.class);
        var failures = mock(AgentTurnFailureService.class);
        var events = mock(AgentTurnEventStore.class);
        var executor = mock(ExecutorService.class);
        var heartbeat = mock(ScheduledExecutorService.class);
        var leases = mock(AgentTurnLeaseService.class, invocation ->
                invocation.getMethod().getReturnType() == boolean.class ? true : null);
        UUID run = UUID.randomUUID();
        when(leases.renew(41, 9, run, 7)).thenReturn(true, false, true);
        Runnable[] task = new Runnable[1];
        doAnswer(call -> { task[0] = call.getArgument(0); return null; })
                .when(heartbeat).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
                .when(executor).execute(any());
        when(answers.answerPersistent(anyLong(), any(), anyString(), anyBoolean(), any(), any(), any()))
                .thenAnswer(call -> {
                    task[0].run();
                    BooleanSupplier running = call.getArgument(5);
                    assertThat(running.getAsBoolean()).isFalse();
                    assertThat(running.getAsBoolean()).isFalse();
                    return new GroundedAgentAnswer("不应完成", List.of(), "stop");
                });

        new AgentTurnRunner(answers, finalizer, failures, events, executor, heartbeat, leases, Clock.systemUTC())
                .submit(new AgentTurnAdmission(41, run, 7, true, "RUNNING"), 9, "问题");

        verifyNoInteractions(finalizer);
        verify(events, never()).append(anyLong(), anyLong(), any(), anyLong(), eq("done"), any());
    }
}
