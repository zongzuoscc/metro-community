package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryView;
import cumt.zongzuo.community.ai.agent.retrieval.ArticleRetrievalResult;
import cumt.zongzuo.community.ai.agent.retrieval.ResolvedArticleChunk;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSearchResult;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSource;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 单轮回答独享的证据累加器。ReAct 可以多次调用同一工具，不能再覆盖上一批结果，
 * 也不能把两个搜索响应各自的 W1 当作同一个网页。这里只保存已经过工具授权的资料；
 * 最终提示词仍由上下文预算器筛选，最终引用仍由严格解析器验证。
 */
final class ReActEvidence {
    private static final int MAX_ITEMS = 48;
    private static final Pattern WEB_MARKER = Pattern.compile("\\[W(\\d+)]");
    private final Map<String, ResolvedArticleChunk> articles = new LinkedHashMap<>();
    private final Map<Long, AgentMemoryView> memories = new LinkedHashMap<>();
    private final Map<Long, AgentConversationHistoryHit> history = new LinkedHashMap<>();
    private final Map<String, AgentWebSource> web = new LinkedHashMap<>();
    private final List<String> webSummaries = new ArrayList<>();
    private boolean lexicalAvailable;
    private boolean denseAvailable;

    void addArticles(ArticleRetrievalResult result) {
        lexicalAvailable |= result.lexicalAvailable();
        denseAvailable |= result.denseAvailable();
        for (var chunk : result.authorizedChunks()) {
            // 同一文章后续检索命中了新修订时，不能混合引用先前的旧修订。
            articles.values().removeIf(old -> old.articleId() == chunk.articleId()
                    && old.revisionId() != chunk.revisionId());
            if (articles.size() < MAX_ITEMS || articles.containsKey(chunk.sourceId())) {
                articles.put(chunk.sourceId(), chunk);
            }
        }
    }

    void addMemories(List<AgentMemoryView> values) {
        for (var value : values) {
            if (memories.size() < MAX_ITEMS || memories.containsKey(value.id())) memories.put(value.id(), value);
        }
    }

    void addHistory(List<AgentConversationHistoryHit> values) {
        for (var value : values) {
            if (history.size() < MAX_ITEMS || history.containsKey(value.messageId())) history.put(value.messageId(), value);
        }
    }

    void addWeb(AgentWebSearchResult result) {
        // 同一响应不能用一个编号指向两个网址。先检查整批，避免抛错后留下半批证据。
        Map<Integer, String> originalUrls = new HashMap<>();
        for (var source : result.sources()) {
            String previous = originalUrls.putIfAbsent(source.index(), source.url());
            if (previous != null && !previous.equals(source.url())) {
                throw new IllegalArgumentException("Conflicting web source numbers");
            }
        }
        Map<Integer, Integer> remap = new HashMap<>();
        for (var source : result.sources()) {
            var saved = web.get(source.url());
            if (saved == null && web.size() < MAX_ITEMS) {
                saved = new AgentWebSource(web.size() + 1, source.title(), source.url(), source.siteName());
                web.put(source.url(), saved);
            }
            if (saved != null) remap.put(source.index(), saved.index());
        }
        // Matcher 单次替换避免 W1→W2 后又被 W2→W1 二次替换。
        String rebased = WEB_MARKER.matcher(clip(result.summary(), 8_000)).replaceAll(match -> {
            Integer index;
            try { index = remap.get(Integer.parseInt(match.group(1))); }
            catch (NumberFormatException invalid) { index = null; }
            return index == null ? "[来源不可用]" : "[W" + index + "]";
        });
        if (!rebased.isBlank() && !webSummaries.contains(rebased) && webSummaries.size() < 24) {
            webSummaries.add(rebased);
        }
    }

    ArticleRetrievalResult articles() {
        return new ArticleRetrievalResult(articles.size(),0,lexicalAvailable,denseAvailable,
                List.copyOf(articles.values()),List.of());
    }
    List<AgentMemoryView> memories() { return List.copyOf(memories.values()); }
    List<AgentConversationHistoryHit> history() { return List.copyOf(history.values()); }
    AgentWebSearchResult web() { return new AgentWebSearchResult(String.join("\n",webSummaries),List.copyOf(web.values())); }

    /** 用稳定的依据标识判断是否出现新信息，而不是把查询成功等同于有效进展。 */
    Set<String> identities() {
        Set<String> ids = new HashSet<>(articles.keySet());
        memories.values().forEach(value -> ids.add("M" + value.id() + ":" + value.version()));
        history.keySet().forEach(id -> ids.add("H" + id));
        web.keySet().forEach(url -> ids.add("W" + url));
        return ids;
    }

    static String clip(String text, int codePoints) {
        if (text == null) return "";
        int count = text.codePointCount(0,text.length());
        return count <= codePoints ? text : text.substring(0,text.offsetByCodePoints(0,codePoints)) + "…[截断]";
    }
}
