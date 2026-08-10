package cumt.zongzuo.community.article.service;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.ArticleTagMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

@Service
public class AuthorArticleReadService {

    private final ArticleMapper articleMapper;
    private final ArticleTagMapper articleTagMapper;
    private final ArticleRevisionModeResolver modeResolver;
    private final PublishedArticleReadService publishedReads;

    public AuthorArticleReadService(ArticleMapper articleMapper,
                                    ArticleTagMapper articleTagMapper,
                                    ArticleRevisionModeResolver modeResolver,
                                    PublishedArticleReadService publishedReads) {
        this.articleMapper = articleMapper;
        this.articleTagMapper = articleTagMapper;
        this.modeResolver = modeResolver;
        this.publishedReads = publishedReads;
    }

    public Article findForEdit(long articleId, long authorId) {
        ArticleRevisionMode mode = modeResolver.current();
        if (mode == ArticleRevisionMode.POINTER_READ) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ARTICLE_CUTOVER_IN_PROGRESS");
        }
        Article article = mode == ArticleRevisionMode.CUTOVER
                ? articleMapper.selectOwnerDraftById(articleId, authorId)
                : articleMapper.selectOwnerLegacyById(articleId, authorId);
        if (article == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文章不存在");
        }
        if (mode == ArticleRevisionMode.CUTOVER) {
            publishedReads.hydrateRevisionTags(article);
        } else {
            List<String> legacyTags = articleTagMapper.selectTagNamesByArticleId(articleId);
            article.setTagList(legacyTags == null ? List.of() : legacyTags);
        }
        return article;
    }

    public List<Article> findDrafts(long authorId) {
        ensureAuthorReadsAvailable();
        List<Article> articles = modeResolver.current() == ArticleRevisionMode.CUTOVER
                ? articleMapper.selectOwnerDirtyDrafts(authorId, 0, Integer.MAX_VALUE)
                : articleMapper.selectOwnerLegacyDrafts(authorId, 0, Integer.MAX_VALUE);
        publishedReads.hydrateRevisionTags(articles);
        return articles;
    }

    public long countDrafts(long authorId) {
        ensureAuthorReadsAvailable();
        return modeResolver.current() == ArticleRevisionMode.CUTOVER
                ? articleMapper.countOwnerDirtyDrafts(authorId)
                : articleMapper.countOwnerLegacyDrafts(authorId);
    }

    public Page<Article> findAll(long authorId, int pageNo, int pageSize) {
        ensureAuthorReadsAvailable();
        long offset = (long) (pageNo - 1) * pageSize;
        boolean cutover = modeResolver.current() == ArticleRevisionMode.CUTOVER;
        List<Article> records = cutover
                ? articleMapper.selectOwnerAllDrafts(authorId, offset, pageSize)
                : articleMapper.selectOwnerLegacyAll(authorId, offset, pageSize);
        long total = cutover
                ? articleMapper.countOwnerAllDrafts(authorId)
                : articleMapper.countOwnerLegacyAll(authorId);
        publishedReads.hydrateRevisionTags(records);
        Page<Article> page = new Page<>(pageNo, pageSize, total);
        page.setRecords(records);
        return page;
    }

    public List<Article> findRecycleBin(long authorId) {
        ensureAuthorReadsAvailable();
        List<Article> articles = modeResolver.current() == ArticleRevisionMode.CUTOVER
                ? articleMapper.selectOwnerDraftRecycle(authorId)
                : articleMapper.selectOwnerLegacyRecycle(authorId);
        publishedReads.hydrateRevisionTags(articles);
        return articles;
    }

    public Page<Article> findPending(int pageNo, int pageSize) {
        long offset = (long) (pageNo - 1) * pageSize;
        boolean revisionReads = publishedReads.pointerReadsEnabled();
        List<Article> records = revisionReads
                ? articleMapper.selectPendingRevisions(offset, pageSize)
                : articleMapper.selectLegacyPending(offset, pageSize);
        long total = revisionReads
                ? articleMapper.countPendingRevisions()
                : articleMapper.countLegacyPending();
        publishedReads.hydrateRevisionTags(records);
        Page<Article> page = new Page<>(pageNo, pageSize, total);
        page.setRecords(records);
        return page;
    }

    private void ensureAuthorReadsAvailable() {
        if (modeResolver.current() == ArticleRevisionMode.POINTER_READ) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ARTICLE_CUTOVER_IN_PROGRESS");
        }
    }
}
