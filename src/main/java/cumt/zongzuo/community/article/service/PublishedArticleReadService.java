package cumt.zongzuo.community.article.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Collection;

@Service
public class PublishedArticleReadService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final ArticleMapper articleMapper;
    private final ArticleRevisionModeResolver modeResolver;
    private final ObjectMapper objectMapper;

    public PublishedArticleReadService(ArticleMapper articleMapper,
                                       ArticleRevisionModeResolver modeResolver,
                                       ObjectMapper objectMapper) {
        this.articleMapper = articleMapper;
        this.modeResolver = modeResolver;
        this.objectMapper = objectMapper;
    }

    public Article findById(long articleId) {
        Article article = pointerReadsEnabled()
                ? articleMapper.selectPublicById(articleId)
                : articleMapper.selectLegacyPublicById(articleId);
        hydrateRevisionTags(article);
        return article;
    }

    public List<Article> findHot(int limit) {
        return hydrate(pointerReadsEnabled()
                ? articleMapper.selectPublishedHot(limit)
                : articleMapper.selectLegacyPublishedHot(limit));
    }

    public List<Article> findHotRank(int limit) {
        return hydrate(pointerReadsEnabled()
                ? articleMapper.selectPublishedHotRank(limit)
                : articleMapper.selectLegacyPublishedHotRank(limit));
    }

    public List<Article> findChronological(LocalDateTime beforeCreateTime, Long beforeId, int limit) {
        return hydrate(pointerReadsEnabled()
                ? articleMapper.selectPublishedChronological(beforeCreateTime, beforeId, limit)
                : articleMapper.selectLegacyPublishedChronological(beforeCreateTime, beforeId, limit));
    }

    public List<Long> findChronologicalIds(int limit) {
        return pointerReadsEnabled()
                ? articleMapper.selectPublishedChronologicalIds(limit)
                : articleMapper.selectLegacyPublishedChronologicalIds(limit);
    }

    public List<Article> findByIds(Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return List.of();
        }
        return hydrate(pointerReadsEnabled()
                ? articleMapper.selectPublishedByIds(articleIds)
                : articleMapper.selectLegacyPublishedByIds(articleIds));
    }

    public Page<Article> findByAuthor(long authorId, int pageNo, int pageSize) {
        long offset = (long) (pageNo - 1) * pageSize;
        List<Article> records = hydrate(pointerReadsEnabled()
                ? articleMapper.selectPublishedByAuthor(authorId, offset, pageSize)
                : articleMapper.selectLegacyPublishedByAuthor(authorId, offset, pageSize));
        long total = pointerReadsEnabled()
                ? articleMapper.countPublishedByAuthor(authorId)
                : articleMapper.countLegacyPublishedByAuthor(authorId);
        return page(pageNo, pageSize, records, total);
    }

    public Page<Article> findByFollowing(long userId, int pageNo, int pageSize) {
        long offset = (long) (pageNo - 1) * pageSize;
        List<Article> records = hydrate(pointerReadsEnabled()
                ? articleMapper.selectPublishedByFollowing(userId, offset, pageSize)
                : articleMapper.selectLegacyPublishedByFollowing(userId, offset, pageSize));
        long total = pointerReadsEnabled()
                ? articleMapper.countPublishedByFollowing(userId)
                : articleMapper.countLegacyPublishedByFollowing(userId);
        return page(pageNo, pageSize, records, total);
    }

    public Page<Article> findPage(int pageNo, int pageSize) {
        long offset = (long) (pageNo - 1) * pageSize;
        List<Article> records = hydrate(pointerReadsEnabled()
                ? articleMapper.selectPublishedPage(offset, pageSize)
                : articleMapper.selectLegacyPublishedPage(offset, pageSize));
        long total = pointerReadsEnabled()
                ? articleMapper.countPublished()
                : articleMapper.countLegacyPublished();
        return page(pageNo, pageSize, records, total);
    }

    public List<Article> findHotSince(LocalDateTime since, int limit) {
        return hydrate(pointerReadsEnabled()
                ? articleMapper.selectPublishedHotSince(since, limit)
                : articleMapper.selectLegacyPublishedHotSince(since, limit));
    }

    public List<Article> findFollowedCandidates(long userId, long excludedAuthorId,
                                                Collection<Long> shownArticleIds, int limit) {
        return hydrate(pointerReadsEnabled()
                ? articleMapper.selectPublishedByFollowedAuthors(
                        userId, excludedAuthorId, shownArticleIds, limit)
                : articleMapper.selectLegacyPublishedByFollowedAuthors(
                        userId, excludedAuthorId, shownArticleIds, limit));
    }

    public List<Article> findHotFreshCandidates(long excludedAuthorId,
                                                Collection<Long> shownArticleIds, int limit) {
        return hydrate(pointerReadsEnabled()
                ? articleMapper.selectPublishedHotFresh(excludedAuthorId, shownArticleIds, limit)
                : articleMapper.selectLegacyPublishedHotFresh(excludedAuthorId, shownArticleIds, limit));
    }

    public List<Article> findTagCandidates(Collection<String> tagNames,
                                           Collection<Long> legacyTagIds,
                                           long excludedAuthorId,
                                           Collection<Long> shownArticleIds,
                                           int limit) {
        if (pointerReadsEnabled()) {
            if (tagNames == null || tagNames.isEmpty()) {
                return List.of();
            }
            return hydrate(articleMapper.selectPublishedByTagNames(
                    tagNames, excludedAuthorId, shownArticleIds, limit));
        }
        if (legacyTagIds == null || legacyTagIds.isEmpty()) {
            return List.of();
        }
        return articleMapper.selectLegacyPublishedByTagIds(
                legacyTagIds, excludedAuthorId, shownArticleIds, limit);
    }

    public void hydrateRevisionTags(Article article) {
        if (article == null) {
            return;
        }
        if (article.getRevisionTagsJson() == null) {
            if (pointerReadsEnabled()) {
                // Revision tags are authoritative after pointer read activation;
                // never fall back to the mutable legacy article_tag mirror.
                article.setTagList(List.of());
            }
            return;
        }
        try {
            article.setTagList(objectMapper.readValue(article.getRevisionTagsJson(), STRING_LIST));
        } catch (Exception invalidStoredTags) {
            throw new IllegalStateException("Invalid revision tags JSON", invalidStoredTags);
        }
    }

    public void hydrateRevisionTags(List<Article> articles) {
        if (articles != null) {
            articles.forEach(this::hydrateRevisionTags);
        }
    }

    public boolean pointerReadsEnabled() {
        ArticleRevisionMode mode = modeResolver.current();
        return mode == ArticleRevisionMode.POINTER_READ || mode == ArticleRevisionMode.CUTOVER;
    }

    private List<Article> hydrate(List<Article> articles) {
        List<Article> result = articles == null ? List.of() : articles;
        hydrateRevisionTags(result);
        return result;
    }

    private static Page<Article> page(int pageNo, int pageSize, List<Article> records, long total) {
        Page<Article> result = new Page<>(pageNo, pageSize, total);
        result.setRecords(records);
        return result;
    }
}
