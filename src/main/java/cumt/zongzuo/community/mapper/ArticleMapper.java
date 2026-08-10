package cumt.zongzuo.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    @Select("SELECT IFNULL(SUM(like_count), 0) FROM article WHERE author_id = #{authorId}")
    Long sumLikesByAuthorId(Long authorId);

    @Update("""
            UPDATE article SET like_count=GREATEST(0,COALESCE(like_count,0)+#{delta})
            WHERE id=#{articleId} AND is_deleted=0
            """)
    int addLikeCount(@Param("articleId") long articleId, @Param("delta") int delta);

    @Update("""
            UPDATE article SET comment_count=GREATEST(0,COALESCE(comment_count,0)+#{delta})
            WHERE id=#{articleId} AND is_deleted=0
            """)
    int addCommentCount(@Param("articleId") long articleId, @Param("delta") int delta);

    @Update("""
            UPDATE article SET view_count=GREATEST(0,#{viewCount})
            WHERE id=#{articleId} AND is_deleted=0
            """)
    int setViewCount(@Param("articleId") long articleId, @Param("viewCount") int viewCount);

    @Select("""
            SELECT f.article_id
            FROM favorite f
            WHERE f.folder_id=#{folderId}
            ORDER BY f.create_time DESC,f.id DESC
            """)
    List<Long> selectArticleIdsByFolderId(@Param("folderId") Long folderId);

    List<Article> selectPublishedByFollowedAuthors(@Param("userId") Long userId,
                                                   @Param("excludedAuthorId") Long excludedAuthorId,
                                                   @Param("shownArticleIds") Collection<Long> shownArticleIds,
                                                   @Param("limit") int limit);

    List<Article> selectPublishedByTagNames(@Param("tagNames") Collection<String> tagNames,
                                            @Param("excludedAuthorId") Long excludedAuthorId,
                                            @Param("shownArticleIds") Collection<Long> shownArticleIds,
                                            @Param("limit") int limit);

    List<Article> selectPublishedHotFresh(@Param("excludedAuthorId") Long excludedAuthorId,
                                          @Param("shownArticleIds") Collection<Long> shownArticleIds,
                                          @Param("limit") int limit);

    List<Article> selectPublishedByIds(@Param("articleIds") Collection<Long> articleIds);

    List<Article> selectPublishedChronological(@Param("beforeCreateTime") LocalDateTime beforeCreateTime,
                                               @Param("beforeId") Long beforeId,
                                               @Param("limit") int limit);

    List<Long> selectPublishedChronologicalIds(@Param("limit") int limit);

    List<Article> selectPublishedHot(@Param("limit") int limit);

    List<Article> selectPublishedHotRank(@Param("limit") int limit);

    List<Article> selectPublishedByAuthor(@Param("authorId") Long authorId,
                                          @Param("offset") long offset,
                                          @Param("limit") int limit);

    long countPublishedByAuthor(@Param("authorId") Long authorId);

    List<Article> selectPublishedByFollowing(@Param("userId") Long userId,
                                             @Param("offset") long offset,
                                             @Param("limit") int limit);

    long countPublishedByFollowing(@Param("userId") Long userId);

    List<Article> selectPublishedPage(@Param("offset") long offset,
                                      @Param("limit") int limit);

    long countPublished();

    List<Article> selectPublishedHotSince(@Param("since") LocalDateTime since,
                                          @Param("limit") int limit);

    List<Article> selectLegacyPublishedByFollowedAuthors(@Param("userId") Long userId,
                                                         @Param("excludedAuthorId") Long excludedAuthorId,
                                                         @Param("shownArticleIds") Collection<Long> shownArticleIds,
                                                         @Param("limit") int limit);

    List<Article> selectLegacyPublishedByTagIds(@Param("tagIds") Collection<Long> tagIds,
                                                @Param("excludedAuthorId") Long excludedAuthorId,
                                                @Param("shownArticleIds") Collection<Long> shownArticleIds,
                                                @Param("limit") int limit);

    List<Article> selectLegacyPublishedHotFresh(@Param("excludedAuthorId") Long excludedAuthorId,
                                                @Param("shownArticleIds") Collection<Long> shownArticleIds,
                                                @Param("limit") int limit);

    List<Article> selectLegacyPublishedByIds(@Param("articleIds") Collection<Long> articleIds);

    List<Article> selectLegacyPublishedChronological(@Param("beforeCreateTime") LocalDateTime beforeCreateTime,
                                                     @Param("beforeId") Long beforeId,
                                                     @Param("limit") int limit);

    List<Long> selectLegacyPublishedChronologicalIds(@Param("limit") int limit);

    List<Article> selectLegacyPublishedHot(@Param("limit") int limit);

    List<Article> selectLegacyPublishedHotRank(@Param("limit") int limit);

    List<Article> selectLegacyPublishedByAuthor(@Param("authorId") Long authorId,
                                                @Param("offset") long offset,
                                                @Param("limit") int limit);

    long countLegacyPublishedByAuthor(@Param("authorId") Long authorId);

    List<Article> selectLegacyPublishedByFollowing(@Param("userId") Long userId,
                                                   @Param("offset") long offset,
                                                   @Param("limit") int limit);

    long countLegacyPublishedByFollowing(@Param("userId") Long userId);

    List<Article> selectLegacyPublishedPage(@Param("offset") long offset,
                                            @Param("limit") int limit);

    long countLegacyPublished();

    List<Article> selectLegacyPublishedHotSince(@Param("since") LocalDateTime since,
                                                @Param("limit") int limit);

    Article selectPublicById(@Param("articleId") Long articleId);

    Article selectLegacyPublicById(@Param("articleId") Long articleId);

    Article selectOwnerDraftById(@Param("articleId") Long articleId,
                                 @Param("authorId") Long authorId);

    Article selectOwnerLegacyById(@Param("articleId") Long articleId,
                                  @Param("authorId") Long authorId);

    List<Article> selectOwnerDirtyDrafts(@Param("authorId") Long authorId,
                                         @Param("offset") long offset,
                                         @Param("limit") int limit);

    long countOwnerDirtyDrafts(@Param("authorId") Long authorId);

    List<Article> selectOwnerAllDrafts(@Param("authorId") Long authorId,
                                       @Param("offset") long offset,
                                       @Param("limit") int limit);

    long countOwnerAllDrafts(@Param("authorId") Long authorId);

    List<Article> selectOwnerLegacyDrafts(@Param("authorId") Long authorId,
                                          @Param("offset") long offset,
                                          @Param("limit") int limit);

    long countOwnerLegacyDrafts(@Param("authorId") Long authorId);

    List<Article> selectOwnerLegacyAll(@Param("authorId") Long authorId,
                                       @Param("offset") long offset,
                                       @Param("limit") int limit);

    long countOwnerLegacyAll(@Param("authorId") Long authorId);

    List<Article> selectOwnerDraftRecycle(@Param("authorId") Long authorId);

    List<Article> selectOwnerLegacyRecycle(@Param("authorId") Long authorId);

    List<Article> selectPendingRevisions(@Param("offset") long offset,
                                         @Param("limit") int limit);

    long countPendingRevisions();

    List<Article> selectLegacyPending(@Param("offset") long offset,
                                      @Param("limit") int limit);

    long countLegacyPending();

    @Select("SELECT * FROM article WHERE id = #{articleId} FOR UPDATE")
    Article selectByIdForUpdate(@Param("articleId") long articleId);

    @Update("""
            UPDATE article
            SET title=#{title}, summary=#{summary}, content=#{content}, cover=#{cover},
                status=0, update_time=#{updatedAt}, lock_version=lock_version+1
            WHERE id=#{articleId} AND author_id=#{authorId} AND is_deleted=0
            """)
    int updateLegacyDraftContent(@Param("articleId") long articleId,
                                 @Param("authorId") long authorId,
                                 @Param("title") String title,
                                 @Param("summary") String summary,
                                 @Param("content") String content,
                                 @Param("cover") String cover,
                                 @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE article
            SET title=#{title}, summary=#{summary}, content=#{content}, cover=#{cover},
                status=#{status}, update_time=#{updatedAt}, lock_version=lock_version+1
            WHERE id=#{articleId} AND author_id=#{authorId} AND is_deleted=0
              AND lock_version=#{expectedLockVersion}
            """)
    int updateLegacyContent(@Param("articleId") long articleId,
                            @Param("authorId") long authorId,
                            @Param("title") String title,
                            @Param("summary") String summary,
                            @Param("content") String content,
                            @Param("cover") String cover,
                            @Param("status") int status,
                            @Param("expectedLockVersion") long expectedLockVersion,
                            @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE article
            SET latest_revision_id=#{revisionId}, pending_revision_id=#{revisionId},
                visibility_state=#{visibilityState}, review_state=#{reviewState}, status=#{status},
                lock_version=lock_version+#{versionIncrement}, update_time=#{updatedAt}
            WHERE id=#{articleId} AND author_id=#{authorId} AND is_deleted=0
              AND lock_version=#{expectedLockVersion}
            """)
    int updateSubmissionPointers(@Param("articleId") long articleId,
                                 @Param("authorId") long authorId,
                                 @Param("revisionId") long revisionId,
                                 @Param("visibilityState") String visibilityState,
                                 @Param("reviewState") String reviewState,
                                 @Param("status") int status,
                                 @Param("versionIncrement") int versionIncrement,
                                 @Param("expectedLockVersion") long expectedLockVersion,
                                 @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE article
            SET latest_revision_id=#{latestRevisionId}, pending_revision_id=#{pendingRevisionId},
                published_revision_id=#{publishedRevisionId}, visibility_state=#{visibilityState},
                review_state=#{reviewState}, lock_version=lock_version+1
            WHERE id=#{articleId} AND lock_version=#{expectedLockVersion}
              AND latest_revision_id IS NULL AND pending_revision_id IS NULL
              AND published_revision_id IS NULL
            """)
    int initializeShadowState(@Param("articleId") long articleId,
                              @Param("latestRevisionId") Long latestRevisionId,
                              @Param("pendingRevisionId") Long pendingRevisionId,
                              @Param("publishedRevisionId") Long publishedRevisionId,
                              @Param("visibilityState") String visibilityState,
                              @Param("reviewState") String reviewState,
                              @Param("expectedLockVersion") long expectedLockVersion);

    @Update("""
            UPDATE article
            SET status=#{targetStatus}, lock_version=lock_version+1, update_time=#{updatedAt}
            WHERE id=#{articleId} AND status=2 AND is_deleted=0
              AND lock_version=#{expectedLockVersion}
            """)
    int updateLegacyModerationDecision(@Param("articleId") long articleId,
                                       @Param("targetStatus") int targetStatus,
                                       @Param("expectedLockVersion") long expectedLockVersion,
                                       @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE article
            SET published_revision_id=#{publishedRevisionId}, pending_revision_id=NULL,
                visibility_state=#{visibilityState}, review_state=#{reviewState}, status=#{status},
                lock_version=lock_version+1, update_time=#{updatedAt}
            WHERE id=#{articleId} AND pending_revision_id=#{revisionId} AND is_deleted=0
              AND lock_version=#{expectedLockVersion}
            """)
    int updateShadowModerationDecision(@Param("articleId") long articleId,
                                       @Param("revisionId") long revisionId,
                                       @Param("publishedRevisionId") Long publishedRevisionId,
                                       @Param("visibilityState") String visibilityState,
                                       @Param("reviewState") String reviewState,
                                       @Param("status") int status,
                                       @Param("expectedLockVersion") long expectedLockVersion,
                                       @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE article
            SET status=3, lock_version=lock_version+1, update_time=#{updatedAt}
            WHERE id=#{articleId} AND is_deleted=0
              AND lock_version=#{expectedLockVersion}
            """)
    int rejectReportedLegacy(@Param("articleId") long articleId,
                             @Param("expectedLockVersion") long expectedLockVersion,
                             @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE article
            SET published_revision_id=NULL, pending_revision_id=NULL,
                visibility_state='PRIVATE', review_state='REJECTED', status=3,
                lock_version=lock_version+#{versionIncrement}, update_time=#{updatedAt}
            WHERE id=#{articleId} AND is_deleted=0
              AND lock_version=#{expectedLockVersion}
            """)
    int rejectReportedShadow(@Param("articleId") long articleId,
                             @Param("versionIncrement") int versionIncrement,
                             @Param("expectedLockVersion") long expectedLockVersion,
                             @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE article
            SET is_deleted=1, delete_time=#{deletedAt}, visibility_state=#{visibilityState},
                lock_version=lock_version+1, update_time=#{deletedAt}
            WHERE id=#{articleId} AND author_id=#{authorId} AND is_deleted=0
              AND lock_version=#{expectedLockVersion}
            """)
    int recycleLocked(@Param("articleId") long articleId,
                      @Param("authorId") long authorId,
                      @Param("visibilityState") String visibilityState,
                      @Param("expectedLockVersion") long expectedLockVersion,
                      @Param("deletedAt") LocalDateTime deletedAt);

    @Update("""
            UPDATE article
            SET is_deleted=0, delete_time=NULL, visibility_state=#{visibilityState},
                lock_version=lock_version+1, update_time=#{updatedAt}
            WHERE id=#{articleId} AND author_id=#{authorId} AND is_deleted=1
              AND (visibility_state IS NULL OR visibility_state<>'PURGED')
              AND lock_version=#{expectedLockVersion}
            """)
    int restoreLocked(@Param("articleId") long articleId,
                      @Param("authorId") long authorId,
                      @Param("visibilityState") String visibilityState,
                      @Param("expectedLockVersion") long expectedLockVersion,
                      @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE article
            SET is_deleted=1, visibility_state='PURGED',
                lock_version=lock_version+1, update_time=#{updatedAt}
            WHERE id=#{articleId} AND author_id=#{authorId} AND is_deleted=1
              AND (visibility_state IS NULL OR visibility_state<>'PURGED')
              AND lock_version=#{expectedLockVersion}
            """)
    int purgeLocked(@Param("articleId") long articleId,
                    @Param("authorId") long authorId,
                    @Param("expectedLockVersion") long expectedLockVersion,
                    @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
            SELECT id FROM article
            WHERE is_deleted=1 AND delete_time<=#{expiredBefore}
              AND (visibility_state IS NULL OR visibility_state<>'PURGED')
            ORDER BY id LIMIT #{limit}
            """)
    List<Long> selectExpiredArticleIds(@Param("expiredBefore") LocalDateTime expiredBefore,
                                       @Param("limit") int limit);

    @Update("UPDATE article SET status = #{targetStatus} " +
            "WHERE id = #{articleId} AND status = 2 AND is_deleted = 0")
    int updateModerationStatusIfPending(@Param("articleId") Long articleId,
                                        @Param("targetStatus") int targetStatus);
}
