package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.agent.AgentCitation;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnRecord;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnStore;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSource;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSourceUrlPolicy;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * 读取当前用户唯一主对话的一页完整历史。
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
        List<AgentTurnHistoryRow> visibleRows = rows.stream().limit(size).toList();
        List<Long> turnIds = visibleRows.stream().map(AgentTurnHistoryRow::getTurnId).toList();
        Map<Long, List<AgentCitation>> citationsByTurn = historyCitations(userId, turnIds);
        Map<Long, HistoryWebSources> webSourcesByTurn = historyWebSources(userId, turnIds);
        List<AgentTurnHistoryItem> items = visibleRows.stream()
                .map(row -> {
                    HistoryWebSources web = webSourcesByTurn.getOrDefault(row.getTurnId(),
                            HistoryWebSources.EMPTY);
                    return new AgentTurnHistoryItem(row.getTurnId(), row.getQuestionPreview(),
                            row.getAnswerPreview(), row.getUserMessage(), row.getFinalMessage(),
                            citationsByTurn.getOrDefault(row.getTurnId(), List.of()),
                            web.sources(), web.expired(), row.getCreatedAt());
                })
                .toList();
        Long next = hasMore && !items.isEmpty() ? items.get(items.size() - 1).turnId() : null;
        return new AgentTurnHistoryPage(items, next);
    }

    /** 把本页站内引用按 turn 分组，并恢复与实时回答相同的安全站内链接。 */
    private Map<Long, List<AgentCitation>> historyCitations(long userId, List<Long> turnIds) {
        Map<Long, List<AgentCitation>> grouped = new HashMap<>();
        if (turnIds.isEmpty()) return grouped;
        for (var row : mapper.selectHistoryCitations(userId, turnIds)) {
            grouped.computeIfAbsent(row.getTurnId(), ignored -> new ArrayList<>()).add(
                    new AgentCitation(row.getOrdinal(), "history:" + row.getChunkId(),
                            row.getArticleId(), row.getRevisionId(), row.getChunkId(),
                            row.getTitleSnapshot(), row.getQuoteSnapshot(),
                            "/article/" + row.getArticleId()));
        }
        return grouped;
    }

    /** 把本页联网来源按 turn 分组；链接仅来自后端已校验并提交的来源快照。 */
    private Map<Long, HistoryWebSources> historyWebSources(long userId, List<Long> turnIds) {
        Map<Long, List<AgentWebSource>> active = new HashMap<>();
        Map<Long, Boolean> expired = new HashMap<>();
        if (turnIds.isEmpty()) return Map.of();
        for (var row : mapper.selectHistoryWebSources(userId, turnIds)) {
            if (!validWebSourceRow(row)) continue;
            if (!Boolean.TRUE.equals(row.getSourceActive())) {
                expired.put(row.getTurnId(), true);
                continue;
            }
            active.computeIfAbsent(row.getTurnId(), ignored -> new ArrayList<>()).add(
                    new AgentWebSource(row.getSourceIndex(), row.getExcerptSnapshot(),
                            row.getSourceUrl(), row.getSiteName()));
        }
        Map<Long, HistoryWebSources> grouped = new HashMap<>();
        for (Long turnId : turnIds) {
            grouped.put(turnId, new HistoryWebSources(active.getOrDefault(turnId, List.of()),
                    expired.getOrDefault(turnId, false)));
        }
        return grouped;
    }

    /** 历史数据也按当前安全契约校验，旧脏数据绝不能直接变成浏览器可点击链接。 */
    private boolean validWebSourceRow(AgentTurnHistoryWebSourceRow row) {
        return row.getSourceIndex() != null && row.getSourceIndex() > 0
                && row.getSourceIndex() <= 99 && row.getExcerptSnapshot() != null
                && !row.getExcerptSnapshot().isBlank() && row.getExcerptSnapshot().length() <= 300
                && row.getSiteName() != null && row.getSiteName().length() <= 160
                && AgentWebSourceUrlPolicy.isSafe(row.getSourceUrl());
    }

    private record HistoryWebSources(List<AgentWebSource> sources, boolean expired) {
        private static final HistoryWebSources EMPTY = new HistoryWebSources(List.of(), false);

        private HistoryWebSources {
            sources = List.copyOf(sources);
        }
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
