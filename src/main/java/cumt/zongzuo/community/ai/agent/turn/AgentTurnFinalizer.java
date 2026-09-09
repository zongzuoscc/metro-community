package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.agent.AgentCitation;
import cumt.zongzuo.community.ai.agent.websearch.AgentWebSourceUrlPolicy;
import cumt.zongzuo.community.ai.agent.GroundedAgentAnswer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 在单个 MySQL 事务中提交持久 Agent 的回答、引用、个人上下文使用记录与终态。
 *
 * <p>长期记忆由回答前的 LLM 压缩阶段提取并完成持久向量同步；本事务只记录本轮实际使用
 * 的记忆，不再从回答后的用户原话做规则捕获，也不在数据库事务中触发模型或向量调用。</p>
 */
@Service
public class AgentTurnFinalizer {

    private final AgentTurnMapper mapper;
    private final AgentRunLeaseStore leases;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public AgentTurnFinalizer(AgentTurnMapper mapper, AgentRunLeaseStore leases,
                              PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    public boolean complete(long turnId, UUID runId, long runFence, GroundedAgentAnswer answer) {
        Long userId = mapper.selectOwner(turnId);
        if (userId == null) {
            return false;
        }
        Boolean completed = transactions.execute(status -> completeInTransaction(
                userId, turnId, runId, runFence, answer));
        if (Boolean.TRUE.equals(completed)) {
            leases.release(userId, runId, runFence);
        }
        return Boolean.TRUE.equals(completed);
    }

    private boolean completeInTransaction(long userId, long turnId, UUID runId, long runFence,
                                          GroundedAgentAnswer answer) {
        AgentRunGuardRecord guard = mapper.selectGuardForUpdate(userId);
        AgentTurnRecord turn = mapper.selectByIdForUpdate(turnId, userId);
        if (guard == null || turn == null || !runId.equals(guard.getActiveRunId())
                || guard.getRunFence() != runFence || !runId.equals(turn.getRunId())
                || turn.getRunFence() != runFence || !"RUNNING".equals(turn.getState())) {
            return false;
        }
        AgentTurnMapper.AgentMessageInsert message = new AgentTurnMapper.AgentMessageInsert();
        mapper.insertAssistantMessage(message, userId, turnId, turn.getConversationId(),
                turn.getEpisodeId(), answer.answer(), AgentTurnAdmissionService.sha256(answer.answer()));
        int ordinal = 0;
        for (AgentCitation citation : answer.citations()) {
            mapper.insertCitation(userId, message.getId(), ++ordinal, citation.articleId(),
                    citation.revisionId(), citation.chunkId(), citation.title(), citation.quote(),
                    AgentTurnAdmissionService.sha256(citation.quote()));
        }
        int contextRank = 0;
        for (var memory : answer.memoryUses()) {
            mapper.insertPersonalContextUse(userId, turnId, "MEMORY",
                    "memory:" + memory.memoryId() + ":v" + memory.version(), memory.memoryId(),
                    ++contextRank, memory.content(), json(java.util.Map.of(
                            "version", memory.version(), "category", memory.category())));
        }
        for (var history : answer.historyUses()) {
            mapper.insertPersonalContextUse(userId, turnId, "CONVERSATION_HISTORY",
                    "message:" + history.messageId(), null, ++contextRank, contextExcerpt(history.content()),
                    json(java.util.Map.of("messageId", history.messageId(),
                            "sourceTurnId", history.turnId(), "role", history.role(),
                            "createdAt", history.createdAt().toString(),
                            "contentHash", AgentTurnAdmissionService.sha256(history.content()),
                            "contentCodePoints", history.content().codePointCount(0, history.content().length()))));
        }
        for (var source : answer.webSources()) {
            if (!AgentWebSourceUrlPolicy.isSafe(source.url())) {
                // 来源必须在回答事务提交前再次校验，禁止其它调用路径绕过搜索网关的协议边界。
                throw new IllegalStateException("Agent web source URL is unsafe");
            }
            mapper.insertWebSourceUse(userId, turnId, "web:" + source.index() + ":"
                            + AgentTurnAdmissionService.sha256(source.url()), ++contextRank,
                    source.title(), json(java.util.Map.of("index", source.index(),
                            "url", source.url(), "siteName", source.siteName())));
        }
        if (mapper.completeTurn(turnId, userId, runId, runFence) != 1
                || mapper.releaseGuard(userId, runId, runFence) != 1
                || mapper.advanceConversation(turn.getConversationId(), userId, message.getId()) != 1
                || mapper.incrementEpisode(turn.getEpisodeId(), userId) != 1) {
            throw new IllegalStateException("Agent completion fence was lost");
        }
        return true;
    }

    /**
     * 使用记录只保存最多 1000 个 Unicode 码点的预览，匹配 VARCHAR(1000) 字段。
     * 原文仍在 agent_message，通过消息 ID 和完整内容哈希追溯；不能为了审计重复长文而
     * 让已生成的回答因 Data too long 回滚，也不能按 UTF-16 下标截断半个 emoji。
     */
    private static String contextExcerpt(String content) {
        int end = content.offsetByCodePoints(0, Math.min(1000, content.codePointCount(0, content.length())));
        return content.substring(0, end);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Agent personal context metadata cannot be encoded", error);
        }
    }
}
