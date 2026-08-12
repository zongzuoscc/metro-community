package cumt.zongzuo.community.ai.agent.temporary;

import cumt.zongzuo.community.ai.agent.GroundedAgentAnswer;
import cumt.zongzuo.community.ai.agent.GroundedAnswerService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnEventStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 执行临时 turn，但绝不调用持久 finalizer 或长期记忆捕获服务。
 *
 * <p>它复用公开文章检索与模型 Provider，但个人上下文只来自 TemporaryTurnStore 中当前 session 的
 * Redis 历史。完成、失败和 heartbeat 都通过 TemporaryTurnLifecycleService 维护共享 fence。</p>
 */
@Service
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.agent.enabled"}, havingValue = "true")
public class DefaultTemporaryTurnRunner implements TemporaryTurnRunner {

    private final GroundedAnswerService answers;
    private final TemporaryTurnStore turns;
    private final TemporaryTurnLifecycleService lifecycle;
    private final AgentTurnEventStore events;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Clock clock;

    public DefaultTemporaryTurnRunner(GroundedAnswerService answers, TemporaryTurnStore turns,
                                      TemporaryTurnLifecycleService lifecycle,
                                      AgentTurnEventStore events,
                                      ExecutorService agentTurnExecutor,
                                      ScheduledExecutorService agentTurnHeartbeatExecutor,
                                      Clock clock) {
        this.answers = answers;
        this.turns = turns;
        this.lifecycle = lifecycle;
        this.events = events;
        this.executor = agentTurnExecutor;
        this.heartbeatExecutor = agentTurnHeartbeatExecutor;
        this.clock = clock;
    }

    /**
     * 使用与持久 turn 相同的有界线程池。
     * 这样临时模式不能绕过全局背压和并发上限；线程池拒绝由 Controller 统一补偿。
     */
    @Override
    public void submit(TemporaryTurnAdmission admission, long userId, String question) {
        executor.execute(() -> execute(admission, userId, question));
    }

    private void execute(TemporaryTurnAdmission admission, long userId, String question) {
        ScheduledFuture<?> heartbeat = null;
        try {
            // 调用 Provider 前先续约一次，防止在线程池中排队过久的 worker 继续执行。
            if (!lifecycle.renew(userId, admission.runId(), admission.runFence())) return;
            // 心跳只维持运行权，绝不延长临时 session 的 24 小时绝对截止时间。
            heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                    () -> lifecycle.renew(userId, admission.runId(), admission.runFence()),
                    30, 30, TimeUnit.SECONDS);
            events.append(admission.turnId(), userId, admission.runId(), admission.runFence(),
                    "generating", Map.of("phase", "temporary_grounded_answer"));
            GroundedAgentAnswer answer = answers.answerTemporary(userId,
                    admission.runId().toString(), question,
                    turns.previousContext(userId, admission.sessionId(), question),
                    clock.instant().plus(Duration.ofMinutes(2)));
            if (!lifecycle.renew(userId, admission.runId(), admission.runFence())) return;
            // 先在栕栏事务内完成 turn，再发 done 事件；SSE 始终只是短期进度通道。
            if (!lifecycle.complete(admission, userId, answer)) return;
            events.append(admission.turnId(), userId, admission.runId(), admission.runFence(),
                    "done", Map.of("finalMessage", answer.answer(), "finishReason",
                            answer.finishReason(), "citationCount", answer.citations().size(),
                            "fundingSource", answer.fundingSource().name(),
                            "provider", safe(answer.provider()), "model", safe(answer.model())));
        } catch (RuntimeException error) {
            if (lifecycle.fail(admission, userId, "AGENT_EXECUTION_FAILED")) {
                events.append(admission.turnId(), userId, admission.runId(), admission.runFence(),
                        "error", Map.of("code", "AI_UNAVAILABLE", "retryable", true,
                                "partialRetained", false));
            }
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
