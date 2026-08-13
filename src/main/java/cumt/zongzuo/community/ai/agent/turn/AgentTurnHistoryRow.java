package cumt.zongzuo.community.ai.agent.turn;

import lombok.Data;

import java.time.LocalDateTime;

/** MyBatis 内部投影行，只承载历史轨道所需的有界字段。 */
@Data
public class AgentTurnHistoryRow {
    private Long turnId;
    private String questionPreview;
    private String answerPreview;
    private LocalDateTime createdAt;
}
