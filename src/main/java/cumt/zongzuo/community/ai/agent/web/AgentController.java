package cumt.zongzuo.community.ai.agent.web;

import cumt.zongzuo.community.ai.agent.GroundedAgentAnswer;
import cumt.zongzuo.community.ai.agent.GroundedAnswerService;
import cumt.zongzuo.community.ai.agent.turn.AgentConversationPreferenceService;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.web.AiApi;
import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@AiApi
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ObjectProvider<GroundedAnswerService> services;
    private final MetroAiProperties properties;
    private final Clock clock;
    private final AgentConversationPreferenceService preferences;

    public AgentController(ObjectProvider<GroundedAnswerService> services,
                           MetroAiProperties properties,
                           Clock clock,
                           AgentConversationPreferenceService preferences) {
        this.services = services;
        this.properties = properties;
        this.clock = clock;
        this.preferences = preferences;
    }

    @PostMapping("/answer")
    public GroundedAgentAnswer answer(@Valid @RequestBody AgentAnswerRequest request) {
        GroundedAnswerService service = services.getIfAvailable();
        if (service == null || !properties.isCapabilityEnabled(
                cumt.zongzuo.community.ai.provider.AiCapability.AGENT)) {
            throw AiApiException.disabled();
        }
        long userId = CurrentUser.id();
        boolean webSearchEnabled = preferences.get(userId).enabled();
        return service.answer(userId, request.clientRequestId().toString(), request.message(),
                webSearchEnabled, clock.instant().plus(properties.getAgent().getTaskTimeout()));
    }
}
