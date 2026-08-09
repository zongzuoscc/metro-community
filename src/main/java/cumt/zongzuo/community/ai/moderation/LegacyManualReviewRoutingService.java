package cumt.zongzuo.community.ai.moderation;

import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class LegacyManualReviewRoutingService implements ManualReviewRoutingService {

    private final ArticleMapper articleMapper;
    private final ModerationMetrics metrics;

    public LegacyManualReviewRoutingService(ArticleMapper articleMapper, ModerationMetrics metrics) {
        this.articleMapper = articleMapper;
        this.metrics = metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public void routeLegacyArticle(Long articleId, String reasonCode) {
        if (articleId == null || articleId <= 0) {
            throw new IllegalArgumentException("articleId must be positive");
        }
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");

        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new IllegalStateException("Legacy moderation article is not committed yet");
        }
        if (!Integer.valueOf(0).equals(article.getIsDeleted())) {
            metrics.record(ModerationMetrics.FallbackOutcome.IGNORED_DELETED, null);
            return;
        }
        if (!Integer.valueOf(2).equals(article.getStatus())) {
            metrics.record(ModerationMetrics.FallbackOutcome.IGNORED_NOT_PENDING, null);
            return;
        }

        metrics.record(ModerationMetrics.FallbackOutcome.MANUAL_PENDING, article.getCreateTime());
    }
}
