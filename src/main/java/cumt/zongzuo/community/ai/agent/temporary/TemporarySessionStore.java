package cumt.zongzuo.community.ai.agent.temporary;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 在 Redis 中保存每个用户唯一的临时 session，不向 MySQL 写入对话内容。
 *
 * <p>session 本身、历史列表和 turn 索引的 TTL 都从同一 expiresAt 计算，防止任一子键通过活跃读写
 * 变成滑动过期，破坏“24 小时后不可恢复”的隐私承诺。</p>
 */
@Service
public class TemporarySessionStore {

    static final Duration TTL = Duration.ofHours(24);
    /** 只有 sessionId 仍匹配时才删除父键，避免并发创建的新 session 被旧删除请求误删。 */
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 0 end
            local ok, session = pcall(cjson.decode, raw)
            if not ok or session['sessionId'] ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TemporarySessionStore(StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等创建 24 小时临时 session。
     * 如果并发请求已经创建成功，setIfAbsent 失败的请求直接返回已有对象，不重置 TTL。
     */
    public TemporarySessionView create(long userId) {
        TemporarySessionView existing = current(userId);
        if (existing != null) return existing;
        Instant created = clock.instant();
        TemporarySessionView proposed = new TemporarySessionView(UUID.randomUUID(), created,
                created.plus(TTL));
        String encoded = encode(proposed);
        Boolean createdNew = redis.opsForValue().setIfAbsent(key(userId), encoded, TTL);
        return Boolean.TRUE.equals(createdNew) ? proposed : requiredCurrent(userId);
    }

    /** 返回 Redis 中仍有效的当前 session，读操作不续期。 */
    public TemporarySessionView current(long userId) {
        String value = redis.opsForValue().get(key(userId));
        return value == null ? null : decode(value);
    }

    /**
     * 同时校验用户所有权、sessionId 和绝对过期边界。
     * 不匹配时统一返回 TEMPORARY_SESSION_EXPIRED，避免向调用方泄漏其他用户的 session 是否存在。
     */
    public TemporarySessionView require(long userId, UUID sessionId) {
        TemporarySessionView current = current(userId);
        if (current == null || !current.sessionId().equals(sessionId)
                || !current.expiresAt().isAfter(clock.instant())) {
            throw AiApiException.temporarySessionExpired();
        }
        return current;
    }

    /**
     * 先删除父 session 键，让所有迟到 worker 的 Lua 写入立即失败。
     * 此时暂不删除 turn 索引，上层还需要用它枚举并清理子键。
     */
    public boolean invalidate(long userId, UUID sessionId) {
        Long deleted = redis.execute(INVALIDATE, java.util.List.of(key(userId)),
                sessionId.toString());
        return deleted != null && deleted == 1L;
    }

    /** 父 session 已失效后，删除该 session 专属的历史与 turn 索引。 */
    public void deleteChildren(long userId, UUID sessionId) {
        redis.delete(java.util.List.of(historyKey(userId, sessionId), turnsKey(userId, sessionId)));
    }

    /**
     * 从 session 截止时间重新计算子键 TTL，而不是每次固定写 24 小时。
     * 这个细节保证新消息不会无意中延长整个临时会话的生命周期。
     */
    Duration remaining(TemporarySessionView session) {
        Duration remaining = Duration.between(clock.instant(), session.expiresAt());
        if (remaining.isZero() || remaining.isNegative()) throw AiApiException.temporarySessionExpired();
        return remaining;
    }

    static String historyKey(long userId, UUID sessionId) {
        return "agent:temporary:history:" + userId + ":" + sessionId;
    }

    static String turnsKey(long userId, UUID sessionId) {
        return "agent:temporary:turns:" + userId + ":" + sessionId;
    }

    private TemporarySessionView requiredCurrent(long userId) {
        TemporarySessionView value = current(userId);
        if (value == null) throw AiApiException.runtimeUnavailable(Duration.ofSeconds(1));
        return value;
    }

    private String encode(TemporarySessionView value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Temporary session cannot be encoded", error); }
    }

    private TemporarySessionView decode(String value) {
        try { return objectMapper.readValue(value, TemporarySessionView.class); }
        catch (Exception error) { throw new IllegalStateException("Temporary session is corrupt", error); }
    }

    /** 统一生成 session 键，供需要在 Lua 中校验父会话存在性的同包组件复用。 */
    static String key(long userId) { return "agent:temporary:session:" + userId; }
}
