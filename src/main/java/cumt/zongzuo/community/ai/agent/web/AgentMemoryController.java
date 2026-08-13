package cumt.zongzuo.community.ai.agent.web;

import cumt.zongzuo.community.ai.agent.memory.AgentMemoryManagementService;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryView;
import cumt.zongzuo.community.ai.web.AiApi;
import cumt.zongzuo.community.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

@AiApi
@RestController
@ConditionalOnProperty(name = {"metro.ai.enabled", "metro.ai.memory.enabled"}, havingValue = "true")
@RequestMapping("/api/agent")
public class AgentMemoryController {

    private final AgentMemoryManagementService memories;

    public AgentMemoryController(AgentMemoryManagementService memories) {
        this.memories = memories;
    }

    @GetMapping("/memories")
    public List<AgentMemoryView> list() {
        return memories.list(CurrentUser.id());
    }

    @GetMapping("/memories/{memoryId}")
    public AgentMemoryView get(@PathVariable long memoryId) {
        return memories.get(CurrentUser.id(), memoryId);
    }

    /** 用户主动添加长期记忆，后端仍执行敏感信息拦截和去重。 */
    @PostMapping("/memories")
    public ResponseEntity<AgentMemoryView> create(
            @Valid @RequestBody AgentMemoryCreateRequest request) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(memories.create(CurrentUser.id(), request.category(), request.content(),
                        request.expiresAt()));
    }

    @PutMapping("/memories/{memoryId}")
    public AgentMemoryView edit(@PathVariable long memoryId,
                                @Valid @RequestBody AgentMemoryUpdateRequest request) {
        return memories.edit(CurrentUser.id(), memoryId, request.content(), request.expectedVersion());
    }

    @DeleteMapping("/memories/{memoryId}")
    public ResponseEntity<Void> delete(@PathVariable long memoryId) {
        memories.delete(CurrentUser.id(), memoryId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/memories/{memoryId}/state")
    public AgentMemoryView updateState(@PathVariable long memoryId,
                                       @Valid @RequestBody AgentMemoryStateRequest request) {
        return memories.updateState(CurrentUser.id(), memoryId, request.paused(), request.expectedVersion());
    }

    /** 单独更新到期时间，不制造一个内容未变的假版本。 */
    @PutMapping("/memories/{memoryId}/expiry")
    public AgentMemoryView updateExpiry(@PathVariable long memoryId,
                                        @Valid @RequestBody AgentMemoryExpiryRequest request) {
        return memories.updateExpiry(CurrentUser.id(), memoryId, request.expiresAt(),
                request.expectedVersion());
    }

    @GetMapping("/memory-settings")
    public AgentMemoryManagementService.MemorySettingView settings() {
        return memories.setting(CurrentUser.id());
    }

    @PutMapping("/memory-settings")
    public AgentMemoryManagementService.MemorySettingView settings(
            @Valid @RequestBody AgentMemorySettingRequest request) {
        return memories.updateSetting(CurrentUser.id(), request.enabled(), request.expectedVersion());
    }
}
