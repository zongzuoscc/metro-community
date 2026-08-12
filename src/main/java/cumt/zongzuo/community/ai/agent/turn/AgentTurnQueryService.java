package cumt.zongzuo.community.ai.agent.turn;

import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.stereotype.Service;

@Service
public class AgentTurnQueryService {

    private final AgentTurnMapper mapper;

    public AgentTurnQueryService(AgentTurnMapper mapper) {
        this.mapper = mapper;
    }

    public AgentTurnSnapshot snapshot(long turnId, long userId) {
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
}
