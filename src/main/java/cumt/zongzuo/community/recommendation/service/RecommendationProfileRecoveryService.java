package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class RecommendationProfileRecoveryService {

    private static final int MAX_BACKOFF_SECONDS = 300;

    private final JdbcTemplate jdbc;
    private final RecommendationProfileService profileService;
    private final RecommendationProperties properties;
    private final Clock clock;

    public RecommendationProfileRecoveryService(JdbcTemplate jdbc,
                                                RecommendationProfileService profileService,
                                                RecommendationProperties properties,
                                                ObjectProvider<Clock> clocks) {
        this.jdbc = jdbc;
        this.profileService = profileService;
        this.properties = properties;
        this.clock = clocks.getIfAvailable(Clock::systemDefaultZone);
    }

    public void requestRebuild(Long userId, Long eventId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        jdbc.update("""
                INSERT INTO recommendation_profile_checkpoint
                  (user_id,requested_event_id,rebuilt_event_id,retry_count,next_attempt_at,
                   last_error,create_time,update_time)
                VALUES (?,?,0,0,?,NULL,?,?)
                ON DUPLICATE KEY UPDATE
                  next_attempt_at=IF(VALUES(requested_event_id)>requested_event_id,
                                     LEAST(next_attempt_at,VALUES(next_attempt_at)),next_attempt_at),
                  retry_count=IF(VALUES(requested_event_id)>requested_event_id,0,retry_count),
                  last_error=IF(VALUES(requested_event_id)>requested_event_id,NULL,last_error),
                  requested_event_id=GREATEST(requested_event_id,VALUES(requested_event_id)),
                  update_time=VALUES(update_time)
                """, userId, eventId, now, now, now);
    }

    public void markRebuilt(Long userId, Long rebuiltThroughEventId) {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        jdbc.update("""
                UPDATE recommendation_profile_checkpoint
                SET rebuilt_event_id=GREATEST(rebuilt_event_id,LEAST(requested_event_id,?)),
                    retry_count=IF(requested_event_id<=?,0,retry_count),
                    next_attempt_at=IF(requested_event_id<=?,?,next_attempt_at),
                    last_error=IF(requested_event_id<=?,NULL,last_error),
                    update_time=?
                WHERE user_id=?
                """, rebuiltThroughEventId, rebuiltThroughEventId, rebuiltThroughEventId, now,
                rebuiltThroughEventId, now, userId);
    }

    public int repairDueProfiles() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        int batchSize = Math.max(1, properties.getProfileRepairBatchSize());
        List<Checkpoint> due = jdbc.query("""
                SELECT user_id,requested_event_id,retry_count
                FROM recommendation_profile_checkpoint
                WHERE requested_event_id>rebuilt_event_id AND next_attempt_at<=?
                ORDER BY next_attempt_at ASC,user_id ASC LIMIT ?
                """, (rs, rowNumber) -> new Checkpoint(
                rs.getLong(1), rs.getLong(2), rs.getInt(3)), now, batchSize);
        int repaired = 0;
        for (Checkpoint checkpoint : due) {
            try {
                profileService.rebuildProfile(checkpoint.userId());
                markRebuilt(checkpoint.userId(), checkpoint.requestedEventId());
                repaired++;
            } catch (RuntimeException failure) {
                markFailed(checkpoint, failure, now);
                log.warn("Recommendation profile repair failed for user {} through event {}",
                        checkpoint.userId(), checkpoint.requestedEventId(), failure);
            }
        }
        return repaired;
    }

    private void markFailed(Checkpoint checkpoint, RuntimeException failure, LocalDateTime failedAt) {
        int retryCount = checkpoint.retryCount() + 1;
        long delaySeconds = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(retryCount, 8));
        String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (error.length() > 500) {
            error = error.substring(0, 500);
        }
        jdbc.update("""
                UPDATE recommendation_profile_checkpoint
                SET retry_count=?,next_attempt_at=?,last_error=?,update_time=?
                WHERE user_id=? AND requested_event_id=? AND rebuilt_event_id<requested_event_id
                """, retryCount, failedAt.plusSeconds(delaySeconds), error, failedAt,
                checkpoint.userId(), checkpoint.requestedEventId());
    }

    private record Checkpoint(Long userId, Long requestedEventId, int retryCount) {}
}
