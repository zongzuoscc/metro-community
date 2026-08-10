package cumt.zongzuo.community.article.projection;

import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public interface ArticleProjectionSource {

    Snapshot loadCurrent(long articleId, long fallbackLifecycleEpoch, long fallbackVersion);

    record Snapshot(ArticleDoc document, String resultHash,
                    long lifecycleEpoch, long projectionVersion) {
        public Snapshot {
            if (resultHash == null || !resultHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("projection result hash must be lowercase SHA-256 hex");
            }
            if (lifecycleEpoch < 0 || projectionVersion < 0) {
                throw new IllegalArgumentException("projection source version must be non-negative");
            }
        }

        public boolean present() {
            return document != null;
        }
    }
}

@Component
class MysqlArticleProjectionSource implements ArticleProjectionSource {

    private final ArticleMapper articleMapper;

    MysqlArticleProjectionSource(ArticleMapper articleMapper) {
        this.articleMapper = articleMapper;
    }

    @Override
    public Snapshot loadCurrent(long articleId, long fallbackLifecycleEpoch, long fallbackVersion) {
        if (articleId <= 0) {
            throw new IllegalArgumentException("articleId must be positive");
        }
        // Deliberately bypass PublishedArticleReadService: projection truth must never
        // change with the rollout read mode or fall back to mutable legacy columns.
        Article article = articleMapper.selectPublicById(articleId);
        if (article == null) {
            Article cursor = articleMapper.selectProjectionCursorById(articleId);
            long lifecycleEpoch = cursor == null ? fallbackLifecycleEpoch : cursor.getLifecycleEpoch();
            long projectionVersion = cursor == null ? fallbackVersion : cursor.getLockVersion();
            return new Snapshot(null, tombstoneHash(articleId), lifecycleEpoch, projectionVersion);
        }
        if (article.getPublishedRevisionId() == null
                || article.getContentHash() == null
                || !article.getContentHash().matches("[0-9a-f]{64}")
                || article.getLifecycleEpoch() == null || article.getLockVersion() == null) {
            throw new IllegalStateException("current published pointer has invalid immutable identity");
        }
        ArticleDoc document = new ArticleDoc();
        document.setId(article.getId());
        document.setRevisionId(article.getPublishedRevisionId());
        document.setContentHash(article.getContentHash());
        document.setTitle(article.getTitle());
        document.setContent(article.getContent());
        document.setSummary(article.getSummary());
        document.setCover(article.getCover());
        document.setAuthorId(article.getAuthorId() == null ? 0L : article.getAuthorId());
        document.setViewCount(article.getViewCount() == null ? 0 : article.getViewCount());
        document.setLikeCount(article.getLikeCount() == null ? 0 : article.getLikeCount());
        document.setCommentCount(article.getCommentCount() == null ? 0 : article.getCommentCount());
        document.setCollectCount(article.getCollectCount() == null ? 0 : article.getCollectCount());
        document.setCreateTime(article.getCreateTime());
        return new Snapshot(document, article.getContentHash(),
                article.getLifecycleEpoch(), article.getLockVersion());
    }

    private static String tombstoneHash(long articleId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    ("article-projection-tombstone-v1\n" + articleId)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
