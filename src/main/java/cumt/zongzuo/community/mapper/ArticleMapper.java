package cumt.zongzuo.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    @Select("SELECT IFNULL(SUM(like_count), 0) FROM article WHERE author_id = #{authorId}")
    Long sumLikesByAuthorId(Long authorId);

    // 【新增】查询指定收藏夹下的所有文章
    @Select("SELECT a.* FROM article a " +
            "LEFT JOIN favorite f ON a.id = f.article_id " +
            "WHERE f.folder_id = #{folderId} " +
            "ORDER BY f.create_time DESC")
    List<Article> selectArticlesByFolderId(Long folderId);

    List<Article> selectPublishedByFollowedAuthors(@Param("userId") Long userId,
                                                   @Param("limit") int limit);

    List<Article> selectPublishedByTagIds(@Param("tagIds") Collection<Long> tagIds,
                                          @Param("limit") int limit);

    List<Article> selectPublishedHotFresh(@Param("limit") int limit);

    List<Article> selectPublishedByIds(@Param("articleIds") Collection<Long> articleIds);
}
