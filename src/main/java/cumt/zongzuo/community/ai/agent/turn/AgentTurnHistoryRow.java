package cumt.zongzuo.community.ai.agent.turn;

import lombok.Data;

import java.time.LocalDateTime;

/** MyBatis 内部投影行，同时承载轨道摘要和连续对话正文。 */
@Data
public class AgentTurnHistoryRow {
    private Long turnId;
    private String questionPreview;
    private String answerPreview;
    private String userMessage;
    private String finalMessage;
    private LocalDateTime createdAt;
}
