package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.agent.GroundedAgentAnswer;
import cumt.zongzuo.community.ai.agent.GroundedAnswerService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(AgentTurnRunner.class);

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
                    "retrieving", Map.of("strategy", "HYBRID", "queryCount", 1,
                            "webSearchEnabled", admission.webSearchEnabled()));
            events.append(admission.turnId(), userId, admission.runId(), admission.runFence(),
                    "generating", Map.of("phase", "grounded_answer"));
            GroundedAgentAnswer answer = answers.answer(userId,
                    admission.runId().toString(), question, admission.webSearchEnabled(),
                    clock.instant().plus(Duration.ofMinutes(2)));
            if (!turnLeases.renew(admission.turnId(), userId, admission.runId(),
                    admission.runFence())) {
                return;
            }
            if (!finalizer.complete(admission.turnId(), admission.runId(), admission.runFence(), answer)) {
                return;
            }
            Map<String, Object> done = new java.util.LinkedHashMap<>();
            done.put("finalMessage", answer.answer());
            done.put("finishReason", answer.finishReason());
            done.put("citationCount", answer.citations().size());
            done.put("citations", answer.citations());
            done.put("webSources", answer.webSources());
            done.put("fundingSource", answer.fundingSource().name());
            done.put("provider", safe(answer.provider()));
            done.put("model", safe(answer.model()));
            events.append(admission.turnId(), userId, admission.runId(), admission.runFence(),
                    "done", done);
        } catch (RuntimeException error) {
            // 异步执行不能把异常静默吞掉，否则前端只会看到通用“不可用”，运维也无法区分
            // 是联网检索、模型响应格式还是事务提交失败。这里只记录异常类型与根因类型，
            // 不记录问题、回答、联网摘要、URL 或密钥，避免诊断日志变成第二份用户数据。
            log.warn("Agent turn execution failed turnId={} exceptionType={} rootCauseType={} reason={}",
                    admission.turnId(), error.getClass().getName(), rootCauseType(error),
                    safeDiagnostic(error.getMessage()));
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String rootCauseType(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }

    private static String safeDiagnostic(String value) {
        if (value == null || value.isBlank()) {
            return "unspecified";
        }
        // 异常消息来自后端预定义校验文本；仍截断并清理换行，禁止意外把供应商正文带入日志。
        String normalized = value.replaceAll("[\\r\\n]+", " ").strip();
        return normalized.substring(0, Math.min(normalized.length(), 160));
    }
}
