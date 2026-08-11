package cumt.zongzuo.community.event.projection;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ProjectionWatermarkMapper {

    @Insert("""
            INSERT IGNORE INTO projection_watermark
              (consumer_name, aggregate_type, aggregate_id, last_applied_version,
               lifecycle_epoch, tombstone, updated_at)
            VALUES (#{consumer}, #{aggregateType}, #{aggregateId}, 0, 0, 0, CURRENT_TIMESTAMP(6))
            """)
    int insertIfAbsent(@Param("consumer") String consumer,
                       @Param("aggregateType") String aggregateType,
                       @Param("aggregateId") long aggregateId);

    @Select("""
            SELECT consumer_name, aggregate_type, aggregate_id, last_applied_version,
                   lifecycle_epoch, tombstone, lease_owner, lease_until, updated_at
            FROM projection_watermark
            WHERE consumer_name = #{consumer} AND aggregate_type = #{aggregateType}
              AND aggregate_id = #{aggregateId}
            FOR UPDATE
            """)
    ProjectionWatermark selectForUpdate(@Param("consumer") String consumer,
                                        @Param("aggregateType") String aggregateType,
                                        @Param("aggregateId") long aggregateId);

    @Select("SELECT CURRENT_TIMESTAMP(6)")
    LocalDateTime selectDatabaseNow();

    @Update("""
            UPDATE projection_watermark
            SET lease_owner = #{leaseOwner},
                lease_until = TIMESTAMPADD(MICROSECOND, #{leaseMicros}, CURRENT_TIMESTAMP(6)),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_name = #{consumer} AND aggregate_type = #{aggregateType}
              AND aggregate_id = #{aggregateId}
              AND (lease_owner IS NULL OR lease_until <= CURRENT_TIMESTAMP(6))
            """)
    int acquire(@Param("consumer") String consumer,
                @Param("aggregateType") String aggregateType,
                @Param("aggregateId") long aggregateId,
                @Param("leaseOwner") String leaseOwner,
                @Param("leaseMicros") long leaseMicros);

    @Update("""
            UPDATE projection_watermark
            SET lease_until = TIMESTAMPADD(MICROSECOND, #{leaseMicros}, CURRENT_TIMESTAMP(6)),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_name = #{consumer} AND aggregate_type = #{aggregateType}
              AND aggregate_id = #{aggregateId} AND lease_owner = #{leaseOwner}
              AND lease_until > CURRENT_TIMESTAMP(6)
            """)
    int renew(@Param("consumer") String consumer,
              @Param("aggregateType") String aggregateType,
              @Param("aggregateId") long aggregateId,
              @Param("leaseOwner") String leaseOwner,
              @Param("leaseMicros") long leaseMicros);

    @Select("""
            SELECT COUNT(*)
            FROM projection_watermark
            WHERE consumer_name = #{consumer} AND aggregate_type = #{aggregateType}
              AND aggregate_id = #{aggregateId} AND lease_owner = #{leaseOwner}
              AND lease_until > CURRENT_TIMESTAMP(6)
            """)
    int countOwned(@Param("consumer") String consumer,
                   @Param("aggregateType") String aggregateType,
                   @Param("aggregateId") long aggregateId,
                   @Param("leaseOwner") String leaseOwner);

    @Select("""
            SELECT COUNT(*)
            FROM projection_watermark
            WHERE consumer_name = #{consumer} AND aggregate_type = #{aggregateType}
              AND aggregate_id = #{aggregateId} AND lease_owner = #{leaseOwner}
              AND lease_until > CURRENT_TIMESTAMP(6)
              AND last_applied_version = #{version} AND lifecycle_epoch = #{lifecycleEpoch}
            """)
    int countRepairOwned(@Param("consumer") String consumer,
                         @Param("aggregateType") String aggregateType,
                         @Param("aggregateId") long aggregateId,
                         @Param("leaseOwner") String leaseOwner,
                         @Param("version") long version,
                         @Param("lifecycleEpoch") long lifecycleEpoch);

    @Update("""
            UPDATE projection_watermark
            SET lease_owner = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_name = #{consumer} AND aggregate_type = #{aggregateType}
              AND aggregate_id = #{aggregateId} AND lease_owner = #{leaseOwner}
              AND lease_until > CURRENT_TIMESTAMP(6)
              AND last_applied_version = #{version} AND lifecycle_epoch = #{lifecycleEpoch}
            """)
    int completeRepair(@Param("consumer") String consumer,
                       @Param("aggregateType") String aggregateType,
                       @Param("aggregateId") long aggregateId,
                       @Param("leaseOwner") String leaseOwner,
                       @Param("version") long version,
                       @Param("lifecycleEpoch") long lifecycleEpoch);

    @Update("""
            UPDATE projection_watermark
            SET last_applied_version = #{version}, lifecycle_epoch = #{lifecycleEpoch},
                tombstone = #{tombstone}, lease_owner = NULL, lease_until = NULL,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_name = #{consumer} AND aggregate_type = #{aggregateType}
              AND aggregate_id = #{aggregateId} AND lease_owner = #{leaseOwner}
              AND lease_until > CURRENT_TIMESTAMP(6)
            """)
    int complete(@Param("consumer") String consumer,
                 @Param("aggregateType") String aggregateType,
                 @Param("aggregateId") long aggregateId,
                 @Param("leaseOwner") String leaseOwner,
                 @Param("version") long version,
                 @Param("lifecycleEpoch") long lifecycleEpoch,
                 @Param("tombstone") boolean tombstone);
}
