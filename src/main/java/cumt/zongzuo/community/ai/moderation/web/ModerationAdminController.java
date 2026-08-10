package cumt.zongzuo.community.ai.moderation.web;

import cumt.zongzuo.community.ai.moderation.revision.ArticleModerationDecisionService;
import cumt.zongzuo.community.ai.web.AiApi;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import cumt.zongzuo.community.security.CurrentUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@AiApi
@Validated
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/moderation")
public class ModerationAdminController {

    private final ArticleModerationDecisionService decisionService;

    public ModerationAdminController(ArticleModerationDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @GetMapping("/jobs/{jobId}")
    public ModerationJobResponse get(@PathVariable @Min(1) long jobId) {
        return decisionService.get(jobId);
    }

    @GetMapping("/jobs")
    public ModerationJobPageResponse list(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) @Min(1) Long before,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return decisionService.list(state, before, size);
    }

    @PostMapping("/jobs/{jobId}/approve")
    public ModerationJobResponse approve(@PathVariable @Min(1) long jobId,
                                         @Valid @RequestBody ModerationDecisionRequest request) {
        return decisionService.approve(jobId, request, CurrentUser.id());
    }

    @PostMapping("/jobs/{jobId}/reject")
    public ModerationJobResponse reject(@PathVariable @Min(1) long jobId,
                                        @Valid @RequestBody ModerationDecisionRequest request) {
        return decisionService.reject(jobId, request, CurrentUser.id());
    }
}
