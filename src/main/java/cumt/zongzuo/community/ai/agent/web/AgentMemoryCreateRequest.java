package cumt.zongzuo.community.ai.agent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 手动记忆请求；类别只允许当前产品已支持的三种稳定事实。 */
public record AgentMemoryCreateRequest(
        @NotNull @Pattern(regexp = "PREFERENCE|GOAL|PROFILE") String category,
        @NotBlank @Size(max = 1000) String content,
        LocalDateTime expiresAt) {
}
