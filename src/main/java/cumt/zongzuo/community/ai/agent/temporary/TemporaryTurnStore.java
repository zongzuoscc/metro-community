package cumt.zongzuo.community.ai.agent.temporary;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 只在 Redis 中保存临时 turn 与有界会话上下文。
 *
 * <p>临时 turn 使用负数 ID，持久 turn 使用 MySQL 正数 ID，从命名空间上防止查询、SSE 和取消路径
 * 误把临时内容当成持久行。所有内容键的 TTL 都不得超过父 session 的绝对截止时间。</p>
 */
@Service
public class TemporaryTurnStore {

    /**
     * 一次性创建 turn 本体、幂等索引、session turn 索引和 USER 历史。
     * 如果拆成多条 Redis 命令，中途断连可能留下“请求索引指向永远 RUNNING”的半成品；
     * Lua 保证所有键要么同时可见，要么全部不可见。
     */
    private static final DefaultRedisScript<String> CREATE_TURN =
            new DefaultRedisScript<>("""
                    local raw = redis.call('GET', KEYS[1])
                    if not raw then return nil end
                    local ok, session = pcall(cjson.decode, raw)
                    if not ok or session['sessionId'] ~= ARGV[1] then return nil end
                    local now = redis.call('TIME')
                    local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
                    local expiresAt = tonumber(ARGV[2])
                    if not expiresAt or expiresAt <= nowMillis then return nil end
                    local existing = redis.call('GET', KEYS[3])
                    if existing then return existing end
                    redis.call('SET', KEYS[2], ARGV[3], 'PXAT', expiresAt)
                    redis.call('SET', KEYS[3], ARGV[4], 'PXAT', expiresAt)
                    redis.call('SADD', KEYS[4], ARGV[4])
                    redis.call('PEXPIREAT', KEYS[4], expiresAt)
                    redis.call('RPUSH', KEYS[5], ARGV[5])
                    redis.call('LTRIM', KEYS[5], -24, -1)
                    redis.call('PEXPIREAT', KEYS[5], expiresAt)
                    return ARGV[4]
                    """, String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TemporarySessionStore sessions;
    private final Clock clock;

    public TemporaryTurnStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                              TemporarySessionStore sessions, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** 在 (userId, sessionId, clientRequestId) 边界内查找幂等重放的 turn。 */
    public TemporaryTurnRecord findByRequest(long userId, UUID sessionId, UUID requestId) {
        String id = redis.opsForValue().get(requestKey(userId, sessionId, requestId));
        return id == null ? null : find(Long.parseLong(id), userId);
    }

    /**
     * 预先分配临时 turn ID。
     *
     * <p>该步骤必须在调用创建 Lua 之前完成，让上层在“Lua 已提交，但客户端没收到返回值”时
     * 仍然知道需要精确终结哪一个 turn。序列出现空洞是可接受的，因为它只用于划分命名空间，
     * 不承担业务顺序或审计连续性。</p>
     */
    public long nextTurnId() {
        Long sequence = redis.opsForValue().increment("agent:temporary:turn-sequence");
        if (sequence == null) throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
        // 持久 turn 使用 MySQL 正数主键；临时 turn 使用负数，从根上防止查询路由混淆。
        return -sequence;
    }

    /**
     * 创建 Redis-only turn，并同时写入幂等索引、session turn 索引与 USER 历史。
     * 所有键使用父 session 的剩余 TTL，不会因新 turn 而滑动续期。
     */
    public TemporaryTurnRecord create(long turnId, long userId, UUID sessionId, UUID requestId,
                                      UUID runId, long fence, String requestHash, String question) {
        TemporarySessionView session = sessions.require(userId, sessionId);
        TemporaryTurnRecord turn = new TemporaryTurnRecord(turnId, userId, sessionId, requestId,
                runId, fence, requestHash, "RUNNING", question, null, null,
                0, clock.instant(), null);
        // remaining() 会在进入 Lua 前先拒绝已过期 session；Lua 内仍使用 Redis TIME 再次封堵边界竞态。
        sessions.remaining(session);
        String actualId = redis.execute(CREATE_TURN, List.of(
                        TemporarySessionStore.key(userId), key(turnId),
                        requestKey(userId, sessionId, requestId),
                        TemporarySessionStore.turnsKey(userId, sessionId),
                        TemporarySessionStore.historyKey(userId, sessionId)),
                sessionId.toString(), Long.toString(session.expiresAt().toEpochMilli()), encode(turn),
                Long.toString(turnId), "USER\t" + question);
        if (actualId == null) throw AiApiException.temporarySessionExpired();
        TemporaryTurnRecord created = find(Long.parseLong(actualId), userId);
        if (created == null) throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
        return created;
    }

    /**
     * 仅向 turn 所有者返回数据，并要求所属 session 仍有效。
     * session 已删除或过期时，即使个别 Redis 子键尚未被清理，也不得再暴露其内容。
     */
    public TemporaryTurnRecord find(long turnId, long userId) {
        String value = redis.opsForValue().get(key(turnId));
        if (value == null) return null;
        TemporaryTurnRecord turn = decode(value);
        if (turn.userId() != userId) return null;
        sessions.require(userId, turn.sessionId());
        return turn;
    }

    /** 返回临时 turn 所属 session 的绝对截止时间，供 SSE 等子键执行精确过期。 */
    public java.time.Instant expiresAt(long turnId, long userId) {
        TemporaryTurnRecord turn = find(turnId, userId);
        if (turn == null) throw AiApiException.resourceNotFound();
        return sessions.require(userId, turn.sessionId()).expiresAt();
    }

    /** 返回 Lua 事件写入所需的父 session 键；只接受已完成所有者校验的 turn。 */
    public String sessionKey(TemporaryTurnRecord turn) {
        return TemporarySessionStore.key(turn.userId());
    }

    /**
     * 仅在 turn 仍为 RUNNING 且 runId/runFence 完全匹配时完成回答。
     * 完成后才把 ASSISTANT 文本追加到临时历史，避免失败或迟到响应污染后续上下文。
     */
    public boolean complete(long turnId, long userId, UUID runId, long fence, String answer,
                            int citationCount) {
        TemporaryTurnRecord turn = find(turnId, userId);
        if (!current(turn, runId, fence)) return false;
        TemporarySessionView session = sessions.require(userId, turn.sessionId());
        TemporaryTurnRecord completed = new TemporaryTurnRecord(turn.turnId(), turn.userId(),
                turn.sessionId(), turn.clientRequestId(), turn.runId(), turn.runFence(),
                turn.requestHash(), "SUCCEEDED", turn.question(), answer, null,
                citationCount, turn.createdAt(), clock.instant());
        Duration ttl = sessions.remaining(session);
        redis.opsForValue().set(key(turnId), encode(completed), ttl);
        appendHistory(userId, turn.sessionId(), "ASSISTANT", answer, ttl);
        return true;
    }

    /** 保存稳定、可对外的失败码，不保留 Provider 原始响应、堆栈或提示词。 */
    public boolean fail(long turnId, long userId, UUID runId, long fence, String errorCode) {
        TemporaryTurnRecord turn = find(turnId, userId);
        if (!current(turn, runId, fence)) return false;
        TemporarySessionView session = sessions.require(userId, turn.sessionId());
        TemporaryTurnRecord failed = new TemporaryTurnRecord(turn.turnId(), turn.userId(),
                turn.sessionId(), turn.clientRequestId(), turn.runId(), turn.runFence(),
                turn.requestHash(), "FAILED", turn.question(), null, errorCode,
                0, turn.createdAt(), clock.instant());
        redis.opsForValue().set(key(turnId), encode(failed), sessions.remaining(session));
        return true;
    }

    /** 使用 worker 的同一 run fence 取消 RUNNING turn，旧 worker 之后的完成写会被 current 校验拒绝。 */
    public boolean cancel(long turnId, long userId, UUID runId, long fence) {
        TemporaryTurnRecord turn = find(turnId, userId);
        if (!current(turn, runId, fence)) return false;
        TemporarySessionView session = sessions.require(userId, turn.sessionId());
        TemporaryTurnRecord cancelled = new TemporaryTurnRecord(turn.turnId(), turn.userId(),
                turn.sessionId(), turn.clientRequestId(), turn.runId(), turn.runFence(),
                turn.requestHash(), "CANCELLED", turn.question(), null, null, 0,
                turn.createdAt(), clock.instant());
        redis.opsForValue().set(key(turnId), encode(cancelled), sessions.remaining(session));
        return true;
    }

    /**
     * 只返回当前临时 session 最近的有界 Redis 历史。
     * 创建 turn 时已写入当前 USER 问题，因此这里会去掉末尾重复项，避免模型同时看到两份当前问题。
     */
    public List<String> previousContext(long userId, UUID sessionId, String currentQuestion) {
        sessions.require(userId, sessionId);
        List<String> values = redis.opsForList().range(
                TemporarySessionStore.historyKey(userId, sessionId), -12, -1);
        if (values == null) return List.of();
        List<String> context = new ArrayList<>(values);
        if (!context.isEmpty() && context.getLast().equals("USER\t" + currentQuestion)) {
            context.removeLast();
        }
        return List.copyOf(context);
    }

    /**
     * 根据 session 索引删除 turn、SSE 和幂等请求键。
     * 生产代码不使用 Redis KEYS 全库扫描，避免会话删除阻塞 Redis 主线程。
     */
    public void deleteSessionTurns(long userId, UUID sessionId) {
        var ids = redis.opsForSet().members(TemporarySessionStore.turnsKey(userId, sessionId));
        if (ids != null) ids.forEach(id -> {
            String turnJson = redis.opsForValue().get(key(Long.parseLong(id)));
            if (turnJson != null) {
                TemporaryTurnRecord turn = decode(turnJson);
                redis.delete(requestKey(userId, sessionId, turn.clientRequestId()));
            }
            redis.delete(List.of(key(Long.parseLong(id)), eventKey(Long.parseLong(id))));
        });
    }

    private void appendHistory(long userId, UUID sessionId, String role, String content, Duration ttl) {
        // 只保留最近 24 条角色消息，防止长会话无界占用 Redis 和模型上下文。
        String key = TemporarySessionStore.historyKey(userId, sessionId);
        redis.opsForList().rightPush(key, role + "\t" + content);
        redis.opsForList().trim(key, -24, -1);
        redis.expire(key, ttl);
    }

    private static boolean current(TemporaryTurnRecord turn, UUID runId, long fence) {
        // 状态、runId 与 fence 必须同时匹配，迟到 worker 才无法覆盖新一轮任务。
        return turn != null && "RUNNING".equals(turn.state()) && runId.equals(turn.runId())
                && fence == turn.runFence();
    }

    private String encode(TemporaryTurnRecord value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Temporary turn cannot be encoded", error); }
    }

    private TemporaryTurnRecord decode(String value) {
        try { return objectMapper.readValue(value, TemporaryTurnRecord.class); }
        catch (Exception error) { throw new IllegalStateException("Temporary turn is corrupt", error); }
    }

    private static String key(long turnId) { return "agent:temporary:turn:" + turnId; }
    private static String eventKey(long turnId) { return "agent:turn:" + turnId + ":events"; }
    private static String requestKey(long userId, UUID sessionId, UUID requestId) {
        return "agent:temporary:request:" + userId + ":" + sessionId + ":" + requestId;
    }
}
