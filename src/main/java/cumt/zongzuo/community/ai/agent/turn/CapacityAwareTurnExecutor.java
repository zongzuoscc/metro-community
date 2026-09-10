package cumt.zongzuo.community.ai.agent.turn;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/** 将入口预留名额与真实任务生命周期绑定，避免 HTTP 返回 202 后提前释放预算。 */
public final class CapacityAwareTurnExecutor extends AbstractExecutorService {
    private final ExecutorService delegate;
    private final AgentTurnCapacity capacity;

    public CapacityAwareTurnExecutor(ExecutorService delegate, AgentTurnCapacity capacity) {
        this.delegate = Objects.requireNonNull(delegate);
        this.capacity = Objects.requireNonNull(capacity);
    }

    @Override public void execute(Runnable command) {
        Objects.requireNonNull(command);
        AgentTurnCapacity.Reservation reservation = capacity.transferToTask();
        try { delegate.execute(new ReservedTask(command, reservation)); }
        catch (RuntimeException failure) { reservation.releaseTask(); throw failure; }
    }

    private record ReservedTask(Runnable command, AgentTurnCapacity.Reservation reservation) implements Runnable {
        @Override public void run() {
            try { command.run(); }
            finally { reservation.releaseTask(); }
        }
        void discard() {
            if (command instanceof Future<?> future) future.cancel(false);
            reservation.releaseTask();
        }
    }

    @Override public void shutdown() { delegate.shutdown(); }
    @Override public List<Runnable> shutdownNow() {
        List<Runnable> discarded = delegate.shutdownNow();
        discarded.forEach(task -> { if (task instanceof ReservedTask reserved) reserved.discard(); });
        return discarded;
    }
    @Override public boolean isShutdown() { return delegate.isShutdown(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
