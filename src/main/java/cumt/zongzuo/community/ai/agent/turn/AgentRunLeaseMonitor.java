package cumt.zongzuo.community.ai.agent.turn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 一次执行的本地失效闩锁：读校验和周期续租分离，任何一方失效后都不能重新恢复输出。
 * 此对象不缓存授权成功结果；每次 isRunning 仍查询权威存储。本地标记只记住失败，
 * 防止定时线程续租异常被 ScheduledExecutor 静默停止后，生成线程仍继续输出。
 */
public final class AgentRunLeaseMonitor {
    private static final Logger log = LoggerFactory.getLogger(AgentRunLeaseMonitor.class);
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final BooleanSupplier check;
    private final BooleanSupplier renew;

    public AgentRunLeaseMonitor(BooleanSupplier check, BooleanSupplier renew) {
        this.check = check;
        this.renew = renew;
    }

    /** 工具调用和模型路由使用只读校验；异常向上传播，同时永久标记本轮失效。 */
    public boolean isRunning() {
        if (!valid.get()) return false;
        try {
            if (!check.getAsBoolean()) valid.set(false);
            return valid.get();
        } catch (RuntimeException error) {
            valid.set(false);
            throw error;
        }
    }

    /** 仅定时任务续租。异常只记录类型，不把供应商信息或用户数据写进日志。 */
    public void heartbeat() {
        if (!valid.get()) return;
        try {
            if (!renew.getAsBoolean()) valid.set(false);
        } catch (RuntimeException error) {
            valid.set(false);
            log.warn("Agent lease heartbeat failed exceptionType={}", error.getClass().getName());
        }
    }

    /** 每段输出只检查本地失效标记，无额外 SQL；真实授权校验仍按既有路由频率执行。 */
    public void requireValid() {
        if (!valid.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Agent no longer owns execution");
        }
    }
}
