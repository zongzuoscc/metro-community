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

    @PutMapping("/memory-settings")
    public AgentMemoryManagementService.MemorySettingView settings(
            @Valid @RequestBody AgentMemorySettingRequest request) {
        return memories.updateSetting(CurrentUser.id(), request.enabled(), request.expectedVersion());
    }
}
