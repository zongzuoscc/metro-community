package cumt.zongzuo.community.ai.agent.turn;

import lombok.Data;

/** MyBatis 专用站内历史来源行，不直接暴露给控制器。 */
@Data
public class AgentTurnHistoryCitationRow {
    private Long turnId;
    private Integer ordinal;
    private Long articleId;
    private Long revisionId;
    private Long chunkId;
    private String titleSnapshot;
    private String quoteSnapshot;
}
