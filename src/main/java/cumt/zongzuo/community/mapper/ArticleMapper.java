package cumt.zongzuo.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    @Select("SELECT IFNULL(SUM(like_count), 0) FROM article WHERE author_id = #{authorId}")
    Long sumLikesByAuthorId(Long authorId);
}