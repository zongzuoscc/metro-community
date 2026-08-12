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

@AiApi
@RestController
@RequestMapping("/api/agent/turns")
public class AgentTurnController {

    private final AgentTurnAdmissionService admissions;
    private final AgentTurnQueryService queries;
    private final AgentTurnEventStore events;
    private final AgentTurnCancellationService cancellations;
    private final ObjectProvider<AgentTurnRunner> runners;
    private final MetroAiProperties properties;
    private final ObjectMapper objectMapper;

    public AgentTurnController(AgentTurnAdmissionService admissions, AgentTurnQueryService queries,
                               AgentTurnEventStore events,
                               AgentTurnCancellationService cancellations,
                               ObjectProvider<AgentTurnRunner> runners,
                               MetroAiProperties properties, ObjectMapper objectMapper) {
        this.admissions = admissions;
        this.queries = queries;
        this.events = events;
        this.cancellations = cancellations;
        this.runners = runners;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody AgentTurnCreateRequest request) {
        if (!properties.isCapabilityEnabled(cumt.zongzuo.community.ai.provider.AiCapability.AGENT)) {
            throw AiApiException.disabled();
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
                        "created", admission.created()));
    }

    @GetMapping("/{turnId}")
    public AgentTurnSnapshot snapshot(@PathVariable long turnId) {
        return queries.snapshot(turnId, CurrentUser.id());
    }

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
