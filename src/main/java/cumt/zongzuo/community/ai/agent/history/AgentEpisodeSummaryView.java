package cumt.zongzuo.community.ai.agent.history;

import java.time.LocalDateTime;

/** 提供给后续回答的已完成滚动摘要，不包含原始消息或内部模型元数据。 */
public record AgentEpisodeSummaryView(long episodeId, int episodeNo, String summary,
                                      LocalDateTime sealedAt) {
}
