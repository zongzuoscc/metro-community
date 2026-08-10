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

    @Select("SELECT * FROM article_moderation_job WHERE id=#{jobId} FOR UPDATE")
    ArticleModerationJob selectByIdForUpdate(@Param("jobId") long jobId);

    @Select("""
            SELECT * FROM article_moderation_job
            WHERE (#{state} IS NULL OR state=#{state})
              AND (#{before} IS NULL OR id<#{before})
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<ArticleModerationJob> selectAdminPage(@Param("state") String state,
                                               @Param("before") Long before,
                                               @Param("limit") int limit);

    @Select("""
            SELECT * FROM article_moderation_job
            WHERE state='PENDING'
               OR (state='RETRY_WAIT' AND (next_attempt_at IS NULL
                                            OR next_attempt_at<=CURRENT_TIMESTAMP(6)))
               OR (state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6))
            ORDER BY id
            LIMIT #{limit}
            """)
    List<ArticleModerationJob> selectRecoverable(@Param("limit") int limit);

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

    @Update("""
            UPDATE article_moderation_job
            SET state=#{state}, reviewer_id=#{reviewerId}, review_reason=#{reason},
                reviewed_at=#{reviewedAt}, updated_at=#{reviewedAt},
                lease_owner=NULL, lease_until=NULL, lock_version=lock_version+1
            WHERE id=#{jobId} AND article_id=#{articleId} AND revision_id=#{revisionId}
              AND content_hash=#{contentHash} AND state='HUMAN_PENDING'
              AND lease_owner IS NULL AND lease_until IS NULL
              AND lock_version=#{expectedLockVersion}
            """)
    int decideHumanPendingExact(@Param("jobId") long jobId,
                                @Param("articleId") long articleId,
                                @Param("revisionId") long revisionId,
                                @Param("contentHash") String contentHash,
                                @Param("expectedLockVersion") long expectedLockVersion,
                                @Param("state") String state,
                                @Param("reviewerId") long reviewerId,
                                @Param("reason") String reason,
                                @Param("reviewedAt") LocalDateTime reviewedAt);

    @Update("""
            UPDATE article_moderation_job
            SET state='RUNNING', lease_owner=#{owner},
                lease_until=TIMESTAMPADD(MICROSECOND,#{leaseMicros},CURRENT_TIMESTAMP(6)),
                next_attempt_at=NULL, last_error=NULL, updated_at=CURRENT_TIMESTAMP(6),
                lock_version=lock_version+1
            WHERE id=#{jobId} AND article_id=#{articleId} AND revision_id=#{revisionId}
              AND content_hash=#{contentHash}
              AND (state='PENDING'
                   OR (state='RETRY_WAIT' AND (next_attempt_at IS NULL
                                               OR next_attempt_at<=CURRENT_TIMESTAMP(6)))
                   OR (state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6)))
            """)
    int claimRevision(@Param("jobId") long jobId,
                      @Param("articleId") long articleId,
                      @Param("revisionId") long revisionId,
                      @Param("contentHash") String contentHash,
                      @Param("owner") String owner,
                      @Param("leaseMicros") long leaseMicros);

    @Select("""
            SELECT COUNT(*) FROM article_moderation_job
            WHERE id=#{jobId} AND state='RUNNING' AND lease_owner=#{owner}
              AND lock_version=#{lockVersion} AND lease_until>CURRENT_TIMESTAMP(6)
            """)
    int hasValidLease(@Param("jobId") long jobId,
                      @Param("owner") String owner,
                      @Param("lockVersion") long lockVersion);

    @Select("""
            SELECT COUNT(*) FROM article_moderation_job
            WHERE id=#{jobId} AND article_id=#{articleId} AND revision_id=#{revisionId}
              AND content_hash=#{contentHash} AND state='RUNNING'
              AND lease_until>CURRENT_TIMESTAMP(6)
            """)
    int isActiveBusy(@Param("jobId") long jobId,
                     @Param("articleId") long articleId,
                     @Param("revisionId") long revisionId,
                     @Param("contentHash") String contentHash);

    @Update("""
            UPDATE article_moderation_job
            SET state='SUPERSEDED', lease_owner=NULL, lease_until=NULL,
                last_error=#{reason}, updated_at=CURRENT_TIMESTAMP(6),
                lock_version=lock_version+1
            WHERE id=#{jobId} AND state='RUNNING' AND lease_owner=#{owner}
              AND lock_version=#{lockVersion} AND lease_until>CURRENT_TIMESTAMP(6)
            """)
    int supersedeOwned(@Param("jobId") long jobId,
                       @Param("owner") String owner,
                       @Param("lockVersion") long lockVersion,
                       @Param("reason") String reason);

    @Update("""
            UPDATE article_moderation_job
            SET attempt_count=#{attemptNo}, updated_at=CURRENT_TIMESTAMP(6),
                lock_version=lock_version+1
            WHERE id=#{jobId} AND state='RUNNING' AND lease_owner=#{owner}
              AND lock_version=#{lockVersion} AND attempt_count=#{previousAttemptNo}
              AND lease_until>CURRENT_TIMESTAMP(6)
            """)
    int advanceAttempt(@Param("jobId") long jobId,
                       @Param("owner") String owner,
                       @Param("lockVersion") long lockVersion,
                       @Param("previousAttemptNo") int previousAttemptNo,
                       @Param("attemptNo") int attemptNo);

    @Update("""
            UPDATE article_moderation_job
            SET state='HUMAN_PENDING',
                lease_owner=NULL, lease_until=NULL, last_error=#{errorCode},
                updated_at=CURRENT_TIMESTAMP(6), lock_version=lock_version+1
            WHERE id=#{jobId} AND state='RUNNING' AND lease_owner=#{owner}
              AND lock_version=#{lockVersion} AND attempt_count=#{attemptNo}
              AND lease_until>CURRENT_TIMESTAMP(6)
            """)
    int failToHumanPending(@Param("jobId") long jobId,
                           @Param("owner") String owner,
                           @Param("lockVersion") long lockVersion,
                           @Param("attemptNo") int attemptNo,
                           @Param("errorCode") String errorCode);

    @Update("""
            UPDATE article_moderation_job
            SET state='HUMAN_PENDING', lease_owner=NULL, lease_until=NULL,
                last_error=#{errorCode}, updated_at=CURRENT_TIMESTAMP(6),
                lock_version=lock_version+1
            WHERE id=#{jobId} AND state='RUNNING' AND lease_owner=#{owner}
              AND lock_version=#{lockVersion} AND lease_until>CURRENT_TIMESTAMP(6)
            """)
    int preProviderFailureToHumanPending(@Param("jobId") long jobId,
                                         @Param("owner") String owner,
                                         @Param("lockVersion") long lockVersion,
                                         @Param("errorCode") String errorCode);

    @Update("""
            UPDATE article_moderation_job
            SET state=#{modelState}, model_decision=#{decision}, risk_score=#{riskScore},
                policy_hits_json=#{policyHitsJson}, last_error=#{errorCode},
                updated_at=CURRENT_TIMESTAMP(6), lock_version=lock_version+1
            WHERE id=#{jobId} AND state='RUNNING' AND lease_owner=#{owner}
              AND lock_version=#{lockVersion} AND lease_until>CURRENT_TIMESTAMP(6)
            """)
    int recordModelTransition(@Param("jobId") long jobId,
                              @Param("owner") String owner,
                              @Param("lockVersion") long lockVersion,
                              @Param("modelState") String modelState,
                              @Param("decision") String decision,
                              @Param("riskScore") java.math.BigDecimal riskScore,
                              @Param("policyHitsJson") String policyHitsJson,
                              @Param("errorCode") String errorCode);

    @Update("""
            UPDATE article_moderation_job
            SET state='HUMAN_PENDING', lease_owner=NULL, lease_until=NULL,
                updated_at=CURRENT_TIMESTAMP(6), lock_version=lock_version+1
            WHERE id=#{jobId} AND state=#{modelState} AND lease_owner=#{owner}
              AND lock_version=#{lockVersion} AND lease_until>CURRENT_TIMESTAMP(6)
            """)
    int modelToHumanPending(@Param("jobId") long jobId,
                            @Param("owner") String owner,
                            @Param("lockVersion") long lockVersion,
                            @Param("modelState") String modelState);

    @Update("""
            UPDATE article_moderation_job
            SET state='HUMAN_PENDING', lease_owner=NULL, lease_until=NULL,
                last_error=#{errorCode}, updated_at=CURRENT_TIMESTAMP(6),
                lock_version=lock_version+1
            WHERE id=#{jobId} AND article_id=#{articleId} AND revision_id=#{revisionId}
              AND content_hash=#{contentHash}
              AND (state='PENDING'
                   OR (state='RETRY_WAIT' AND (next_attempt_at IS NULL
                                               OR next_attempt_at<=CURRENT_TIMESTAMP(6)))
                   OR (state='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6)))
            """)
    int routeUnavailableToHumanPending(@Param("jobId") long jobId,
                                       @Param("articleId") long articleId,
                                       @Param("revisionId") long revisionId,
                                       @Param("contentHash") String contentHash,
                                       @Param("errorCode") String errorCode);
}
