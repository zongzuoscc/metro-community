package cumt.zongzuo.community.article.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cumt.zongzuo.community.entity.ArticleTag;
import cumt.zongzuo.community.mapper.ArticleTagMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class ArticleLegacyTagWriter {

    private final ArticleTagMapper articleTagMapper;
    private final ExactArticleTagStore tagStore;

    ArticleLegacyTagWriter(ArticleTagMapper articleTagMapper, ExactArticleTagStore tagStore) {
        this.articleTagMapper = articleTagMapper;
        this.tagStore = tagStore;
    }

    void replace(long articleId, List<String> canonicalTagNames) {
        articleTagMapper.delete(new QueryWrapper<ArticleTag>().eq("article_id", articleId));
        for (String tagName : canonicalTagNames) {
            long tagId = tagStore.getOrCreate(tagName);
            ArticleTag link = new ArticleTag();
            link.setArticleId(articleId);
            link.setTagId(tagId);
            articleTagMapper.insert(link);
        }
    }
}
