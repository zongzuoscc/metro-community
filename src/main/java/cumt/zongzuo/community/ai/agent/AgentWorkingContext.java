package cumt.zongzuo.community.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.history.*;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryView;
import cumt.zongzuo.community.ai.provider.*;
import java.util.*;
import java.util.function.Function;

/** 一轮内唯一的有效上下文。工具决策、最终回答和引用集合均由此视图产生；原始聊天不修改。 */
final class AgentWorkingContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    final ReActEvidence evidence = new ReActEvidence();
    private final Map<String, AgentConversationHistoryHit> recent = new LinkedHashMap<>();
    private final Map<String, AgentEpisodeSummaryView> summaries = new LinkedHashMap<>();
    private final Map<String, String> temporary = new LinkedHashMap<>();
    private final Set<String> protectedIds = new HashSet<>();
    private final Map<Long, AgentMemoryView> committed = new LinkedHashMap<>();
    private final Set<Long> removedMemories = new HashSet<>();
    private final List<String> receipts = new ArrayList<>();
    private final AgentConversationPage originalPage;
    private final boolean trimPreviewHistory;
    private boolean reduced;
    private long revision;
    private int summarySequence;

    AgentWorkingContext(AgentConversationPage page, List<AgentEpisodeSummaryView> summary, List<String> temporary) {
        this(page, summary, temporary, false);
    }

    AgentWorkingContext(AgentConversationPage page, List<AgentEpisodeSummaryView> summary,
            List<String> temporary, boolean trimPreviewHistory) {
        originalPage = page;
        this.trimPreviewHistory = trimPreviewHistory;
        if (page != null) {
            var protectedTurns = page.messages().stream().map(AgentConversationHistoryHit::turnId).distinct()
                    .sorted(Comparator.reverseOrder()).limit(3).collect(java.util.stream.Collectors.toSet());
            for (var row : page.messages()) {
                String id = "H" + row.messageId();
                recent.put(id, row);
                if (protectedTurns.contains(row.turnId())) protectedIds.add(id);
            }
        }
        for (var item : summary) summaries.put("S" + item.episodeId() + ":" + item.episodeNo(), item);
        for (int i = 0; i < temporary.size(); i++) this.temporary.put("T" + i, temporary.get(i));
        // 临时历史是角色前缀列表：最近完整一轮保留，不把用户问题与助手回复拆开。
        int latestUser = -1;
        for (int i = 0; i < temporary.size(); i++) if (temporary.get(i).startsWith("USER\t")) latestUser = i;
        if (latestUser < 0) latestUser = Math.max(0, temporary.size() - 2);
        for (int i = latestUser; i < temporary.size(); i++) protectedIds.add("T" + i);
    }

    List<AiPromptMessage> decisionMessages() {
        var rows = new ArrayList<Map<String, Object>>();
        recent.forEach((id, row) -> rows.add(fragment(id, "history", row.role() + " " + row.content())));
        summaries.forEach((id, row) -> rows.add(fragment(id, "history-summary", row.summary())));
        temporary.forEach((id, text) -> rows.add(fragment(id, "temporary-history", text)));
        var recentIds = recent.keySet();
        evidence.history().stream().filter(h -> !recentIds.contains("H" + h.messageId()))
                .forEach(h -> rows.add(fragment("H" + h.messageId(), "history", h.role() + " " + h.content())));
        evidence.memories().forEach(m -> rows.add(fragment("M" + m.id() + ":V" + m.version(), "memory", m.content())));
        evidence.articles().authorizedChunks().forEach(a -> rows.add(fragment(a.sourceId(), "article",
                a.title() + "\n" + ReActEvidence.clip(a.bodyText(), 1000))));
        if (!evidence.web().summary().isBlank() || !evidence.web().sources().isEmpty())
            rows.add(fragment("WEB", "web", evidence.web().summary() + "\n" + evidence.web().sources()));
        try {
            return List.of(new AiPromptMessage(AiPromptRole.USER,
                    JSON.writeValueAsString(Map.of("fragments", rows, "toolReceipts", List.copyOf(receipts)))));
        } catch (Exception error) { throw new IllegalStateException("Cannot encode effective context", error); }
    }

    private Map<String, Object> fragment(String id, String type, String text) {
        return Map.of("fragmentId", id, "kind", type, "protected", protectedIds.contains(id), "text", text);
    }

    void receipt(String status) { if (receipts.size() < 24) receipts.add(status); }
    long revision() { return revision; }
    boolean modified() { return revision > 0; }
    boolean reduced() { return reduced; }
    List<AgentEpisodeSummaryView> summaries() { return List.copyOf(summaries.values()); }
    List<String> temporary() { return List.copyOf(temporary.values()); }
    AgentConversationPage page() {
        if (originalPage == null) return null;
        return modified() ? new AgentConversationPage(List.copyOf(recent.values()), Long.MAX_VALUE, true) : originalPage;
    }

    /** 新写入正文可在同轮立即使用；不假称 Milvus 已经完成投影。 */
    void committed(long memoryId, AgentMemoryView view) {
        boolean changed = view == null ? !removedMemories.contains(memoryId)
                : evidence.memories().stream().noneMatch(m -> m.id() == memoryId && m.version() == view.version());
        evidence.removeMemory(memoryId);
        committed.remove(memoryId);
        if (view != null) {
            removedMemories.remove(memoryId);
            committed.put(memoryId, view);
            evidence.putCommittedMemory(view);
        } else removedMemories.add(memoryId);
        if (changed) revision++;
    }

    void addRetrievedMemories(List<AgentMemoryView> values) {
        evidence.addMemories(values.stream().filter(m -> !removedMemories.contains(m.id())
                && (!committed.containsKey(m.id()) || committed.get(m.id()).version() == m.version())).toList());
    }

    /** 请求超预算时仅裁减可选检索资料和旧临时整轮；持久原文由已批准的压缩图处理。 */
    boolean reduceToFit() {
        if (evidence.removeLowestPriority()) { revision++; reduced = true; return true; }
        if (trimPreviewHistory) {
            var oldest = recent.entrySet().stream().filter(e -> !protectedIds.contains(e.getKey()))
                    .map(e -> e.getValue().turnId()).min(Long::compareTo);
            if (oldest.isPresent()) {
                recent.values().removeIf(row -> row.turnId() == oldest.get());
                revision++; reduced = true; return true;
            }
            if (!summaries.isEmpty()) {
                summaries.remove(summaries.keySet().iterator().next());
                revision++; reduced = true; return true;
            }
        }
        var group = new ArrayList<String>();
        for (var entry : temporary.entrySet()) {
            if (entry.getValue().startsWith("USER\t") && !group.isEmpty()) break;
            group.add(entry.getKey());
        }
        if (!group.isEmpty() && group.stream().noneMatch(protectedIds::contains)) {
            temporary.keySet().removeAll(group); revision++; reduced = true; return true;
        }
        return false;
    }

    /** 校验整批选择再修改，不允许部分成功留下不完整问答。 */
    boolean filter(List<String> ids) {
        var selected = validate(ids, false);
        if (selected.isEmpty()) return false;
        remove(selected);
        revision++;
        reduced = true;
        return true;
    }

    String summary(List<String> ids, Function<String, String> summarize) {
        var selected = validate(ids, true);
        if (selected.isEmpty()) return "UNCHANGED";
        var text = new StringBuilder();
        for (String id : selected) {
            if (recent.containsKey(id)) text.append(recent.get(id).role()).append(": ").append(recent.get(id).content());
            else if (temporary.containsKey(id)) text.append(temporary.get(id));
            else if (summaries.containsKey(id)) text.append(summaries.get(id).summary());
            else evidence.history().stream().filter(h -> id.equals("H" + h.messageId()))
                        .forEach(h -> text.append(h.role()).append(": ").append(h.content()));
            text.append('\n');
        }
        String summary = summarize.apply(text.toString());
        if (summary == null || summary.isBlank() || summary.codePointCount(0, summary.length()) > 4000)
            throw new IllegalStateException("Invalid working-context summary");
        if (summary.strip().equals(text.toString().strip())) return "UNCHANGED";
        remove(selected);
        int sequence = ++summarySequence;
        summaries.put("WS" + sequence, new AgentEpisodeSummaryView(-sequence, sequence, summary, null));
        revision++;
        reduced = true;
        return "OK";
    }

    private Set<String> validate(List<String> ids, boolean historyOnly) {
        var selected = new LinkedHashSet<>(ids);
        if (selected.stream().anyMatch(protectedIds::contains)) throw new IllegalArgumentException("Protected fragment");
        var all = new HashSet<>(recent.keySet());
        all.addAll(summaries.keySet()); all.addAll(temporary.keySet());
        evidence.history().forEach(h -> all.add("H" + h.messageId()));
        if (!historyOnly) {
            evidence.memories().forEach(m -> all.add("M" + m.id() + ":V" + m.version()));
            evidence.articles().authorizedChunks().forEach(a -> all.add(a.sourceId()));
            if (!evidence.web().summary().isBlank() || !evidence.web().sources().isEmpty()) all.add("WEB");
        }
        if (!all.containsAll(selected)) throw new IllegalArgumentException("Unknown or ineligible fragment");
        var historyRows = new ArrayList<>(recent.values()); historyRows.addAll(evidence.history());
        var turns = new HashSet<Long>();
        historyRows.stream().filter(h -> selected.contains("H" + h.messageId())).forEach(h -> turns.add(h.turnId()));
        if (historyRows.stream().anyMatch(h -> turns.contains(h.turnId()) && !selected.contains("H" + h.messageId())))
            throw new IllegalArgumentException("Select complete turns");
        var group = new ArrayList<String>();
        for (var entry : temporary.entrySet()) {
            if (entry.getValue().startsWith("USER\t") && !group.isEmpty()) { validateWholeGroup(selected, group); group.clear(); }
            group.add(entry.getKey());
        }
        validateWholeGroup(selected, group);
        return selected;
    }

    private static void validateWholeGroup(Set<String> selected, List<String> group) {
        if (group.stream().anyMatch(selected::contains) && !selected.containsAll(group))
            throw new IllegalArgumentException("Select complete temporary turns");
    }

    private void remove(Set<String> selected) {
        recent.keySet().removeAll(selected); summaries.keySet().removeAll(selected); temporary.keySet().removeAll(selected);
        evidence.filter(selected);
    }
}
