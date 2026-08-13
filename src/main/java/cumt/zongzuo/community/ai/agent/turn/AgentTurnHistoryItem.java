package cumt.zongzuo.community.ai.agent.turn;

import java.time.LocalDateTime;

/**
 * 主对话历史轨道中的一条轻量摘要。
 *
 * <p>问题和回答都由服务端从已提交的 FINAL message 截取，不让前端为了显示
 * 一条细轨道就下载全部长文。turnId 同时是稳定的定位键和向后翻页游标。</p>
 */
public record AgentTurnHistoryItem(long turnId, String questionPreview, String answerPreview,
                                   LocalDateTime createdAt) {
}
