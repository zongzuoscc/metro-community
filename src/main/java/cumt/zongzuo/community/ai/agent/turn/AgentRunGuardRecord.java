package cumt.zongzuo.community.ai.agent.turn;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class AgentRunGuardRecord {
    private Long userId;
    private UUID activeRunId;
    private String activeRunType;
    private Long runFence;
    private LocalDateTime leaseUntil;
    private Long lockVersion;
    private LocalDateTime updatedAt;
}
