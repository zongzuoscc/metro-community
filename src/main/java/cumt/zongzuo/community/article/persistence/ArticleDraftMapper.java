package cumt.zongzuo.community.article.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import cumt.zongzuo.community.article.model.ArticleDraft;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArticleDraftMapper extends BaseMapper<ArticleDraft> {
}
