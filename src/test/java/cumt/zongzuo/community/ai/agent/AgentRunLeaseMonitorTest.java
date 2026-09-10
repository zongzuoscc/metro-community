package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.agent.turn.AgentRunLeaseMonitor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 检查失败闩锁不可恢复，并确保每段增量的本地检查不访问存储。 */
class AgentRunLeaseMonitorTest {
    @Test
    void heartbeatExceptionFailsClosedAndNeverRenewsAgain() {
        BooleanSupplier check = mock(BooleanSupplier.class), renew = mock(BooleanSupplier.class);
        when(renew.getAsBoolean()).thenThrow(new IllegalStateException("故障测试"));
        var monitor = new AgentRunLeaseMonitor(check, renew);

        assertThatCode(monitor::heartbeat).doesNotThrowAnyException();
        assertThat(monitor.isRunning()).isFalse();
        assertThatThrownBy(monitor::requireValid).isInstanceOf(CancellationException.class);
        monitor.heartbeat();

        verify(renew).getAsBoolean();
        verifyNoInteractions(check);
    }

    @Test
    void failedReadCannotBeRevivedByALaterHeartbeat() {
        BooleanSupplier check = mock(BooleanSupplier.class), renew = mock(BooleanSupplier.class);
        when(check.getAsBoolean()).thenReturn(false, true);
        var monitor = new AgentRunLeaseMonitor(check, renew);

        assertThat(monitor.isRunning()).isFalse();
        monitor.heartbeat();
        assertThat(monitor.isRunning()).isFalse();
        assertThatThrownBy(monitor::requireValid).isInstanceOf(CancellationException.class);

        verify(check).getAsBoolean();
        verifyNoInteractions(renew);
    }

    @Test
    void readExceptionIsPropagatedAndBlocksLaterOutput() {
        BooleanSupplier check = mock(BooleanSupplier.class), renew = mock(BooleanSupplier.class);
        when(check.getAsBoolean()).thenThrow(new IllegalStateException("存储不可用"));
        var monitor = new AgentRunLeaseMonitor(check, renew);

        assertThatThrownBy(monitor::isRunning).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(monitor::requireValid).isInstanceOf(CancellationException.class);
        assertThat(monitor.isRunning()).isFalse();
        verify(check).getAsBoolean();
    }

    @Test
    void successfulReadAndLocalDeltaChecksDoNotRenew() {
        BooleanSupplier check = mock(BooleanSupplier.class), renew = mock(BooleanSupplier.class);
        when(check.getAsBoolean()).thenReturn(true);
        var monitor = new AgentRunLeaseMonitor(check, renew);

        assertThat(monitor.isRunning()).isTrue();
        assertThat(monitor.isRunning()).isTrue();
        for (int i = 0; i < 100; i++) monitor.requireValid();

        verify(check, times(2)).getAsBoolean();
        verifyNoMoreInteractions(check);
        verifyNoInteractions(renew);
    }
}
