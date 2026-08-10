package cumt.zongzuo.community.article.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.article.model.ArticleDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ArticleDraftMapper extends BaseMapper<ArticleDraft> {

    @Select("SELECT * FROM article_draft WHERE article_id=#{articleId} AND user_id=#{userId} FOR UPDATE")
    ArticleDraft selectOwnerDraftForUpdate(@Param("articleId") long articleId,
                                           @Param("userId") long userId);

    @Update("""
            UPDATE article_draft
            SET draft_version=draft_version+1, title=#{title}, summary=#{summary},
                body_markdown=#{bodyMarkdown}, body_plain=#{bodyPlain}, cover=#{cover},
                tags_json=#{tagsJson}, content_hash=#{contentHash}, updated_at=#{updatedAt},
                lock_version=lock_version+1
            WHERE article_id=#{articleId} AND user_id=#{userId}
              AND draft_version=#{expectedDraftVersion} AND lock_version=#{expectedLockVersion}
            """)
    int updateOwnerDraftCas(@Param("articleId") long articleId,
                            @Param("userId") long userId,
                            @Param("expectedDraftVersion") long expectedDraftVersion,
                            @Param("expectedLockVersion") long expectedLockVersion,
                            @Param("title") String title,
                            @Param("summary") String summary,
                            @Param("bodyMarkdown") String bodyMarkdown,
                            @Param("bodyPlain") String bodyPlain,
                            @Param("cover") String cover,
                            @Param("tagsJson") String tagsJson,
                            @Param("contentHash") String contentHash,
                            @Param("updatedAt") LocalDateTime updatedAt);
}
