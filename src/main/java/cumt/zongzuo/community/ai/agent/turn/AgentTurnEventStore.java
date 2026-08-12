package cumt.zongzuo.community.ai.agent.turn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.data.domain.Range;
import org.springframework.data.domain.Range.Bound;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentTurnEventStore {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_EVENTS = 500;

    private final StringRedisTemplate redis;
    private final AgentTurnMapper mapper;
    private final ObjectMapper objectMapper;

    public AgentTurnEventStore(StringRedisTemplate redis, AgentTurnMapper mapper,
                               ObjectMapper objectMapper) {
        this.redis = redis;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public String append(long turnId, long userId, UUID runId, long runFence, String type,
                         Map<String, Object> payload) {
        AgentTurnRecord turn = mapper.selectById(turnId, userId);
        if (turn == null || !runId.equals(turn.getRunId()) || turn.getRunFence() != runFence
                || (!"RUNNING".equals(turn.getState()) && !terminalType(type))) {
            throw new IllegalStateException("Agent event fence is stale");
        }
        String key = key(turnId);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schemaVersion", "1");
        values.put("turnId", Long.toString(turnId));
        values.put("type", type);
        values.put("occurredAt", Instant.now().toString());
        try {
            values.put("payload", objectMapper.writeValueAsString(payload));
        } catch (Exception error) {
            throw new IllegalArgumentException("Agent event payload cannot be encoded", error);
        }
        RecordId id = redis.opsForStream().add(key, values,
                RedisStreamCommands.XAddOptions.maxlen(MAX_EVENTS).approximateTrimming(true));
        redis.expire(key, TTL);
        if (id == null) {
            throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
        }
        return id.getValue();
    }

    public List<AgentTurnEvent> replay(long turnId, long userId, String after, int limit) {
        if (mapper.selectById(turnId, userId) == null) {
            throw AiApiException.resourceNotFound();
        }
        String key = key(turnId);
        List<MapRecord<String, Object, Object>> first = redis.opsForStream().range(key,
                Range.unbounded(), Limit.limit().count(1));
        if (first == null || first.isEmpty()) {
            return List.of();
        }
        if (after != null && !after.isBlank()
                && compareIds(after, first.getFirst().getId().getValue()) < 0) {
            throw AiApiException.eventStreamExpired();
        }
        Range<String> range = after == null || after.isBlank()
                ? Range.unbounded()
                : Range.rightUnbounded(Bound.exclusive(after));
        List<MapRecord<String, Object, Object>> rows = redis.opsForStream().range(key, range,
                Limit.limit().count(Math.max(1, Math.min(limit, MAX_EVENTS))));
        return rows == null ? List.of() : rows.stream().map(this::event).toList();
    }

    public boolean isTerminal(long turnId, long userId) {
        AgentTurnRecord turn = mapper.selectById(turnId, userId);
        if (turn == null) {
            throw AiApiException.resourceNotFound();
        }
        return "SUCCEEDED".equals(turn.getState()) || "FAILED".equals(turn.getState())
                || "CANCELLED".equals(turn.getState());
    }

    public void trimBefore(long turnId, String keepFrom) {
        List<MapRecord<String, Object, Object>> rows = redis.opsForStream().range(key(turnId),
                Range.unbounded());
        if (rows == null) {
            return;
        }
        rows.stream().filter(row -> compareIds(row.getId().getValue(), keepFrom) < 0)
                .forEach(row -> redis.opsForStream().delete(key(turnId), row.getId()));
    }

    private AgentTurnEvent event(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        try {
            Map<String, Object> payload = objectMapper.readValue(String.valueOf(value.get("payload")),
                    new TypeReference<>() {});
            return new AgentTurnEvent(record.getId().getValue(),
                    Integer.parseInt(String.valueOf(value.get("schemaVersion"))),
                    Long.parseLong(String.valueOf(value.get("turnId"))),
                    String.valueOf(value.get("type")), Instant.parse(String.valueOf(value.get("occurredAt"))),
                    payload);
        } catch (Exception error) {
            throw new IllegalStateException("Agent event stream contains invalid data", error);
        }
    }

    private static boolean terminalType(String type) {
        return "done".equals(type) || "error".equals(type) || "cancelled".equals(type);
    }

    private static String key(long turnId) {
        return "agent:turn:" + turnId + ":events";
    }

    static int compareIds(String left, String right) {
        String[] l = left.split("-", 2);
        String[] r = right.split("-", 2);
        int time = Long.compare(Long.parseLong(l[0]), Long.parseLong(r[0]));
        return time != 0 ? time : Long.compare(Long.parseLong(l[1]), Long.parseLong(r[1]));
    }
}
