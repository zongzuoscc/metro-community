package cumt.zongzuo.community.recommendation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RecommendationEventOutboxMapper extends BaseMapper<RecommendationEventOutbox> {

    @Insert("""
            INSERT INTO recommendation_event_outbox
                (user_id, article_id, target_author_id, event_type, occurred_at, dedupe_key,
                 source, status, retry_count, next_attempt_at, create_time, update_time)
            VALUES
                (#{row.userId}, #{row.articleId}, #{row.targetAuthorId}, #{row.eventType},
                 #{row.occurredAt}, #{row.dedupeKey}, #{row.source}, #{row.status},
                 #{row.retryCount}, #{row.nextAttemptAt}, #{row.createTime}, #{row.updateTime})
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """)
    int insertViewIfAbsent(@Param("row") RecommendationEventOutbox row);

    @Select("""
            SELECT * FROM recommendation_event_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= #{now}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<RecommendationEventOutbox> selectEligible(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE recommendation_event_outbox
            SET status = 'SENDING', update_time = #{claimedAt}
            WHERE id = #{id} AND status = 'PENDING' AND next_attempt_at <= #{claimedAt}
            """)
    int claim(@Param("id") Long id, @Param("claimedAt") LocalDateTime claimedAt);

    @Update("""
            UPDATE recommendation_event_outbox
            SET status = 'PENDING', next_attempt_at = #{now}, update_time = #{now}
            WHERE status = 'SENDING' AND update_time < #{staleBefore}
            """)
    int recoverStale(@Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE recommendation_event_outbox
            SET status = 'SENT', sent_time = #{sentAt}, last_error = NULL, update_time = #{sentAt}
            WHERE id = #{id} AND status = 'SENDING'
            """)
    int markSent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    @Update("""
            UPDATE recommendation_event_outbox
            SET status = 'PENDING', retry_count = #{retryCount}, next_attempt_at = #{nextAttemptAt},
                last_error = #{lastError}, update_time = #{failedAt}
            WHERE id = #{id} AND status = 'SENDING'
            """)
    int markRetry(@Param("id") Long id,
                  @Param("retryCount") int retryCount,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                  @Param("lastError") String lastError,
                  @Param("failedAt") LocalDateTime failedAt);
}
