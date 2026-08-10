package cumt.zongzuo.community.event.outbox;

import cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DomainEventRetentionMapper {

    String TOMBSTONE_WATERMARK_GATE = """
            AND (event_type NOT IN ('ARTICLE_DELETED','ARTICLE_UNPUBLISHED') OR EXISTS (
                SELECT 1 FROM projection_watermark w
                WHERE w.consumer_name=#{searchConsumer}
                  AND w.aggregate_type=domain_event_outbox.aggregate_type
                  AND w.aggregate_id=domain_event_outbox.aggregate_id
                  AND (w.lifecycle_epoch>domain_event_outbox.lifecycle_epoch
                    OR (w.lifecycle_epoch=domain_event_outbox.lifecycle_epoch
                        AND w.last_applied_version>domain_event_outbox.aggregate_version)
                    OR (w.lifecycle_epoch=domain_event_outbox.lifecycle_epoch
                        AND w.last_applied_version=domain_event_outbox.aggregate_version
                        AND w.tombstone=1)))
            )
            """;

    @Select("""
            SELECT id FROM domain_event_outbox FORCE INDEX (idx_domain_outbox_published_retention)
            WHERE state='PUBLISHED' AND published_at<#{cutoff}
              AND lease_owner IS NULL AND lease_until IS NULL
              AND dead_resolved_at IS NULL AND dead_resolved_by IS NULL AND dead_resolution IS NULL
            """ + TOMBSTONE_WATERMARK_GATE + """
            ORDER BY published_at,id LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<Long> selectPublishedForRetention(@Param("cutoff") LocalDateTime cutoff,
                                           @Param("searchConsumer") String searchConsumer,
                                           @Param("limit") int limit);

    @Delete("""
            <script>
            DELETE FROM domain_event_outbox
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
              AND state='PUBLISHED' AND published_at &lt; #{cutoff}
              AND lease_owner IS NULL AND lease_until IS NULL
              AND dead_resolved_at IS NULL AND dead_resolved_by IS NULL AND dead_resolution IS NULL
            """ + TOMBSTONE_WATERMARK_GATE + """
            </script>
            """)
    int deletePublishedBatchExact(@Param("ids") List<Long> ids,
                                  @Param("cutoff") LocalDateTime cutoff,
                                  @Param("searchConsumer") String searchConsumer);

    @Select("""
            SELECT id FROM domain_event_outbox FORCE INDEX (idx_domain_outbox_dead_retention)
            WHERE state='PUBLISHED' AND dead_resolved_at<#{operatorCutoff}
              AND published_at<#{publishedCutoff}
              AND dead_resolved_by IS NOT NULL AND dead_resolution='REQUEUED'
              AND lease_owner IS NULL AND lease_until IS NULL
            """ + TOMBSTONE_WATERMARK_GATE + """
            ORDER BY dead_resolved_at,id LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<Long> selectRequeuedPublishedForRetention(
                                                   @Param("operatorCutoff") LocalDateTime operatorCutoff,
                                                   @Param("publishedCutoff") LocalDateTime publishedCutoff,
                                                   @Param("searchConsumer") String searchConsumer,
                                                   @Param("limit") int limit);

    @Delete("""
            <script>
            DELETE FROM domain_event_outbox
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
              AND state='PUBLISHED' AND dead_resolved_at &lt; #{operatorCutoff}
              AND published_at &lt; #{publishedCutoff}
              AND dead_resolved_by IS NOT NULL AND dead_resolution='REQUEUED'
              AND lease_owner IS NULL AND lease_until IS NULL
            """ + TOMBSTONE_WATERMARK_GATE + """
            </script>
            """)
    int deleteRequeuedPublishedBatchExact(
            @Param("ids") List<Long> ids,
            @Param("operatorCutoff") LocalDateTime operatorCutoff,
            @Param("publishedCutoff") LocalDateTime publishedCutoff,
            @Param("searchConsumer") String searchConsumer);

    @Select("""
            SELECT id FROM domain_event_outbox FORCE INDEX (idx_domain_outbox_dead_retention)
            WHERE state='DEAD' AND dead_resolved_at<#{cutoff}
              AND dead_resolved_by IS NOT NULL AND dead_resolution='ACKNOWLEDGED'
              AND lease_owner IS NULL AND lease_until IS NULL
            """ + TOMBSTONE_WATERMARK_GATE + """
            ORDER BY dead_resolved_at,id LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<Long> selectResolvedDeadForRetention(@Param("cutoff") LocalDateTime cutoff,
                                              @Param("searchConsumer") String searchConsumer,
                                              @Param("limit") int limit);

    @Delete("""
            <script>
            DELETE FROM domain_event_outbox
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
              AND state='DEAD' AND dead_resolved_at &lt; #{cutoff}
              AND dead_resolved_by IS NOT NULL AND dead_resolution='ACKNOWLEDGED'
              AND lease_owner IS NULL AND lease_until IS NULL
            """ + TOMBSTONE_WATERMARK_GATE + """
            </script>
            """)
    int deleteResolvedDeadBatchExact(@Param("ids") List<Long> ids,
                                     @Param("cutoff") LocalDateTime cutoff,
                                     @Param("searchConsumer") String searchConsumer);

    @Select("""
            SELECT consumer_name,event_id FROM consumer_inbox FORCE INDEX (idx_consumer_inbox_retention)
            WHERE processed_at<#{cutoff}
            ORDER BY processed_at,consumer_name,event_id LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    @Results(id = "consumerInboxRetentionKey", value = {
            @Result(property = "consumerName", column = "consumer_name"),
            @Result(property = "eventId", column = "event_id", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<ConsumerInboxRetentionKey> selectInboxForRetention(@Param("cutoff") LocalDateTime cutoff,
                                                            @Param("limit") int limit);

    @Delete("""
            <script>
            DELETE FROM consumer_inbox
            WHERE (consumer_name,event_id) IN
            <foreach collection="keys" item="key" open="(" separator="," close=")">
                (#{key.consumerName},
                 #{key.eventId,typeHandler=cumt.zongzuo.community.event.persistence.UuidBinaryTypeHandler})
            </foreach>
              AND processed_at &lt; #{cutoff}
            </script>
            """)
    int deleteInboxBatchExact(@Param("keys") List<ConsumerInboxRetentionKey> keys,
                              @Param("cutoff") LocalDateTime cutoff);

    @Select("""
            SELECT id FROM article_revision_migration_issue FORCE INDEX (idx_revision_migration_retention)
            WHERE resolved_at<#{cutoff}
            ORDER BY resolved_at,id LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<Long> selectResolvedMigrationIssuesForRetention(@Param("cutoff") LocalDateTime cutoff,
                                                         @Param("limit") int limit);

    @Delete("""
            <script>
            DELETE FROM article_revision_migration_issue
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
              AND resolved_at &lt; #{cutoff}
            </script>
            """)
    int deleteResolvedMigrationIssueBatchExact(@Param("ids") List<Long> ids,
                                                @Param("cutoff") LocalDateTime cutoff);

    @Select("""
            SELECT COUNT(*) FROM domain_event_outbox
            WHERE state='DEAD'
              AND (dead_resolved_at IS NULL OR dead_resolved_by IS NULL
                OR dead_resolution IS NULL OR dead_resolution<>'ACKNOWLEDGED')
            """)
    long selectUnresolvedDeadCount();

    @Select("""
            SELECT MIN(created_at) FROM domain_event_outbox
            WHERE state IN ('PENDING','IN_FLIGHT')
            """)
    LocalDateTime selectOldestPendingCreatedAt();
}
