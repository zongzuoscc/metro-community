package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.agent.GroundedAgentAnswer;
import cumt.zongzuo.community.ai.agent.GroundedAnswerService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 在共享有界线程池中执行持久 Agent turn。
 *
 * <p>本类只负责心跳、检索/生成阶段和 SSE 进度。回答、引用、记忆捕获与 SUCCEEDED
 * 状态由 AgentTurnFinalizer 统一事务提交，避免查询线程看到半完成结果。</p>
 */
@Service
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.agent.enabled"}, havingValue = "true")
public class AgentTurnRunner {

    private final GroundedAnswerService answers;
    private final AgentTurnFinalizer finalizer;
    private final AgentTurnFailureService failures;
    private final AgentTurnEventStore events;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final AgentTurnLeaseService turnLeases;
    private final Clock clock;

    public AgentTurnRunner(GroundedAnswerService answers, AgentTurnFinalizer finalizer,
                           AgentTurnFailureService failures, AgentTurnEventStore events,
                           ExecutorService agentTurnExecutor,
                           ScheduledExecutorService agentTurnHeartbeatExecutor,
                           AgentTurnLeaseService turnLeases, Clock clock) {
        this.answers = answers;
        this.finalizer = finalizer;
        this.failures = failures;
        this.events = events;
        this.executor = agentTurnExecutor;
        this.heartbeatExecutor = agentTurnHeartbeatExecutor;
        this.turnLeases = turnLeases;
        this.clock = clock;
    }

    public void submit(AgentTurnAdmission admission, long userId, String question) {
        // 持久与临时模式复用同一容量上限，任何一方都不能绕过全局背压。
        executor.execute(() -> execute(admission, userId, question));
    }

    private void execute(AgentTurnAdmission admission, long userId, String question) {
        ScheduledFuture<?> heartbeat = null;
        try {
            if (!turnLeases.renew(admission.turnId(), userId, admission.runId(),
                    admission.runFence())) {
                return;
            }
            heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                    () -> turnLeases.renew(admission.turnId(), userId, admission.runId(),
                            admission.runFence()), 30, 30, TimeUnit.SECONDS);
            events.append(admission.turnId(), userId, admission.runId(), admission.runFence(),
                    "retrieving", Map.of("strategy", "HYBRID", "queryCount", 1));
            events.append(admission.turnId(), userId, admission.runId(), admission.runFence(),
                    "generating", Map.of("phase", "grounded_answer"));
            GroundedAgentAnswer answer = answers.answer(userId,
                    admission.runId().toString(), question, clock.instant().plus(Duration.ofMinutes(2)));
            if (!turnLeases.renew(admission.turnId(), userId, admission.runId(),
                    admission.runFence())) {
                return;
            }
            if (!finalizer.complete(admission.turnId(), admission.runId(), admission.runFence(), answer)) {
                return;
            }
            events.append(admission.turnId(), userId, admission.runId(), admission.runFence(),
                    "done", Map.of("finalMessage", answer.answer(),
                            "finishReason", answer.finishReason(), "citationCount",
                            answer.citations().size()));
        } catch (RuntimeException error) {
            if (failures.fail(admission.turnId(), userId, admission.runId(), admission.runFence(),
                    "AGENT_EXECUTION_FAILED")) {
                events.append(admission.turnId(), userId, admission.runId(), admission.runFence(),
                        "error", Map.of("code", "AI_UNAVAILABLE", "retryable", true,
                                "partialRetained", false));
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }
}
