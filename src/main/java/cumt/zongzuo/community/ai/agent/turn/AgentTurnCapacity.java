package cumt.zongzuo.community.ai.agent.turn;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对“正在接纳 + 已排队 + 正在执行”的整轮任务统一计数。
 * 请求进入鉴权/数据库之前预留名额，任务接手后由任务 finally 释放；
 * 校验失败、鉴权失败和幂等重放没有启动任务时，由请求 finally 释放。
 * ThreadLocal 只在同步 HTTP 入口传递资源凭证，不传用户身份，也不跨线程复用。
 */
public final class AgentTurnCapacity {
    private final Semaphore permits;
    private final ThreadLocal<Reservation> requestReservation = new ThreadLocal<>();

    public AgentTurnCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        permits = new Semaphore(capacity);
    }

    public Reservation reserveRequest() {
        if (requestReservation.get() != null) throw new IllegalStateException("Nested turn admission");
        Reservation reservation = reserve();
        requestReservation.set(reservation);
        return reservation;
    }

    /** 后台恢复任务没有 HTTP 预留，必须自行取得同一预算，不能绕过准入。 */
    Reservation transferToTask() {
        Reservation reservation = requestReservation.get();
        if (reservation == null || reservation.transferred) reservation = reserve();
        reservation.transferred = true;
        return reservation;
    }

    private Reservation reserve() {
        if (!permits.tryAcquire()) throw new RejectedExecutionException("AGENT_CAPACITY_EXHAUSTED");
        return new Reservation();
    }

    public final class Reservation implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        private boolean transferred;
        private Reservation() { }

        /** 完成、取消或提交失败可以同时发生，名额最多释放一次。 */
        void releaseTask() {
            if (released.compareAndSet(false, true)) permits.release();
        }

        @Override public void close() {
            if (requestReservation.get() == this) requestReservation.remove();
            if (!transferred) releaseTask();
        }
    }
}
