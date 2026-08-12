package cumt.zongzuo.community.ai.agent.turn;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class AgentRunLeaseStore {

    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final DefaultRedisScript<Long> CLAIM = script("""
            local current = redis.call('GET', KEYS[1])
            local proposedFence = tonumber(ARGV[2])
            if not current then
              redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[1] .. ':' .. ARGV[2])
              return 1
            end
            local delimiter = string.find(current, ':', 1, true)
            local currentRun = string.sub(current, 1, delimiter - 1)
            local currentFence = tonumber(string.sub(current, delimiter + 1))
            if currentRun == ARGV[1] and currentFence == proposedFence then
              redis.call('PEXPIRE', KEYS[1], ARGV[3])
              return 1
            end
            if currentFence < proposedFence then
              redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[1] .. ':' .. ARGV[2])
              return 1
            end
            return 0
            """);
    private static final DefaultRedisScript<Long> RENEW = script("""
            if redis.call('GET', KEYS[1]) == ARGV[1] .. ':' .. ARGV[2] then
              redis.call('PEXPIRE', KEYS[1], ARGV[3])
              return 1
            end
            return 0
            """);
    private static final DefaultRedisScript<Long> RELEASE = script("""
            if redis.call('GET', KEYS[1]) == ARGV[1] .. ':' .. ARGV[2] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """);

    private final StringRedisTemplate redis;

    public AgentRunLeaseStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean claim(long userId, UUID runId, long fence) {
        return execute(CLAIM, userId, runId, fence, Long.toString(LEASE.toMillis()));
    }

    public boolean renew(long userId, UUID runId, long fence) {
        return execute(RENEW, userId, runId, fence, Long.toString(LEASE.toMillis()));
    }

    public boolean release(long userId, UUID runId, long fence) {
        return execute(RELEASE, userId, runId, fence, "0");
    }

    private boolean execute(DefaultRedisScript<Long> script, long userId, UUID runId,
                            long fence, String ttl) {
        Long result = redis.execute(script, List.of(key(userId)), runId.toString(),
                Long.toString(fence), ttl);
        return Long.valueOf(1).equals(result);
    }

    private static String key(long userId) {
        return "agent:run:user:" + userId;
    }

    private static DefaultRedisScript<Long> script(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }
}
