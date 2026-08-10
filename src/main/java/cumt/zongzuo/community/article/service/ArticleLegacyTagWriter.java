package cumt.zongzuo.community.article.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cumt.zongzuo.community.entity.ArticleTag;
import cumt.zongzuo.community.entity.Tag;
import cumt.zongzuo.community.mapper.ArticleTagMapper;
import cumt.zongzuo.community.mapper.TagMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
class ArticleLegacyTagWriter {

    private final ArticleTagMapper articleTagMapper;
    private final TagMapper tagMapper;

    ArticleLegacyTagWriter(ArticleTagMapper articleTagMapper, TagMapper tagMapper) {
        this.articleTagMapper = articleTagMapper;
        this.tagMapper = tagMapper;
    }

    void replace(long articleId, List<String> canonicalTagNames) {
        articleTagMapper.delete(new QueryWrapper<ArticleTag>().eq("article_id", articleId));
        for (String tagName : canonicalTagNames) {
            Tag tag = tagMapper.selectOne(new QueryWrapper<Tag>().eq("name", tagName));
            if (tag == null) {
                tag = new Tag();
                tag.setName(tagName);
                tag.setArticleCount(1);
                tag.setCreateTime(LocalDateTime.now());
                tagMapper.insert(tag);
            }
            ArticleTag link = new ArticleTag();
            link.setArticleId(articleId);
            link.setTagId(tag.getId());
            articleTagMapper.insert(link);
        }
    }
}
