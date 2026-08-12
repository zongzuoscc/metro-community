package cumt.zongzuo.community.ai.agent.turn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnRecord;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnStore;
import org.springframework.data.domain.Range;
import org.springframework.data.domain.Range.Bound;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 在 Redis Stream 中保存有上限的 SSE 事件，同时对持久和临时 turn 执行所有者与 run fence 校验。
 *
 * <p>事件流只用于短期进度恢复，不是业务事实源；当 Redis 事件过期时，持久 turn 仍以 MySQL 为准，
 * 临时 turn 则以它所属 session 的绝对过期时间为准。</p>
 */
@Service
public class AgentTurnEventStore {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_EVENTS = 500;
    /**
     * 临时事件不能采用“先校验、再 XADD”的多命令写法。用户可能在两条命令之间删除 session，
     * 而迟到 worker 会重新创建包含最终回答的 Stream。该脚本把 session 存在性、sessionId 归属、
     * 绝对截止时间、XADD 和 PEXPIREAT 收敛为一个 Redis 原子操作。
     */
    private static final DefaultRedisScript<String> APPEND_TEMPORARY =
            new DefaultRedisScript<>("""
                    local raw = redis.call('GET', KEYS[1])
                    if not raw then return nil end
                    local ok, session = pcall(cjson.decode, raw)
                    if not ok or session['sessionId'] ~= ARGV[1] then return nil end
                    local now = redis.call('TIME')
                    local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
                    local expiresAt = tonumber(ARGV[2])
                    if not expiresAt or expiresAt <= nowMillis then return nil end
                    local id = redis.call('XADD', KEYS[2], 'MAXLEN', '~', ARGV[3], '*',
                        'schemaVersion', ARGV[4], 'turnId', ARGV[5], 'type', ARGV[6],
                        'occurredAt', ARGV[7], 'payload', ARGV[8])
                    redis.call('PEXPIREAT', KEYS[2], expiresAt)
                    return id
                    """, String.class);

    private final StringRedisTemplate redis;
    private final AgentTurnMapper mapper;
    private final TemporaryTurnStore temporaryTurns;
    private final ObjectMapper objectMapper;

    public AgentTurnEventStore(StringRedisTemplate redis, AgentTurnMapper mapper,
                               TemporaryTurnStore temporaryTurns,
                               ObjectMapper objectMapper) {
        this.redis = redis;
        this.mapper = mapper;
        this.temporaryTurns = temporaryTurns;
        this.objectMapper = objectMapper;
    }

    /**
     * 追加一条经 runId/runFence 验证的 SSE 事件。
     * 持久 turn 使用 30 分钟进度缓存；临时 turn 使用父 session 绝对截止时间，且通过 Lua
     * 防止删除后的迟到 worker 重建包含回答的 Stream。
     */
    public String append(long turnId, long userId, UUID runId, long runFence, String type,
                         Map<String, Object> payload) {
        if (!eventFenceCurrent(turnId, userId, runId, runFence, type)) {
            throw new IllegalStateException("Agent event fence is stale");
        }
        TemporaryTurnRecord temporaryTurn = turnId < 0 ? temporaryTurns.find(turnId, userId) : null;
        java.time.Instant temporaryExpiry = temporaryTurn == null ? null
                : temporaryTurns.expiresAt(turnId, userId);
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
        if (temporaryExpiry != null) {
            String id = redis.execute(APPEND_TEMPORARY,
                    List.of(temporaryTurns.sessionKey(temporaryTurn), key),
                    temporaryTurn.sessionId().toString(), Long.toString(temporaryExpiry.toEpochMilli()),
                    Integer.toString(MAX_EVENTS), values.get("schemaVersion"), values.get("turnId"),
                    values.get("type"), values.get("occurredAt"), values.get("payload"));
            if (id == null) throw AiApiException.temporarySessionExpired();
            return id;
        }
        RecordId id = redis.opsForStream().add(key, values,
                RedisStreamCommands.XAddOptions.maxlen(MAX_EVENTS).approximateTrimming(true));
        if (id == null) {
            throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
        }
        // 持久 turn 的 Stream 是可恢复进度缓存，因此仍使用独立的 30 分钟滑动期限。
        if (temporaryExpiry == null) {
            redis.expire(key, TTL);
        }
        return id.getValue();
    }

    /** 从指定 Stream ID 之后重放有界事件，先校验 turn 属于当前用户。 */
    public List<AgentTurnEvent> replay(long turnId, long userId, String after, int limit) {
        if (!exists(turnId, userId)) {
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

    /** 统一判定持久或临时 turn 是否已进入不可逆终态。 */
    public boolean isTerminal(long turnId, long userId) {
        String state = state(turnId, userId);
        return "SUCCEEDED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    /** 删除 keepFrom 之前的旧 SSE 事件，用于有界 Stream 恢复测试和运维修复。 */
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

    /**
     * 根据 turn ID 命名空间选择所有权校验来源。
     * 正 ID 查询 MySQL 持久 turn，负 ID 查询 Redis 临时 turn，因此临时事件不需要为了 SSE 而落 MySQL 内容行。
     */
    private boolean eventFenceCurrent(long turnId, long userId, UUID runId, long runFence,
                                      String type) {
        if (turnId < 0) {
            TemporaryTurnRecord turn = temporaryTurns.find(turnId, userId);
            return turn != null && runId.equals(turn.runId()) && turn.runFence() == runFence
                    && ("RUNNING".equals(turn.state()) || terminalType(type));
        }
        AgentTurnRecord turn = mapper.selectById(turnId, userId);
        return turn != null && runId.equals(turn.getRunId()) && turn.getRunFence() == runFence
                && ("RUNNING".equals(turn.getState()) || terminalType(type));
    }

    private boolean exists(long turnId, long userId) {
        return turnId < 0 ? temporaryTurns.find(turnId, userId) != null
                : mapper.selectById(turnId, userId) != null;
    }

    private String state(long turnId, long userId) {
        if (turnId < 0) {
            TemporaryTurnRecord turn = temporaryTurns.find(turnId, userId);
            if (turn == null) throw AiApiException.resourceNotFound();
            return turn.state();
        }
        AgentTurnRecord turn = mapper.selectById(turnId, userId);
        if (turn == null) throw AiApiException.resourceNotFound();
        return turn.getState();
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
