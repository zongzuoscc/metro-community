package cumt.zongzuo.community.ai.agent.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmission;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnAdmissionService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnCancellationService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnCreateCommand;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnEvent;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnEventStore;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnQueryService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnRunner;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnSnapshot;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnHistoryPage;
import cumt.zongzuo.community.ai.agent.turn.AgentConversationPreferenceService;
import cumt.zongzuo.community.ai.agent.turn.AgentConversationContextService;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnAdmission;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnAdmissionService;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnRunner;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.web.AiApi;
import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent turn 的 HTTP 入口，负责把一次请求精确路由到持久或临时执行边界。
 *
 * <p>路由发生在 admission 之前，因此 temporary=true 的请求不会先创建 conversation/episode/turn/message
 * 再尝试删除。任一 dispatch 失败都必须补偿共享栅栏，防止用户永久卡在 ACTIVE_TURN_EXISTS。</p>
 */
@AiApi
@RestController
@RequestMapping("/api/agent/turns")
public class AgentTurnController {

    private final AgentTurnAdmissionService admissions;
    private final TemporaryTurnAdmissionService temporaryAdmissions;
    private final AgentTurnQueryService queries;
    private final AgentTurnEventStore events;
    private final AgentTurnCancellationService cancellations;
    private final ObjectProvider<AgentTurnRunner> runners;
    private final ObjectProvider<TemporaryTurnRunner> temporaryRunners;
    private final MetroAiProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentConversationPreferenceService preferences;
    private final AgentConversationContextService contexts;

    public AgentTurnController(AgentTurnAdmissionService admissions,
                               TemporaryTurnAdmissionService temporaryAdmissions,
                               AgentTurnQueryService queries,
                               AgentTurnEventStore events,
                               AgentTurnCancellationService cancellations,
                               ObjectProvider<AgentTurnRunner> runners,
                               ObjectProvider<TemporaryTurnRunner> temporaryRunners,
                               MetroAiProperties properties, ObjectMapper objectMapper,
                               AgentConversationPreferenceService preferences,
                               AgentConversationContextService contexts) {
        this.admissions = admissions;
        this.temporaryAdmissions = temporaryAdmissions;
        this.queries = queries;
        this.events = events;
        this.cancellations = cancellations;
        this.runners = runners;
        this.temporaryRunners = temporaryRunners;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.preferences = preferences;
        this.contexts = contexts;
    }

    /** 返回当前用户唯一主对话的联网偏好；默认值为开启。 */
    @GetMapping("/web-search-setting")
    public AgentConversationPreferenceService.WebSearchPreference webSearchSetting() {
        return preferences.get(CurrentUser.id());
    }

    /** 更新主对话联网偏好，新 turn 会在接纳时冻结该值，运行中请求不被中途改变。 */
    @PutMapping("/web-search-setting")
    public AgentConversationPreferenceService.WebSearchPreference webSearchSetting(
            @Valid @RequestBody AgentWebSearchSettingRequest request) {
        return preferences.update(CurrentUser.id(), request.enabled());
    }

    /**
     * 全屏主对话的有界历史接口。每一项既含轨道摘要，也含该轮完整问答，
     * 前端因此可以连续渲染全部已加载内容，并让轨道只负责滚动定位。
     */
    @GetMapping("/history")
    public AgentTurnHistoryPage history(
            @RequestParam(required = false) Long beforeTurnId,
            @RequestParam(defaultValue = "30") int size) {
        return queries.history(CurrentUser.id(), beforeTurnId, size);
    }

    /** 保留可见历史，但让下一轮开始一个不搜索旧消息的新上下文段。 */
    @PostMapping("/context/reset")
    public ResponseEntity<Void> resetContext() {
        contexts.reset(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    /**
     * 创建持久或临时 turn。返回 202 表示新任务已接纳，返回 200 表示同一幂等请求已存在；
     * 两种路径都在异步调度失败时执行精确栅栏补偿。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody AgentTurnCreateRequest request) {
        if (!properties.isCapabilityEnabled(cumt.zongzuo.community.ai.provider.AiCapability.AGENT)) {
            throw AiApiException.disabled();
        }
        if (request.temporary()) {
            return createTemporary(request);
        }
        AgentTurnRunner runner = runners.getIfAvailable();
        if (runner == null) {
            throw AiApiException.runtimeUnavailable(java.time.Duration.ofSeconds(1));
        }
        AgentTurnAdmission admission = admissions.admit(new AgentTurnCreateCommand(CurrentUser.id(),
                request.clientRequestId(), request.message(), contextJson(request.context()),
                "COMMUNITY_QA"));
        if (admission.created()) {
            events.append(admission.turnId(), CurrentUser.id(), admission.runId(), admission.runFence(),
                    "accepted", Map.of("state", "RUNNING"));
            try {
                runner.submit(admission, CurrentUser.id(), request.message());
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                admissions.compensateFailedDispatch(admission, CurrentUser.id(),
                        "AGENT_EXECUTOR_SATURATED");
                throw AiApiException.runtimeUnavailable(java.time.Duration.ofSeconds(1));
            }
        }
        return ResponseEntity.status(admission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(Map.of("turnId", admission.turnId(), "state", admission.state(),
                        "created", admission.created(), "temporary", false));
    }

    /**
     * 接纳一个只保存在 Redis 中的 turn，并交给临时 runner 异步执行。
     * 在返回 202 前先写 accepted SSE 事件；如果线程池拒绝或事件写入失败，会将 Redis turn 终结并释放 MySQL/Redis 租约。
     */
    private ResponseEntity<Map<String, Object>> createTemporary(AgentTurnCreateRequest request) {
        if (request.temporarySessionId() == null) {
            throw AiApiException.validationFailed();
        }
        TemporaryTurnRunner runner = temporaryRunners.getIfAvailable();
        if (runner == null) throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
        long userId = CurrentUser.id();
        TemporaryTurnAdmission admission = temporaryAdmissions.admit(userId,
                request.temporarySessionId(), request.clientRequestId(), request.message(),
                contextJson(request.context()),
                preferences.getWithoutCreatingConversation(userId).enabled());
        if (admission.created()) {
            try {
                events.append(admission.turnId(), userId, admission.runId(), admission.runFence(),
                        "accepted", Map.of("state", "RUNNING", "temporary", true));
                runner.submit(admission, userId, request.message());
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                temporaryAdmissions.compensate(admission, userId, "AGENT_EXECUTOR_SATURATED");
                throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
            } catch (RuntimeException dispatchFailure) {
                temporaryAdmissions.compensate(admission, userId, "AGENT_DISPATCH_FAILED");
                throw dispatchFailure;
            }
        }
        return ResponseEntity.status(admission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(Map.of("turnId", admission.turnId(), "state", admission.state(),
                        "created", admission.created(), "temporary", true,
                        "temporarySessionId", admission.sessionId()));
    }

    /** 查询当前用户的 turn 快照；负数 ID 自动进入临时 Redis 边界。 */
    @GetMapping("/{turnId}")
    public AgentTurnSnapshot snapshot(@PathVariable long turnId) {
        return queries.snapshot(turnId, CurrentUser.id());
    }

    /** 按 Last-Event-ID/after 恢复 SSE，在终态事件送达或超时后结束连接。 */
    @GetMapping(value = "/{turnId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> events(@PathVariable long turnId,
                                                         @RequestParam(required = false) String after,
                                                         @RequestHeader(value = "Last-Event-ID", required = false)
                                                         String lastEventId) {
        long userId = CurrentUser.id();
        String cursor = after == null ? lastEventId : after;
        List<AgentTurnEvent> initial = events.replay(turnId, userId, cursor, 100);
        StreamingResponseBody body = output -> streamEvents(
                output, turnId, userId, cursor, initial);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(body);
    }

    /** 使用 turn 当前 run fence 取消任务，迟到的旧请求不能取消新 turn。 */
    @PostMapping("/{turnId}/cancel")
    public AgentTurnSnapshot cancel(@PathVariable long turnId) {
        return cancellations.cancel(turnId, CurrentUser.id(), queries);
    }

    private String contextJson(Map<String, Object> context) {
        try {
            return objectMapper.writeValueAsString(context == null ? Map.of() : context);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Agent page context is invalid", error);
        }
    }

    private void streamEvents(java.io.OutputStream output, long turnId, long userId,
                              String after, List<AgentTurnEvent> initial) throws IOException {
        String cursor = writeEvents(output, initial, after);
        if (containsTerminal(initial)) {
            return;
        }
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        Instant terminalObservedAt = events.isTerminal(turnId, userId) ? Instant.now() : null;
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            List<AgentTurnEvent> next = events.replay(turnId, userId, cursor, 100);
            cursor = writeEvents(output, next, cursor);
            if (containsTerminal(next)) {
                return;
            }
            if (events.isTerminal(turnId, userId)) {
                if (terminalObservedAt == null) {
                    terminalObservedAt = Instant.now();
                } else if (Duration.between(terminalObservedAt, Instant.now()).toSeconds() >= 2) {
                    return;
                }
            }
        }
    }

    private String writeEvents(java.io.OutputStream output, List<AgentTurnEvent> batch,
                               String cursor) throws IOException {
        String latest = cursor;
        for (AgentTurnEvent event : batch) {
            String frame = "id: " + event.eventId() + '\n'
                    + "event: " + event.type() + '\n'
                    + "data: " + objectMapper.writeValueAsString(event) + "\n\n";
            output.write(frame.getBytes(StandardCharsets.UTF_8));
            output.flush();
            latest = event.eventId();
        }
        return latest;
    }

    private static boolean containsTerminal(List<AgentTurnEvent> batch) {
        return batch.stream().anyMatch(event -> "done".equals(event.type())
                || "error".equals(event.type()) || "cancelled".equals(event.type()));
    }
}
