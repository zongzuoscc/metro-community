package cumt.zongzuo.community.article.persistence;

import cumt.zongzuo.community.article.model.ArticleRevision;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ArticleRevisionMapper {

    @Insert("""
            INSERT INTO article_revision
                (article_id, revision_no, title, summary, body_markdown, body_plain, cover,
                 tags_json, content_hash, source_draft_version, created_by, created_at)
            VALUES
                (#{row.articleId}, #{row.revisionNo}, #{row.title}, #{row.summary},
                 #{row.bodyMarkdown}, #{row.bodyPlain}, #{row.cover}, #{row.tagsJson},
                 #{row.contentHash}, #{row.sourceDraftVersion}, #{row.createdBy}, #{row.createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insert(@Param("row") ArticleRevision row);

    @Select("SELECT * FROM article_revision WHERE id = #{id}")
    ArticleRevision selectById(@Param("id") long id);

    @Select("SELECT * FROM article_revision WHERE id = #{id} FOR UPDATE")
    ArticleRevision selectByIdForUpdate(@Param("id") long id);

    @Select("SELECT COALESCE(MAX(revision_no),0)+1 FROM article_revision WHERE article_id=#{articleId}")
    long selectNextRevisionNo(@Param("articleId") long articleId);

    @Select("SELECT * FROM article_revision WHERE article_id=#{articleId} ORDER BY revision_no")
    List<ArticleRevision> selectByArticleId(@Param("articleId") long articleId);
}
