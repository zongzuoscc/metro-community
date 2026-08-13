package cumt.zongzuo.community.ai.agent.turn;

import java.time.LocalDateTime;

/**
 * 主对话历史中的一轮完整问答。
 *
 * <p>preview 字段只服务于左侧悬浮卡片，完整字段服务于连续对话正文。
 * 二者都来自同一组已提交的 FINAL message，避免前端再逐轮请求而产生瀑布流。
 * turnId 同时是稳定的定位键和向后翻页游标。</p>
 */
public record AgentTurnHistoryItem(long turnId, String questionPreview, String answerPreview,
                                   String userMessage, String finalMessage,
                                   LocalDateTime createdAt) {
}
