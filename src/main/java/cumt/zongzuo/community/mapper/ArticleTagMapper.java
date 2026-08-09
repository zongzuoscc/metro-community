package cumt.zongzuo.community.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.ArticleTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ArticleTagMapper extends BaseMapper<ArticleTag> {

    @Select("SELECT t.name FROM tag t JOIN article_tag at ON at.tag_id = t.id " +
            "WHERE at.article_id = #{articleId} ORDER BY t.id")
    List<String> selectTagNamesByArticleId(@Param("articleId") Long articleId);
}
