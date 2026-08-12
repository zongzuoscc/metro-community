package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.agent.AgentCitation;
import cumt.zongzuo.community.ai.agent.GroundedAgentAnswer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;

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
                    "message:" + history.messageId(), null, ++contextRank, history.content(),
                    json(java.util.Map.of("messageId", history.messageId(),
                            "sourceTurnId", history.turnId(), "role", history.role(),
                            "createdAt", history.createdAt().toString())));
        }
        if (mapper.completeTurn(turnId, userId, runId, runFence) != 1
                || mapper.releaseGuard(userId, runId, runFence) != 1
                || mapper.advanceConversation(turn.getConversationId(), userId, message.getId()) != 1
                || mapper.incrementEpisode(turn.getEpisodeId(), userId) != 1) {
            throw new IllegalStateException("Agent completion fence was lost");
        }
        return true;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Agent personal context metadata cannot be encoded", error);
        }
    }
}
