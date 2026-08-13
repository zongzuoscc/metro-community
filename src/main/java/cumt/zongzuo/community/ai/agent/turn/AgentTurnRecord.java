package cumt.zongzuo.community.ai.agent.turn;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class AgentTurnRecord {
    private Long id;
    private Long userId;
    private Long conversationId;
    private Long episodeId;
    private UUID runId;
    private UUID clientRequestId;
    private String requestHash;
    private String taskType;
    private String pageContextJson;
    private String groundingMode;
    private Boolean webSearchEnabled;
    private String state;
    private Long runFence;
    private LocalDateTime leaseUntil;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
