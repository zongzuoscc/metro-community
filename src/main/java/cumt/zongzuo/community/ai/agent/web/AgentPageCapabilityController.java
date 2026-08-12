package cumt.zongzuo.community.ai.agent.web;

import cumt.zongzuo.community.ai.agent.AgentCapabilityResponse;
import cumt.zongzuo.community.ai.agent.AgentPageCapabilityService;
import cumt.zongzuo.community.ai.agent.WritingSuggestionRequest;
import cumt.zongzuo.community.ai.agent.WritingSuggestionResponse;
import cumt.zongzuo.community.ai.config.MetroAiProperties;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.web.AiApi;
import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 桌宠小窗的文章伴读和写作提案入口。 */
@AiApi
@RestController
@RequestMapping("/api/agent")
public class AgentPageCapabilityController {

    private final AgentPageCapabilityService service;
    private final MetroAiProperties properties;

    public AgentPageCapabilityController(AgentPageCapabilityService service,
                                         MetroAiProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/articles/{articleId}/summary")
    public AgentCapabilityResponse summarize(@PathVariable long articleId) {
        return analyze(articleId, "SUMMARY");
    }

    @PostMapping("/articles/{articleId}/analysis/{operation}")
    public AgentCapabilityResponse analyze(@PathVariable long articleId,
                                           @PathVariable String operation) {
        require(AiCapability.ARTICLE_SUMMARY);
        try {
            return service.analyzeArticle(CurrentUser.id(), articleId, operation);
        }
        catch (IllegalArgumentException invalid) {
            throw AiApiException.resourceNotFound();
        }
    }

    @PostMapping("/writing/suggestions")
    public WritingSuggestionResponse suggest(@Valid @RequestBody WritingSuggestionRequest request) {
        require(AiCapability.WRITING);
        try {
            return service.suggestWriting(CurrentUser.id(), request);
        }
        catch (IllegalArgumentException invalid) {
            throw AiApiException.validationFailed();
        }
    }

    private void require(AiCapability capability) {
        if (!properties.isCapabilityEnabled(capability)) throw AiApiException.disabled();
    }
}
