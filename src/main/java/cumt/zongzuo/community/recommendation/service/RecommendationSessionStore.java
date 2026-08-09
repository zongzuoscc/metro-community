package cumt.zongzuo.community.recommendation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.dto.RecommendationSession;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RecommendationSessionStore {

    private static final String KEY_PREFIX = "recommendation:session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RecommendationProperties properties;

    public RecommendationSessionStore(StringRedisTemplate redisTemplate,
                                      ObjectMapper objectMapper,
                                      RecommendationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void save(String sessionId, RecommendationSession session) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId,
                    objectMapper.writeValueAsString(session),
                    Duration.ofMinutes(properties.getSessionTtlMinutes()));
        } catch (DataAccessException | JsonProcessingException exception) {
            throw new RecommendationSessionUnavailableException("Recommendation session write failed", exception);
        }
    }

    public RecommendationSession load(String sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
            return json == null ? null : objectMapper.readValue(json, RecommendationSession.class);
        } catch (DataAccessException | JsonProcessingException exception) {
            throw new RecommendationSessionUnavailableException("Recommendation session read failed", exception);
        }
    }
}
