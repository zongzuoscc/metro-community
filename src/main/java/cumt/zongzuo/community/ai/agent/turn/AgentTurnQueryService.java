package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnRecord;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统一构建当前用户可见的 turn 快照。
 * 持久 turn 从 MySQL 聚合消息与引用，临时 turn 只从 Redis 读取，两者对外共用同一响应结构。
 */
@Service
public class AgentTurnQueryService {

    private final AgentTurnMapper mapper;
    private final TemporaryTurnStore temporaryTurns;

    public AgentTurnQueryService(AgentTurnMapper mapper, TemporaryTurnStore temporaryTurns) {
        this.mapper = mapper;
        this.temporaryTurns = temporaryTurns;
    }

    /**
     * 返回当前用户可见的 turn 快照。负 ID 只查 Redis 临时内容，正 ID 只查 MySQL 持久内容，
     * 从入口处避免两种隐私边界被错误合并。
     */
    public AgentTurnSnapshot snapshot(long turnId, long userId) {
        if (turnId < 0) return temporarySnapshot(turnId, userId);
        AgentTurnRecord turn = mapper.selectById(turnId, userId);
        if (turn == null) {
            throw AiApiException.resourceNotFound();
        }
        String assistant = mapper.selectMessageContent(turnId, userId, "ASSISTANT");
        Long messageId = mapper.selectAssistantMessageId(turnId, userId);
        int citationCount = messageId == null ? 0 : mapper.countCitations(messageId, userId);
        return new AgentTurnSnapshot(turnId, turn.getState(), turn.getTaskType(), false,
                turn.getCreatedAt(), turn.getStartedAt(), turn.getCompletedAt(),
                mapper.selectMessageContent(turnId, userId, "USER"), null, assistant,
                messageId, citationCount, turn.getErrorCode());
    }

    /**
     * 读取当前用户唯一主对话的历史摘要。
     *
     * <p>游标只能向更早的 turnId 移动，页大小上限为 50，避免全屏侧边栏变成
     * 一次性导出全部对话的隐式接口。</p>
     */
    public AgentTurnHistoryPage history(long userId, Long beforeTurnId, int size) {
        if (size < 1 || size > 50 || (beforeTurnId != null && beforeTurnId < 1)) {
            throw AiApiException.validationFailed();
        }
        List<AgentTurnHistoryRow> rows = mapper.selectHistory(
                userId, beforeTurnId, size + 1);
        boolean hasMore = rows.size() > size;
        List<AgentTurnHistoryItem> items = rows.stream().limit(size)
                .map(row -> new AgentTurnHistoryItem(row.getTurnId(), row.getQuestionPreview(),
                        row.getAnswerPreview(), row.getCreatedAt()))
                .toList();
        Long next = hasMore && !items.isEmpty() ? items.get(items.size() - 1).turnId() : null;
        return new AgentTurnHistoryPage(items, next);
    }

    /**
     * 将可丢弃的 Redis turn 映射为公共快照。
     * 临时回答没有持久 messageId，所以该字段固定为 null，temporary 固定为 true。
     */
    private AgentTurnSnapshot temporarySnapshot(long turnId, long userId) {
        TemporaryTurnRecord turn = temporaryTurns.find(turnId, userId);
        if (turn == null) throw AiApiException.resourceNotFound();
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        return new AgentTurnSnapshot(turnId, turn.state(), "COMMUNITY_QA", true,
                java.time.LocalDateTime.ofInstant(turn.createdAt(), zone),
                java.time.LocalDateTime.ofInstant(turn.createdAt(), zone),
                turn.completedAt() == null ? null
                        : java.time.LocalDateTime.ofInstant(turn.completedAt(), zone),
                turn.question(), null, turn.answer(), null, turn.citationCount(), turn.errorCode());
    }
}
