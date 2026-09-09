package cumt.zongzuo.community.ai.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.ai.agent.history.*;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryView;
import cumt.zongzuo.community.ai.agent.retrieval.ResolvedArticleChunk;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSearchResult;
import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.runtime.AiExecutionException;
import cumt.zongzuo.community.ai.runtime.AiExecutionErrorReason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 把上下文选择、序列化和裁减放在同一处。每次裁减后重新估算真正发送的 JSON，保证
 * 引用校验和审计只能使用最终留下的资料。原始问题和系统约束不截断，近期问答整轮保留。
 */
public final class AgentContextAssembler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AgentPromptBudget budget;
    private final int maxCharacters;

    public AgentContextAssembler(AgentPromptBudget budget, int maxCharacters) {
        this.budget = budget;
        this.maxCharacters = maxCharacters;
    }

    public Assembled assemble(String system, String question, List<ResolvedArticleChunk> sources,
                              List<AgentMemoryView> memories, List<AgentConversationHistoryHit> history,
                              List<AgentEpisodeSummaryView> summaries,
                              List<AgentConversationHistoryHit> recent, List<String> temporary,
                              AgentWebSearchResult web, AgentPromptBudget.Limits limits) {
        var selected = new Selection(sources, memories, history, summaries, recent, temporary, web);
        return fit(system, question, selected, limits, false);
    }

    private Assembled fit(String system, String question, Selection selected,
                          AgentPromptBudget.Limits limits, boolean reduced) {
        while (true) {
            List<AiPromptMessage> prompt = prompt(system, question, selected, reduced);
            int tokens = budget.estimate(prompt);
            int characters = prompt.stream().mapToInt(m -> m.text().length()).sum();
            if (tokens <= limits.inputTokens() && characters <= maxCharacters) {
                return new Assembled(prompt, List.copyOf(selected.sources),
                        List.copyOf(selected.memories), selected.usedHistory(), selected.web,
                        tokens, limits.outputTokens(), reduced,
                        !selected.memories.isEmpty() || !selected.history.isEmpty()
                                || !selected.recent.isEmpty() || !selected.temporary.isEmpty()
                                || !selected.summaries.isEmpty());
            }
            if (!selected.removeLowestPriority()) {
                // 必选内容仍超限时明确失败，不静默截断用户问题、更不发送超预算请求。
                throw new AiExecutionException(AiExecutionErrorReason.INPUT_TOO_LARGE,
                        "当前问题超过所选模型的输入预算，请缩短问题或选择更大窗口的模型");
            }
            reduced = true;
        }
    }

    /** 从最新一页开始，用剩余预算决定是否继续向前读取。 */
    public Assembled assemblePaged(String system, String question, List<ResolvedArticleChunk> sources,
                                   List<AgentMemoryView> memories, List<AgentConversationHistoryHit> history,
                                   List<AgentEpisodeSummaryView> summaries, AgentConversationPage firstPage,
                                   AgentWebSearchResult web, AgentPromptBudget.Limits limits,
                                   java.util.function.LongFunction<AgentConversationPage> olderPages,
                                   java.util.function.BooleanSupplier mayRead) {
        // 先保护最新完整一轮与本次检索依据，再用剩余空间填更早历史。
        // 每页大小不能影响保留优先级：第一页中的旧轮次也必须走同样的预算检查。
        long newestTurn = firstPage.messages().stream().mapToLong(AgentConversationHistoryHit::turnId)
                .max().orElse(-1);
        var newest = firstPage.messages().stream().filter(row -> row.turnId() == newestTurn).toList();
        var selected = new Selection(sources, memories, history, summaries, newest, List.of(), web);
        var initial = fit(system, question, selected, limits, false);
        // 最新整轮最终仍装不下，说明先前为保护它而丢掉资料没有收益。
        // 从原始检索依据重新试装，但不再补更早连续历史，也不允许相关历史
        // 把刚被整轮移除的消息重新带回，避免小模型切换后既丢原文又丢依据。
        if (selected.recent.size() < newest.size()) {
            var withoutOversizedTurn = new Selection(sources, memories,
                    history.stream().filter(row -> row.turnId() != newestTurn).toList(),
                    summaries, List.of(), List.of(), web);
            return fit(system, question, withoutOversizedTurn, limits, true);
        }
        boolean reduced = initial.reduced();
        boolean exhausted = false;
        long cursor = Long.MAX_VALUE;
        AgentConversationPage page = new AgentConversationPage(firstPage.messages().stream()
                .filter(row -> row.turnId() != newestTurn).toList(),
                firstPage.nextBeforeTurnId(), firstPage.exhausted());
        while (true) {
            if (!page.exhausted() && page.nextBeforeTurnId() >= cursor) {
                throw new IllegalStateException("History cursor must move backwards");
            }
            // 每页先作为整体尝试；放不下时二分找能保留的最新连续若干轮。
            // 不逐条重算整个长提示词，单页最多 O(log(pageSize)) 次完整估算。
            var grouped = new java.util.TreeMap<Long, List<AgentConversationHistoryHit>>(
                    java.util.Comparator.reverseOrder());
            for (var row : page.messages()) {
                if (row.turnId() >= cursor) throw new IllegalStateException("History page overlaps its cursor");
                grouped.computeIfAbsent(row.turnId(), ignored -> new ArrayList<>()).add(row);
            }
            var turns = new ArrayList<>(grouped.values());
            Selection all = prepend(selected, turns, turns.size());
            int accepted = turns.size();
            if (!fits(system, question, all, limits)) {
                int low = 0, high = turns.size();
                while (low < high) {
                    int middle = (low + high + 1) / 2;
                    if (fits(system, question, prepend(selected, turns, middle), limits)) low = middle;
                    else high = middle - 1;
                }
                accepted = low;
                all = prepend(selected, turns, accepted);
            }
            selected = all;
            if (accepted < turns.size()) {
                reduced = true;
                break;
            }
            exhausted = page.exhausted();
            cursor = page.nextBeforeTurnId();
            if (exhausted || !mayRead.getAsBoolean()) break;
            try {
                page = olderPages.apply(cursor);
            } catch (org.springframework.dao.DataAccessException unavailableHistory) {
                // 追加历史属于可选增强。单页超时/数据库暂时异常不丢弃已读取的有效上下文。
                break;
            }
            if (page == null) throw new IllegalStateException("History page must not be null");
        }
        // 时限耗尽或窗口装满都要明确标记历史并非全集，不能暗示遗漏部分不存在。
        return fit(system, question, selected, limits, reduced || !exhausted);
    }

    public Assembled assemblePrepared(String system, String question, List<ResolvedArticleChunk> sources,
                                      List<AgentMemoryView> memories, List<AgentConversationHistoryHit> history,
                                      List<AgentEpisodeSummaryView> summaries, AgentConversationPage page,
                                      AgentWebSearchResult web, AgentPromptBudget.Limits limits) {
        if (!page.exhausted()) throw new IllegalArgumentException("Prepared context must have a closed history boundary");
        var selected=new Selection(sources,memories,history,summaries,page.messages(),List.of(),web);
        // 已完成压缩的摘要替代了旧原文，不能像可选旧 episode 摘要一样随手删掉。
        // 先裁减检索资料；最近三轮与新摘要仍超限时明确失败，而不是悄悄退回一轮。
        selected.protectedRecentTurns=page.messages().stream().map(AgentConversationHistoryHit::turnId)
                .distinct().sorted(java.util.Comparator.reverseOrder()).limit(3)
                .collect(java.util.stream.Collectors.toSet());
        selected.protectSummaries=true;
        return fit(system,question,selected,limits,false);
    }

    private boolean fits(String system, String question, Selection selected, AgentPromptBudget.Limits limits) {
        var messages = prompt(system, question, selected, true);
        return messages.stream().mapToInt(m -> m.text().length()).sum() <= maxCharacters
                && budget.estimate(messages) <= limits.inputTokens();
    }

    private static Selection prepend(Selection selected,
                                      List<List<AgentConversationHistoryHit>> newestFirst, int count) {
        var recent = new ArrayList<AgentConversationHistoryHit>();
        for (int index = count - 1; index >= 0; index--) recent.addAll(newestFirst.get(index));
        recent.addAll(selected.recent);
        recent.sort(java.util.Comparator.comparingLong(AgentConversationHistoryHit::messageId));
        // 构造器统一按 messageId 去重，避免刚加载的旧消息又在相关历史中重复计费。
        return new Selection(selected.sources, selected.memories, selected.history,
                selected.summaries, recent, selected.temporary, selected.web);
    }

    private static List<AiPromptMessage> prompt(String system, String question,
                                                Selection selected, boolean reduced) {
        ObjectNode data = JSON.createObjectNode().put("question", question)
                .put("contextReduced", reduced);
        var sources = data.putArray("sources");
        for (var source : selected.sources) {
            var item = sources.addObject().put("sourceId", source.sourceId())
                    .put("title", source.title()).put("bodyText", source.bodyText());
            var headings = item.putArray("headingPath");
            source.headingPath().forEach(headings::add);
        }
        var memories = data.putArray("memories");
        selected.memories.forEach(m -> memories.addObject().put("memoryId", m.id())
                .put("version", m.version()).put("category", m.category()).put("content", m.content()));
        appendHistory(data, "conversationHistory", selected.history);
        appendHistory(data, "recentConversation", selected.recent);
        var summaries = data.putArray("episodeSummaries");
        selected.summaries.forEach(s -> summaries.addObject().put("episodeId", s.episodeId())
                .put("episodeNo", s.episodeNo()).put("summary", s.summary())
                .put("sealedAt", s.sealedAt() == null ? "" : s.sealedAt().toString()));
        var temporary = data.putArray("temporaryConversation");
        selected.temporary.forEach(temporary::add);
        var web = data.putObject("webSearch").put("summary", selected.web.summary());
        var webSources = web.putArray("sources");
        selected.web.sources().forEach(s -> webSources.addObject().put("index", s.index())
                .put("title", s.title()).put("url", s.url()).put("siteName", s.siteName()));
        return List.of(new AiPromptMessage(AiPromptRole.SYSTEM, system),
                new AiPromptMessage(AiPromptRole.USER, "UNTRUSTED_COMMUNITY_DATA_JSON:\n" + data));
    }

    private static void appendHistory(ObjectNode data, String name,
                                       List<AgentConversationHistoryHit> rows) {
        var array = data.putArray(name);
        rows.forEach(h -> array.addObject().put("messageId", h.messageId()).put("turnId", h.turnId())
                .put("role", h.role()).put("content", h.content())
                .put("createdAt", h.createdAt().toString()));
    }

    /** 返回的 sources/history 即最终发送的集合，调用方不可继续使用裁减前的全集。 */
    public record Assembled(List<AiPromptMessage> messages, List<ResolvedArticleChunk> sources,
                             List<AgentMemoryView> memories, List<AgentConversationHistoryHit> history,
                             AgentWebSearchResult web, int estimatedInputTokens,
                             int maxOutputTokens, boolean reduced, boolean hasPersonalContext) { }

    private static final class Selection {
        final ArrayList<ResolvedArticleChunk> sources;
        final ArrayList<AgentMemoryView> memories;
        final ArrayList<AgentConversationHistoryHit> history;
        final ArrayList<AgentEpisodeSummaryView> summaries;
        final ArrayList<AgentConversationHistoryHit> recent;
        final ArrayList<String> temporary;
        AgentWebSearchResult web;
        java.util.Set<Long> protectedRecentTurns=java.util.Set.of();
        boolean protectSummaries;

        Selection(List<ResolvedArticleChunk> sources, List<AgentMemoryView> memories,
                  List<AgentConversationHistoryHit> history, List<AgentEpisodeSummaryView> summaries,
                  List<AgentConversationHistoryHit> recent, List<String> temporary, AgentWebSearchResult web) {
            this.sources = new ArrayList<>(sources);
            this.memories = new ArrayList<>(memories);
            this.recent = new ArrayList<>(recent);
            var recentIds = new HashSet<Long>();
            recent.forEach(row -> recentIds.add(row.messageId()));
            this.history = new ArrayList<>(history.stream()
                    .filter(row -> !recentIds.contains(row.messageId())).toList());
            this.summaries = new ArrayList<>(summaries);
            this.temporary = new ArrayList<>(temporary);
            this.web = web;
        }

        List<AgentConversationHistoryHit> usedHistory() {
            var result = new ArrayList<>(recent);
            result.addAll(history);
            return List.copyOf(result);
        }

        /** 先减少检索补充信息，最后才从最老的完整问答开始滑动近期窗口。 */
        boolean removeLowestPriority() {
            if (!sources.isEmpty()) { sources.removeLast(); return true; }
            if (!history.isEmpty()) { history.removeLast(); return true; }
            if (!memories.isEmpty()) { memories.removeLast(); return true; }
            if (!web.summary().isEmpty() || !web.sources().isEmpty()) {
                web = AgentWebSearchResult.empty(); return true;
            }
            if (!protectSummaries && !summaries.isEmpty()) { summaries.removeFirst(); return true; }
            if (!recent.isEmpty()) {
                long oldest = recent.getFirst().turnId();
                if (!protectedRecentTurns.contains(oldest)) {
                    recent.removeIf(row -> row.turnId() == oldest);
                    return true;
                }
            }
            if (!temporary.isEmpty()) {
                temporary.removeFirst();
                // Redis 条目含角色前缀，删除对应助手回复，避免裁减后留下孤立回答。
                if (!temporary.isEmpty() && temporary.getFirst().startsWith("ASSISTANT\t")) {
                    temporary.removeFirst();
                }
                return true;
            }
            return false;
        }
    }
}
