package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RecommendationProfileService {

    private static final double HALF_LIFE_DAYS = 14D;
    private static final int LOCK_TIMEOUT_SECONDS = 5;
    private static final DefaultRedisScript<Long> REPLACE_PROFILE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('exists', KEYS[1]) == 1 then
                redis.call('rename', KEYS[1], KEYS[2])
            else
                redis.call('del', KEYS[2])
            end
            if redis.call('exists', KEYS[3]) == 1 then
                redis.call('rename', KEYS[3], KEYS[4])
            else
                redis.call('del', KEYS[4])
            end
            return 1
            """, Long.class);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final RecommendationProperties properties;

    public RecommendationProfileService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate,
                                        RecommendationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rebuildProfile(Long userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        String lockName = "recommendation:profile:" + userId;
        boolean acquired = false;
        try {
            Integer result = jdbcTemplate.queryForObject(
                    "SELECT GET_LOCK(?, ?)", Integer.class, lockName, LOCK_TIMEOUT_SECONDS);
            if (!Integer.valueOf(1).equals(result)) {
                throw new IllegalStateException("Timed out acquiring recommendation profile lock for user " + userId);
            }
            acquired = true;
            rebuildWhileLocked(userId);
        } finally {
            if (acquired) {
                Integer released = jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
                if (!Integer.valueOf(1).equals(released)) {
                    throw new IllegalStateException("Failed to release recommendation profile lock for user " + userId);
                }
            }
        }
    }

    public Map<String, Double> profileTags(Long userId, int limit) {
        return readProfile(tagKey(userId), limit, value -> value);
    }

    public Map<Long, Double> profileAuthors(Long userId, int limit) {
        return readProfile(authorKey(userId), limit, Long::valueOf);
    }

    private void rebuildWhileLocked(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusDays(properties.getProfileWindowDays());
        List<ProfileRow> rows = jdbcTemplate.query("""
                SELECT e.id AS event_id, e.event_type, e.occurred_at, e.target_author_id,
                       a.author_id AS article_author_id, t.name AS tag_name
                FROM user_article_event e
                LEFT JOIN article a ON a.id = e.article_id
                LEFT JOIN article_tag article_tags ON article_tags.article_id = e.article_id
                LEFT JOIN tag t ON t.id = article_tags.tag_id
                WHERE e.user_id = ? AND e.occurred_at >= ?
                ORDER BY e.occurred_at ASC, e.id ASC, article_tags.id ASC
                """, (resultSet, rowNumber) -> new ProfileRow(
                resultSet.getLong("event_id"),
                RecommendationEventType.valueOf(resultSet.getString("event_type")),
                resultSet.getObject("occurred_at", LocalDateTime.class),
                nullableLong(resultSet, "target_author_id"),
                nullableLong(resultSet, "article_author_id"),
                resultSet.getString("tag_name")), userId, cutoff);

        String suffix = ":rebuild:" + UUID.randomUUID();
        String temporaryTagKey = tagKey(userId) + suffix;
        String temporaryAuthorKey = authorKey(userId) + suffix;
        try {
            replay(rows, now, temporaryTagKey, temporaryAuthorKey);
            setTtlIfPresent(temporaryTagKey);
            setTtlIfPresent(temporaryAuthorKey);
            redisTemplate.execute(REPLACE_PROFILE_SCRIPT,
                    List.of(temporaryTagKey, tagKey(userId), temporaryAuthorKey, authorKey(userId)));
        } finally {
            redisTemplate.delete(List.of(temporaryTagKey, temporaryAuthorKey));
        }
    }

    private void replay(List<ProfileRow> rows, LocalDateTime now, String tagKey, String authorKey) {
        Long currentEventId = null;
        for (ProfileRow row : rows) {
            double delta = row.eventType().weight() * decay(row.occurredAt(), now);
            if (!row.eventId().equals(currentEventId)) {
                Long authorId = row.eventType() == RecommendationEventType.FOLLOW_AUTHOR
                        ? row.targetAuthorId() : row.articleAuthorId();
                if (authorId != null) {
                    redisTemplate.opsForZSet().incrementScore(authorKey, authorId.toString(), delta);
                }
                currentEventId = row.eventId();
            }
            if (row.eventType() != RecommendationEventType.FOLLOW_AUTHOR && row.tagName() != null) {
                redisTemplate.opsForZSet().incrementScore(tagKey, row.tagName(), delta);
            }
        }
    }

    private double decay(LocalDateTime occurredAt, LocalDateTime now) {
        long daysBetween = Math.max(0L,
                ChronoUnit.DAYS.between(occurredAt.toLocalDate(), now.toLocalDate()));
        return Math.exp(-Math.log(2D) * daysBetween / HALF_LIFE_DAYS);
    }

    private void setTtlIfPresent(String key) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.expire(key, properties.getProfileTtlDays(), TimeUnit.DAYS);
        }
    }

    private <T> Map<T, Double> readProfile(String key, int limit,
                                           java.util.function.Function<String, T> memberMapper) {
        if (limit <= 0) {
            return Map.of();
        }
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L);
        Map<T, Double> profile = new LinkedHashMap<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                profile.put(memberMapper.apply(tuple.getValue()), tuple.getScore());
            }
        }
        return profile;
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String tagKey(Long userId) {
        return "recommendation:tag:" + userId;
    }

    private static String authorKey(Long userId) {
        return "recommendation:author:" + userId;
    }

    private record ProfileRow(Long eventId, RecommendationEventType eventType, LocalDateTime occurredAt,
                              Long targetAuthorId, Long articleAuthorId, String tagName) {
    }
}
