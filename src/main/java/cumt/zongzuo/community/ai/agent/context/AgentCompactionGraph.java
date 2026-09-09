package cumt.zongzuo.community.ai.agent.context;

import com.alibaba.cloud.ai.graph.StateGraph;
import cumt.zongzuo.community.ai.agent.history.*;
import cumt.zongzuo.community.ai.agent.memory.AgentMemorySafetyPolicy;
import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.userprovider.PreparedUserAiChat;
import java.time.*;
import java.util.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * 用 Alibaba Graph 执行“加载→压缩→落库→向量可见→启用→重新检查”的回答前流水线。
 * Graph 只保存控制状态；正文、路由与凭据不进入框架 checkpoint/日志。可靠恢复依赖 MySQL 批次，
 * 不是进程内 MemorySaver。整个 Graph 不开启数据库事务，只有 store.stage/activate 使用短事务。
 */
public final class AgentCompactionGraph {
    private static final int KEEP_RECENT_TURNS = 3;
    private static final int PAGE_TURNS = 24;
    private static final int MAX_BATCHES_PER_REQUEST = 8;
    private final AgentCompactionStore store;
    private final AgentCompactionModel model;
    private final MemoryIndex index;
    private final AgentPromptBudget budget;
    private final AgentMemorySafetyPolicy safety;
    private final Clock clock;
    private final int maxInputCharacters;

    @FunctionalInterface public interface MemoryIndex { void synchronize(long userId, Instant deadline); }
    public record Prepared(AgentConversationPage recent, List<AgentEpisodeSummaryView> summaries) { }

    public AgentCompactionGraph(AgentCompactionStore store, AgentCompactionModel model,
                               MemoryIndex index, AgentPromptBudget budget,
                               AgentMemorySafetyPolicy safety, Clock clock, int maxInputCharacters) {
        this.store=store; this.model=model; this.index=index; this.budget=budget;
        this.safety=safety; this.clock=clock; this.maxInputCharacters=maxInputCharacters;
    }

    public Prepared prepare(long userId, UUID runId, String question, PreparedUserAiChat route,
                            Instant deadline) {
        // 每次调用独立闭包，不在单例上放用户状态；图内只传递布尔路由标志。
        Work work = new Work(userId, runId, question, route, deadline);
        try {
            var graph = new StateGraph("agent-context-compaction", () -> Map.of(
                    "compact",new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy()))
                    .addNode("load", node_async(state -> { work.load(); return Map.of("compact",work.compact); }))
                    .addNode("extract", node_async(state -> { work.extract(); return Map.of(); }))
                    .addNode("stage", node_async(state -> { work.stage(); return Map.of(); }))
                    .addNode("index", node_async(state -> { work.index(); return Map.of(); }))
                    .addNode("activate", node_async(state -> { work.activate(); return Map.of(); }))
                    .addEdge(StateGraph.START,"load")
                    .addConditionalEdges("load",edge_async(state -> work.compact ? "yes":"no"),
                            Map.of("yes","extract","no",StateGraph.END))
                    .addEdge("extract","stage").addEdge("stage","index")
                    .addEdge("index","activate").addEdge("activate","load").compile();
            graph.setMaxIterations(64);
            graph.invoke(Map.of()).orElseThrow(() -> new IllegalStateException("Context graph returned no state"));
            return Objects.requireNonNull(work.prepared,"Context preparation did not finish");
        } catch (com.alibaba.cloud.ai.graph.exception.GraphStateException invalidGraph) {
            throw new IllegalStateException("Invalid context graph",invalidGraph);
        }
    }

    private final class Work {
        final long userId; final UUID run; final String question; final PreparedUserAiChat route;
        final Instant deadline;
        AgentCompactionStore.Snapshot snapshot;
        AgentCompactionStore.Batch pending;
        AgentCompactionModel.Extraction extraction;
        List<AgentConversationHistoryHit> prefix;
        long through; boolean compact; int batches;
        Prepared prepared;
        Work(long userId,UUID run,String question,PreparedUserAiChat route,Instant deadline) {
            this.userId=userId; this.run=run; this.question=question; this.route=route; this.deadline=deadline;
        }
        void checkDeadline() {
            if (!clock.instant().isBefore(deadline) || Thread.currentThread().isInterrupted())
                throw new IllegalStateException("Context preparation deadline exceeded");
        }
        void load() {
            checkDeadline();
            snapshot=Objects.requireNonNull(store.snapshot(userId),"Conversation does not exist");
            pending=store.pending(userId,snapshot.boundary(),snapshot.coveredThroughTurnId());
            extraction=null; prefix=List.of();
            if (pending!=null) { compact=true; return; }
            var rawRecent=store.recent(userId,snapshot.boundary(),KEEP_RECENT_TURNS);
            var recent=safeTurns(rawRecent);
            long firstRecent=rawRecent.stream().mapToLong(AgentConversationHistoryHit::turnId).min().orElse(Long.MAX_VALUE);
            List<AgentConversationHistoryHit> loaded=new ArrayList<>();
            long cursor=snapshot.coveredThroughTurnId();
            var limits=budget.limits(route.model(),route.fundingSource());
            // 70% 是触发水位，不是固定截取条数；余量留给本轮问题、检索资料、系统提示和压缩格式。
            int trigger=(int)(limits.inputTokens()*0.70);
            int prefixLimit=Math.max(256,limits.inputTokens()-estimate(snapshot.summary())-1200);
            int extractionCharacters=Math.max(512,maxInputCharacters-snapshot.summary().length()-5000);
            boolean exhausted=false;
            while (!exhausted) {
                checkDeadline();
                var page=store.messages(userId,snapshot.boundary(),cursor,firstRecent,PAGE_TURNS);
                if (page.isEmpty()) { exhausted=true; break; }
                var groups=new LinkedHashMap<Long,List<AgentConversationHistoryHit>>();
                page.forEach(m -> groups.computeIfAbsent(m.turnId(),k->new ArrayList<>()).add(m));
                for (var entry:groups.entrySet()) {
                    var safe=safeTurns(entry.getValue());
                    var trial=new ArrayList<>(loaded); trial.addAll(safe);
                    if (estimateMessages(trial)>prefixLimit || characters(trial)>extractionCharacters
                            || !model.fits(request(trial,entry.getKey()))) {
                        if (cursor==snapshot.coveredThroughTurnId())
                            throw new IllegalStateException("A completed turn exceeds the compaction model input budget");
                        compact=true; prefix=List.copyOf(loaded); through=cursor; return;
                    }
                    loaded.addAll(safe); cursor=entry.getKey();
                    if (estimateMessages(loaded)+estimateMessages(recent)+estimate(snapshot.summary())+estimate(question)>trigger) {
                        compact=true; prefix=List.copyOf(loaded); through=cursor; return;
                    }
                }
                exhausted=groups.size()<PAGE_TURNS;
            }
            loaded.addAll(recent);
            // 这里已读完当前未压缩范围，后续组装不再翻页把被压缩的旧原文重新全部装回去。
            prepared=new Prepared(new AgentConversationPage(loaded,Long.MAX_VALUE,true),
                    snapshot.summary().isBlank() ? List.of() : List.of(new AgentEpisodeSummaryView(
                            snapshot.conversationId(),snapshot.boundary(),snapshot.summary(),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC))));
            compact=false;
        }
        void extract() {
            checkDeadline();
            if (++batches>MAX_BATCHES_PER_REQUEST) throw new IllegalStateException("Context backlog needs another retry");
            if (pending!=null) return;
            extraction=prefix.isEmpty() ? new AgentCompactionModel.Extraction(
                    snapshot.summary().isBlank()?"较早的敏感对话已从工作上下文中省略。":snapshot.summary(),List.of())
                    : model.extract(request(prefix,through));
        }
        AgentCompactionModel.Request request(List<AgentConversationHistoryHit> messages,long end) {
            return new AgentCompactionModel.Request(userId,run+":compact:"+end,snapshot.summary(),
                    messages,snapshot.memoryEnabled(),route,deadline);
        }
        void stage() {
            checkDeadline();
            if (pending==null) pending=store.stage(userId,run,snapshot,through,extraction,prefix);
        }
        void index() {
            checkDeadline();
            // 旧批次可能在全局关闭记忆之前保存；当前关闭时仅恢复工作摘要，
            // 不要求已停用的向量服务。原待投影事实保留，重开后由召回同步恢复。
            if (snapshot.memoryEnabled() && pending.memoryEnabled()) index.synchronize(userId,deadline);
        }
        void activate() { checkDeadline(); store.activate(userId,run,pending); }
    }

    private List<AgentConversationHistoryHit> safeTurns(List<AgentConversationHistoryHit> messages) {
        var groups=new LinkedHashMap<Long,List<AgentConversationHistoryHit>>();
        messages.forEach(m -> groups.computeIfAbsent(m.turnId(),k->new ArrayList<>()).add(m));
        return groups.values().stream().filter(g -> g.size()==2
                && g.stream().anyMatch(m->"USER".equals(m.role()))
                && g.stream().anyMatch(m->"ASSISTANT".equals(m.role()))
                && g.stream().allMatch(m->safety.canUseAsContext(m.content())))
                .flatMap(List::stream).toList();
    }
    private int estimate(String text) { return budget.estimate(List.of(new AiPromptMessage(AiPromptRole.USER,text))); }
    private int estimateMessages(List<AgentConversationHistoryHit> rows) {
        return budget.estimate(rows.stream().map(m->new AiPromptMessage(AiPromptRole.USER,m.content())).toList());
    }
    private int characters(List<AgentConversationHistoryHit> rows) { return rows.stream().mapToInt(m->m.content().length()+100).sum(); }
}
