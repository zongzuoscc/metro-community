package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@Service
public class RecommendationFeedRateLimiter {

    private static final String KEY_PREFIX = "recommendation:feed:request:";
    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('incr', KEYS[1])
            if count == 1 or redis.call('ttl', KEYS[1]) < 0 then
                redis.call('expire', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RecommendationProperties properties;

    public RecommendationFeedRateLimiter(StringRedisTemplate redisTemplate,
                                         RecommendationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void checkRequest(Long userId) {
        int limit = properties.getFeedRequestLimit();
        if (limit <= 0) {
            return;
        }
        try {
            Long count = redisTemplate.execute(INCREMENT_WITH_TTL, List.of(KEY_PREFIX + userId),
                    Integer.toString(Math.max(1, properties.getFeedRateWindowSeconds())));
            if (count != null && count > limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "推荐刷新过于频繁，请稍后再试");
            }
        } catch (DataAccessException redisFailure) {
            log.warn("Recommendation feed rate limiter unavailable for user {}; allowing request ({})", userId,
                    redisFailure.getClass().getSimpleName());
        }
    }
}
