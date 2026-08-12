package cumt.zongzuo.community.ai.agent.turn;

import java.time.Instant;
import java.util.Map;

public record AgentTurnEvent(String eventId, int schemaVersion, long turnId, String type,
                             Instant occurredAt, Map<String, Object> payload) {
    public AgentTurnEvent {
        payload = Map.copyOf(payload);
    }
}
