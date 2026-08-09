package cumt.zongzuo.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /**
     * 【生产级优化】通过 SQL JOIN 直接查询文章对应的标签列表
     * 避免在 Java 代码中循环查库
     */
    List<Tag> selectTagsByArticleId(@Param("articleId") Long articleId);

    List<Long> selectIdsByNames(@Param("names") Collection<String> names);
}
