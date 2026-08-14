package cumt.zongzuo.community.ai.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.provider.AiPromptRole;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import cumt.zongzuo.community.ai.userprovider.UserAiChatRouter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 最多两轮、最多四次工具调用的只读 Planner v1。
 *
 * <p>模型只负责在白名单中选择信息源。后端仍掌握最终权限：第一轮固定先查站内文章；
 * 用户开启联网后固定执行联网搜索；临时对话永远移除长期记忆和持久历史。模型输出无法
 * 改变这些规则，也无法构造任意工具参数。</p>
 */
public final class BoundedReadOnlyAgentPlanner implements AgentReadOnlyPlanProvider {

    private static final String SYSTEM = """
            You are a read-only retrieval planner. Select only from these exact tools:
            COMMUNITY_ARTICLES, LONG_TERM_MEMORY, CONVERSATION_HISTORY, WEB_SEARCH.
            Never propose write operations, URLs, SQL, code, settings changes, or arbitrary tools.
            Return exactly one JSON object:
            {"tools":["TOOL_NAME"],"reviewAfterExecution":false}
            Use reviewAfterExecution only when one later planning pass may be needed after seeing
            evidence counts. Do not repeat tools listed in alreadyCalledTools.
            """;

    private final AiCapabilityExecutor executor;
    private final UserAiChatRouter router;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration timeout;
    private final int maxRounds;
    private final int maxToolCalls;

    public BoundedReadOnlyAgentPlanner(AiCapabilityExecutor executor, UserAiChatRouter router,
                                       ObjectMapper objectMapper, Clock clock, Duration timeout,
                                       int maxRounds, int maxToolCalls) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || maxRounds < 1 || maxRounds > 2 || maxToolCalls < 2 || maxToolCalls > 4) {
            throw new IllegalArgumentException("Read-only planner limits are invalid");
        }
        this.executor = executor;
        this.router = router;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.timeout = timeout;
        this.maxRounds = maxRounds;
        this.maxToolCalls = maxToolCalls;
    }

    @Override
    public AgentPlannerRound plan(long userId, String requestId, String question,
                                  boolean persistentContextAllowed, boolean webSearchEnabled,
                                  int round, Set<AgentReadOnlyTool> alreadyCalled,
                                  AgentPlannerEvidence evidence, Instant deadline) {
        if (round < 1 || round > maxRounds || alreadyCalled.size() > maxToolCalls) {
            throw new IllegalArgumentException("Planner round or tool budget is invalid");
        }
        int remaining = maxToolCalls - alreadyCalled.size();
        if (remaining == 0) return new AgentPlannerRound(List.of(), false);

        try {
            ObjectNode input = input(question, persistentContextAllowed, webSearchEnabled,
                    round, alreadyCalled, evidence, remaining);
            List<AiPromptMessage> messages = List.of(
                    new AiPromptMessage(AiPromptRole.SYSTEM, SYSTEM),
                    new AiPromptMessage(AiPromptRole.USER, objectMapper.writeValueAsString(input)));
            Instant planningDeadline = min(deadline, clock.instant().plus(timeout));
            var routed = executor.execute(new AiInvocationContext(AiCapability.AGENT, userId,
                            requestId + ":planner:" + round, messages.stream()
                            .mapToInt(message -> message.text().length()).sum(),
                            planningDeadline, false),
                    () -> router.generate(userId, new AiChatCommand(AiCapability.AGENT, messages,
                            AiResponseMode.JSON_OBJECT)));
            return secure(parse(routed.result().text()), persistentContextAllowed,
                    webSearchEnabled, round, alreadyCalled, remaining);
        } catch (RuntimeException error) {
            // 规划失败不能让整轮问答不可用。降级计划仍只包含相同白名单，并继续遵守
            // 临时会话隔离、联网开关、去重和剩余预算。
            return fallback(persistentContextAllowed, webSearchEnabled, alreadyCalled, remaining);
        } catch (Exception error) {
            // JSON 序列化等受检异常同样只触发只读降级，不向调用方暴露规划器内部细节。
            return fallback(persistentContextAllowed, webSearchEnabled, alreadyCalled, remaining);
        }
    }

    private ObjectNode input(String question, boolean persistentContextAllowed,
                             boolean webSearchEnabled, int round,
                             Set<AgentReadOnlyTool> alreadyCalled,
                             AgentPlannerEvidence evidence, int remaining) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("question", question);
        input.put("round", round);
        input.put("persistentContextAllowed", persistentContextAllowed);
        input.put("webSearchEnabled", webSearchEnabled);
        input.put("remainingToolCalls", remaining);
        ArrayNode called = input.putArray("alreadyCalledTools");
        alreadyCalled.stream().sorted().map(Enum::name).forEach(called::add);
        ObjectNode counts = input.putObject("evidenceCounts");
        counts.put("communitySources", evidence.communitySources());
        counts.put("memories", evidence.memories());
        counts.put("historyMessages", evidence.historyMessages());
        counts.put("episodeSummaries", evidence.episodeSummaries());
        counts.put("webSources", evidence.webSources());
        return input;
    }

    private RawDecision parse(String value) throws Exception {
        JsonNode root = objectMapper.readTree(value);
        if (root == null || !root.isObject() || root.size() != 2
                || !(root.get("tools") instanceof ArrayNode tools)
                || !root.path("reviewAfterExecution").isBoolean()) {
            throw new IllegalArgumentException("Planner response shape is invalid");
        }
        LinkedHashSet<AgentReadOnlyTool> selected = new LinkedHashSet<>();
        for (JsonNode item : tools) {
            if (!item.isTextual()) throw new IllegalArgumentException("Planner tool is invalid");
            selected.add(AgentReadOnlyTool.valueOf(item.textValue()));
        }
        return new RawDecision(List.copyOf(selected), root.path("reviewAfterExecution").booleanValue());
    }

    private AgentPlannerRound secure(RawDecision raw, boolean persistentContextAllowed,
                                     boolean webSearchEnabled, int round,
                                     Set<AgentReadOnlyTool> alreadyCalled, int remaining) {
        EnumSet<AgentReadOnlyTool> selected = raw.tools().isEmpty()
                ? EnumSet.noneOf(AgentReadOnlyTool.class) : EnumSet.copyOf(raw.tools());
        if (!persistentContextAllowed) {
            selected.remove(AgentReadOnlyTool.LONG_TERM_MEMORY);
            selected.remove(AgentReadOnlyTool.CONVERSATION_HISTORY);
        }
        selected.removeAll(alreadyCalled);
        if (round == 1 && !alreadyCalled.contains(AgentReadOnlyTool.COMMUNITY_ARTICLES)) {
            selected.add(AgentReadOnlyTool.COMMUNITY_ARTICLES);
        }
        if (round == 1 && webSearchEnabled
                && !alreadyCalled.contains(AgentReadOnlyTool.WEB_SEARCH)) {
            selected.add(AgentReadOnlyTool.WEB_SEARCH);
        } else if (!webSearchEnabled) {
            selected.remove(AgentReadOnlyTool.WEB_SEARCH);
        }
        boolean mandatoryWeb = round == 1 && webSearchEnabled
                && !alreadyCalled.contains(AgentReadOnlyTool.WEB_SEARCH);
        return new AgentPlannerRound(ordered(selected, remaining, mandatoryWeb),
                raw.reviewAfterExecution());
    }

    private AgentPlannerRound fallback(boolean persistentContextAllowed, boolean webSearchEnabled,
                                       Set<AgentReadOnlyTool> alreadyCalled, int remaining) {
        EnumSet<AgentReadOnlyTool> safe = EnumSet.of(AgentReadOnlyTool.COMMUNITY_ARTICLES);
        if (persistentContextAllowed) {
            safe.add(AgentReadOnlyTool.LONG_TERM_MEMORY);
            safe.add(AgentReadOnlyTool.CONVERSATION_HISTORY);
        }
        if (webSearchEnabled) safe.add(AgentReadOnlyTool.WEB_SEARCH);
        safe.removeAll(alreadyCalled);
        boolean mandatoryWeb = webSearchEnabled
                && !alreadyCalled.contains(AgentReadOnlyTool.WEB_SEARCH);
        return new AgentPlannerRound(ordered(safe, remaining, mandatoryWeb), false);
    }

    private static List<AgentReadOnlyTool> ordered(Set<AgentReadOnlyTool> tools, int limit,
                                                   boolean mandatoryWeb) {
        List<AgentReadOnlyTool> ordered = new ArrayList<>();
        if (tools.contains(AgentReadOnlyTool.COMMUNITY_ARTICLES) && ordered.size() < limit) {
            ordered.add(AgentReadOnlyTool.COMMUNITY_ARTICLES);
        }
        // 联网在展示顺序上仍放最后，但开启后必须预留一个预算槽位，不能被可选的记忆
        // 或历史挤掉。预算为 2 时因此稳定得到“站内文章 + 联网搜索”。
        int reservedForWeb = mandatoryWeb && tools.contains(AgentReadOnlyTool.WEB_SEARCH) ? 1 : 0;
        for (AgentReadOnlyTool tool : List.of(AgentReadOnlyTool.LONG_TERM_MEMORY,
                AgentReadOnlyTool.CONVERSATION_HISTORY)) {
            if (tools.contains(tool) && ordered.size() < limit - reservedForWeb) {
                ordered.add(tool);
            }
        }
        if (tools.contains(AgentReadOnlyTool.WEB_SEARCH) && ordered.size() < limit) {
            ordered.add(AgentReadOnlyTool.WEB_SEARCH);
        }
        return List.copyOf(ordered);
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    @Override
    public int maxRounds() {
        return maxRounds;
    }

    @Override
    public int maxToolCalls() {
        return maxToolCalls;
    }

    private record RawDecision(List<AgentReadOnlyTool> tools, boolean reviewAfterExecution) {
    }
}
