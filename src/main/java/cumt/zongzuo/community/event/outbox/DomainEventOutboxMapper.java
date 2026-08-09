package cumt.zongzuo.community.event.outbox;

import cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface DomainEventOutboxMapper {

    @Insert("""
            INSERT INTO domain_event_outbox
              (event_id, aggregate_type, aggregate_id, aggregate_version, lifecycle_epoch,
               event_type, payload_version, payload_json, dedupe_key, occurred_at,
               state, retry_count, next_attempt_at, created_at)
            VALUES
              (#{row.eventId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler},
               #{row.aggregateType}, #{row.aggregateId}, #{row.aggregateVersion},
               #{row.lifecycleEpoch}, #{row.eventType}, #{row.payloadVersion},
               CAST(#{row.payloadJson} AS JSON), #{row.dedupeKey}, #{row.occurredAt},
               'PENDING', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """)
    int insertIdempotently(@Param("row") DomainEventOutbox row);

    @Select("""
            SELECT * FROM domain_event_outbox
            WHERE dedupe_key = #{dedupeKey}
            """)
    @Results(id = "domainEventOutboxRow", value = {
            @Result(property = "eventId", column = "event_id",
                    typeHandler = UuidBinaryTypeHandler.class)
    })
    DomainEventOutbox selectByDedupeKey(@Param("dedupeKey") String dedupeKey);

    @Select("""
            SELECT * FROM domain_event_outbox
            WHERE event_id = #{eventId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler}
            """)
    @Results(id = "domainEventOutboxByEventId", value = {
            @Result(property = "eventId", column = "event_id",
                    typeHandler = UuidBinaryTypeHandler.class)
    })
    DomainEventOutbox selectByEventId(@Param("eventId") UUID eventId);

    @Select("""
            SELECT * FROM domain_event_outbox
            WHERE id = #{id}
            """)
    @Results(id = "domainEventOutboxById", value = {
            @Result(property = "eventId", column = "event_id",
                    typeHandler = UuidBinaryTypeHandler.class)
    })
    DomainEventOutbox selectById(@Param("id") long id);

    @Select("""
            SELECT * FROM domain_event_outbox
            WHERE (state = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6))
               OR (state = 'IN_FLIGHT' AND lease_until <= CURRENT_TIMESTAMP(6))
            ORDER BY id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    @Results(id = "domainEventOutboxClaim", value = {
            @Result(property = "eventId", column = "event_id",
                    typeHandler = UuidBinaryTypeHandler.class)
    })
    List<DomainEventOutbox> selectClaimableForUpdate(@Param("limit") int limit);

    @Update("""
            UPDATE domain_event_outbox
            SET state = 'IN_FLIGHT', retry_count = #{attempt}, lease_owner = #{leaseOwner},
                lease_until = TIMESTAMPADD(MICROSECOND, #{leaseMicros}, CURRENT_TIMESTAMP(6))
            WHERE id = #{id}
              AND ((state = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(6))
                OR (state = 'IN_FLIGHT' AND lease_until <= CURRENT_TIMESTAMP(6)))
            """)
    int claim(@Param("id") long id,
              @Param("leaseOwner") String leaseOwner,
              @Param("attempt") int attempt,
              @Param("leaseMicros") long leaseMicros);

    @Select("SELECT CURRENT_TIMESTAMP(6)")
    Instant selectDatabaseNow();

    @Update("""
            UPDATE domain_event_outbox
            SET state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(6), failed_at = NULL,
                lease_owner = NULL, lease_until = NULL, last_error = NULL
            WHERE id = #{id} AND state = 'IN_FLIGHT' AND lease_owner = #{leaseOwner}
            """)
    int markPublished(@Param("id") long id,
                      @Param("leaseOwner") String leaseOwner,
                      @Param("publishedAt") Instant publishedAt);

    @Update("""
            UPDATE domain_event_outbox
            SET state = 'PENDING', retry_count = #{retryCount}, next_attempt_at = #{nextAttemptAt},
                lease_owner = NULL, lease_until = NULL, last_error = #{error}
            WHERE id = #{id} AND state = 'IN_FLIGHT' AND lease_owner = #{leaseOwner}
            """)
    int markRetry(@Param("id") long id,
                  @Param("leaseOwner") String leaseOwner,
                  @Param("retryCount") int retryCount,
                  @Param("nextAttemptAt") Instant nextAttemptAt,
                  @Param("error") String error);

    @Update("""
            UPDATE domain_event_outbox
            SET state = 'DEAD', retry_count = #{retryCount}, failed_at = CURRENT_TIMESTAMP(6),
                lease_owner = NULL, lease_until = NULL, last_error = #{error}
            WHERE id = #{id} AND state = 'IN_FLIGHT' AND lease_owner = #{leaseOwner}
            """)
    int markDead(@Param("id") long id,
                 @Param("leaseOwner") String leaseOwner,
                 @Param("retryCount") int retryCount,
                 @Param("error") String error,
                 @Param("failedAt") Instant failedAt);
}
