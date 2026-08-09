package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RecommendationProfileService {

    private static final int LOCK_TIMEOUT_SECONDS = 5;
    private static final DefaultRedisScript<Long> REPLACE_PROFILE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('exists', KEYS[1]) == 1 then
                redis.call('rename', KEYS[1], KEYS[2])
                redis.call('expire', KEYS[2], ARGV[1])
            else
                redis.call('del', KEYS[2])
            end
            if redis.call('exists', KEYS[3]) == 1 then
                redis.call('rename', KEYS[3], KEYS[4])
                redis.call('expire', KEYS[4], ARGV[1])
            else
                redis.call('del', KEYS[4])
            end
            return 1
            """, Long.class);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final RecommendationProperties properties;
    private final Clock clock;

    @Autowired
    public RecommendationProfileService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate,
                                        RecommendationProperties properties, ObjectProvider<Clock> clocks) {
        this(jdbcTemplate, redisTemplate, properties, clocks.getIfAvailable(Clock::systemDefaultZone));
    }

    public RecommendationProfileService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate,
                                        RecommendationProperties properties) {
        this(jdbcTemplate, redisTemplate, properties, Clock.systemDefaultZone());
    }

    RecommendationProfileService(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate,
                                 RecommendationProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
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
        return readProfileForServing(tagKey(userId), limit, value -> value);
    }

    public Map<Long, Double> profileAuthors(Long userId, int limit) {
        return readProfileForServing(authorKey(userId), limit, Long::valueOf);
    }

    private <T> Map<T, Double> readProfileForServing(String key, int limit,
                                                      java.util.function.Function<String, T> memberMapper) {
        try {
            return readProfile(key, limit, memberMapper);
        } catch (DataAccessException redisFailure) {
            throw new RecommendationServingUnavailableException("Recommendation profile Redis unavailable", redisFailure);
        }
    }

    private void rebuildWhileLocked(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime cutoff = now.minusDays(properties.getProfileWindowDays());
        int factLimit = Math.max(1, properties.getProfileFactLimit());
        List<ProfileScore> tags = topTags(userId, cutoff, now, factLimit);
        List<ProfileScore> authors = topAuthors(userId, cutoff, now, factLimit);

        String suffix = ":rebuild:" + UUID.randomUUID();
        String temporaryTagKey = tagKey(userId) + suffix;
        String temporaryAuthorKey = authorKey(userId) + suffix;
        try {
            writeProfile(temporaryTagKey, tags);
            writeProfile(temporaryAuthorKey, authors);
            redisTemplate.execute(REPLACE_PROFILE_SCRIPT,
                    List.of(temporaryTagKey, tagKey(userId), temporaryAuthorKey, authorKey(userId)),
                    Long.toString(TimeUnit.DAYS.toSeconds(Math.max(1, properties.getProfileTtlDays()))));
        } finally {
            redisTemplate.delete(List.of(temporaryTagKey, temporaryAuthorKey));
        }
    }

    private List<ProfileScore> topTags(Long userId, LocalDateTime cutoff, LocalDateTime now, int factLimit) {
        int memberLimit = Math.max(0, properties.getProfileMaxTags());
        if (memberLimit == 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                WITH bounded_events AS (
                  SELECT id,event_type,occurred_at,article_id
                  FROM user_article_event
                  WHERE user_id=? AND occurred_at>=?
                  ORDER BY occurred_at DESC,id DESC LIMIT ?
                ), bounded_tag_rows AS (
                  SELECT e.event_type,e.occurred_at,t.name AS member
                  FROM bounded_events e
                  JOIN article_tag article_tags ON article_tags.article_id=e.article_id
                  JOIN tag t ON t.id=article_tags.tag_id
                  WHERE e.event_type<>'FOLLOW_AUTHOR'
                  ORDER BY e.occurred_at DESC,e.id DESC,article_tags.id ASC
                  LIMIT ?
                )
                SELECT member,SUM(
                  CASE event_type
                    WHEN 'VIEW' THEN 1 WHEN 'LIKE' THEN 4 WHEN 'COLLECT' THEN 8
                    WHEN 'COMMENT' THEN 6 ELSE 0 END
                  * EXP(-LN(2)*GREATEST(0,DATEDIFF(DATE(?),DATE(occurred_at)))/14.0)
                ) AS score
                FROM bounded_tag_rows
                GROUP BY member ORDER BY score DESC,member ASC LIMIT ?
                """, (rs, rowNumber) -> new ProfileScore(rs.getString(1), rs.getDouble(2)),
                userId, cutoff, factLimit, Math.max(1, properties.getProfileTagAssociationLimit()), now, memberLimit);
    }

    private List<ProfileScore> topAuthors(Long userId, LocalDateTime cutoff, LocalDateTime now, int factLimit) {
        int memberLimit = Math.max(0, properties.getProfileMaxAuthors());
        if (memberLimit == 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                WITH bounded_events AS (
                  SELECT id,event_type,occurred_at,article_id,target_author_id
                  FROM user_article_event
                  WHERE user_id=? AND occurred_at>=?
                  ORDER BY occurred_at DESC,id DESC LIMIT ?
                ), author_events AS (
                  SELECT CASE WHEN e.event_type='FOLLOW_AUTHOR' THEN e.target_author_id
                              ELSE a.author_id END AS member,
                         e.event_type,e.occurred_at
                  FROM bounded_events e LEFT JOIN article a ON a.id=e.article_id
                )
                SELECT member,SUM(
                  CASE event_type
                    WHEN 'VIEW' THEN 1 WHEN 'LIKE' THEN 4 WHEN 'COLLECT' THEN 8
                    WHEN 'COMMENT' THEN 6 WHEN 'FOLLOW_AUTHOR' THEN 10 ELSE 0 END
                  * EXP(-LN(2)*GREATEST(0,DATEDIFF(DATE(?),DATE(occurred_at)))/14.0)
                ) AS score
                FROM author_events WHERE member IS NOT NULL
                GROUP BY member ORDER BY score DESC,member ASC LIMIT ?
                """, (rs, rowNumber) -> new ProfileScore(rs.getString(1), rs.getDouble(2)),
                userId, cutoff, factLimit, now, memberLimit);
    }

    private void writeProfile(String key, List<ProfileScore> scores) {
        if (scores.isEmpty()) {
            return;
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = scores.stream()
                .map(score -> (ZSetOperations.TypedTuple<String>)
                        new DefaultTypedTuple<>(score.member(), score.score()))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        redisTemplate.opsForZSet().add(key, tuples);
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

    private static String tagKey(Long userId) {
        return "recommendation:tag:" + userId;
    }

    private static String authorKey(Long userId) {
        return "recommendation:author:" + userId;
    }

    private record ProfileScore(String member, double score) {}
}
