package cumt.zongzuo.community.ai.moderation.revision;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ArticleModerationJobMapper extends BaseMapper<ArticleModerationJob> {

    @Select("""
            SELECT * FROM article_moderation_job
            WHERE article_id=#{articleId}
              AND state NOT IN ('SUPERSEDED','HUMAN_APPROVED','HUMAN_REJECTED')
            ORDER BY id
            FOR UPDATE
            """)
    List<ArticleModerationJob> selectNonTerminalForUpdate(@Param("articleId") long articleId);

    @Update("""
            UPDATE article_moderation_job
            SET state='SUPERSEDED', lease_owner=NULL, lease_until=NULL,
                updated_at=#{updatedAt}, lock_version=lock_version+1
            WHERE id=#{jobId} AND lock_version=#{expectedLockVersion}
              AND state NOT IN ('SUPERSEDED','HUMAN_APPROVED','HUMAN_REJECTED')
            """)
    int supersede(@Param("jobId") long jobId,
                  @Param("expectedLockVersion") long expectedLockVersion,
                  @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            SELECT * FROM article_moderation_job
            WHERE article_id=#{articleId} AND revision_id=#{revisionId}
            FOR UPDATE
            """)
    ArticleModerationJob selectRevisionJobForUpdate(@Param("articleId") long articleId,
                                                    @Param("revisionId") long revisionId);

    @Update("""
            UPDATE article_moderation_job
            SET state=#{state}, reviewer_id=#{reviewerId}, review_reason=#{reason},
                reviewed_at=#{reviewedAt}, updated_at=#{reviewedAt},
                lease_owner=NULL, lease_until=NULL, lock_version=lock_version+1
            WHERE id=#{jobId} AND revision_id=#{revisionId} AND content_hash=#{contentHash}
              AND lock_version=#{expectedLockVersion}
              AND state NOT IN ('SUPERSEDED','HUMAN_APPROVED','HUMAN_REJECTED')
            """)
    int decideLegacyShadowJob(@Param("jobId") long jobId,
                              @Param("revisionId") long revisionId,
                              @Param("contentHash") String contentHash,
                              @Param("expectedLockVersion") long expectedLockVersion,
                              @Param("state") String state,
                              @Param("reviewerId") long reviewerId,
                              @Param("reason") String reason,
                              @Param("reviewedAt") LocalDateTime reviewedAt);
}
