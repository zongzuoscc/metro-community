package cumt.zongzuo.community.ai.agent.turn;

import lombok.Data;

/** MyBatis 专用联网历史来源行，字段均来自回答完成时保存的来源快照。 */
@Data
public class AgentTurnHistoryWebSourceRow {
    private Long turnId;
    private Integer rankNo;
    private Integer sourceIndex;
    private String excerptSnapshot;
    private String sourceUrl;
    private String siteName;
    private Boolean sourceActive;
}
