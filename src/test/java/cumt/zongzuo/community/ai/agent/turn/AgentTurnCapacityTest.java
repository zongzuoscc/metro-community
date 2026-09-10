package cumt.zongzuo.community.ai.agent.turn;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class AgentTurnCapacityTest {
    @Test void earlyReturnAndValidationFailureReleaseTheRequestReservation() {
        AgentTurnCapacity capacity = new AgentTurnCapacity(1);
        try (var ignored = capacity.reserveRequest()) { /* 鉴权、校验或幂等直接返回。 */ }
        try (var ignored = capacity.reserveRequest()) { /* 名额必须可重新使用。 */ }
        assertThatThrownBy(() -> {
            try (var ignored = capacity.reserveRequest()) { throw new IllegalArgumentException("synthetic"); }
        }).isInstanceOf(IllegalArgumentException.class);
        try (var ignored = capacity.reserveRequest()) { }
    }

    @Test void returning202DoesNotReleaseARunningTasksReservation() throws Exception {
        AgentTurnCapacity capacity = new AgentTurnCapacity(1);
        var executor = new CapacityAwareTurnExecutor(Executors.newSingleThreadExecutor(), capacity);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        try {
            try (var ignored = capacity.reserveRequest()) {
                executor.execute(() -> { started.countDown(); block(finish); });
            }
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(capacity::reserveRequest).isInstanceOf(RejectedExecutionException.class);
            finish.countDown();
            await().untilAsserted(() -> { try (var ignored = capacity.reserveRequest()) { } });
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @Test void taskFailureAndRejectedSubmissionCannotLeakCapacity() {
        AgentTurnCapacity capacity = new AgentTurnCapacity(1);
        var executor = new CapacityAwareTurnExecutor(Executors.newSingleThreadExecutor(), capacity);
        try {
            Future<?> failed = executor.submit(() -> { throw new IllegalStateException("synthetic"); });
            assertThatThrownBy(failed::get).isInstanceOf(ExecutionException.class);
            await().untilAsserted(() -> { try (var ignored = capacity.reserveRequest()) { } });
            executor.shutdown();
            assertThatThrownBy(() -> executor.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
            try (var ignored = capacity.reserveRequest()) { }
        } finally { executor.shutdownNow(); }
    }

    @Test void queuedCancellationAndShutdownReleaseExactlyOnce() throws Exception {
        AgentTurnCapacity capacity = new AgentTurnCapacity(2);
        var executor = new CapacityAwareTurnExecutor(Executors.newSingleThreadExecutor(), capacity);
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        try {
            executor.execute(() -> { started.countDown(); block(finish); });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> queued = executor.submit(() -> { throw new AssertionError("Cancelled task must not run"); });
            queued.cancel(false);
            assertThatThrownBy(() -> executor.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            // 关闭后两个名额都释放；重复关闭不会把容量错误增加成三个。
            executor.shutdownNow();
            var first = capacity.transferToTask();
            var second = capacity.transferToTask();
            try { assertThatThrownBy(capacity::transferToTask).isInstanceOf(RejectedExecutionException.class); }
            finally { first.releaseTask(); second.releaseTask(); }
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    private static void block(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
