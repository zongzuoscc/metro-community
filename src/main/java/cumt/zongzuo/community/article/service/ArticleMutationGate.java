package cumt.zongzuo.community.article.service;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public final class ArticleMutationGate {

    private final ArticleRevisionModeResolver modeResolver;

    public ArticleMutationGate(ArticleRevisionModeResolver modeResolver) {
        this.modeResolver = modeResolver;
    }

    public ArticleRevisionMode requireArticleWriteAllowed() {
        ArticleRevisionMode mode = modeResolver.current();
        if (mode == ArticleRevisionMode.VERIFY_FENCE || mode == ArticleRevisionMode.POINTER_READ) {
            throw cutoverInProgress();
        }
        return mode;
    }

    public ArticleRevisionMode requireRevisionWriteMode() {
        ArticleRevisionMode mode = requireArticleWriteAllowed();
        if (mode == ArticleRevisionMode.LEGACY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVISION_WRITE_NOT_ENABLED");
        }
        return mode;
    }

    public ArticleRevisionMode requirePublishedRevisionEditAllowed() {
        ArticleRevisionMode mode = requireArticleWriteAllowed();
        if (mode != ArticleRevisionMode.CUTOVER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PUBLISHED_ARTICLE_EDIT_REQUIRES_CUTOVER");
        }
        return mode;
    }

    public ArticleRevisionMode requireLegacyModerationDecisionAllowed() {
        ArticleRevisionMode mode = modeResolver.current();
        return switch (mode) {
            case LEGACY -> mode;
            case SHADOW, CUTOVER -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "LEGACY_MODERATION_DISABLED");
            case VERIFY_FENCE, POINTER_READ -> throw cutoverInProgress();
        };
    }

    public ArticleRevisionMode requireRevisionModerationDecisionAllowed() {
        ArticleRevisionMode mode = modeResolver.current();
        return switch (mode) {
            case SHADOW, CUTOVER -> mode;
            case LEGACY -> throw AiApiException.revisionModerationDisabled();
            case VERIFY_FENCE, POINTER_READ -> throw AiApiException.articleCutoverInProgress();
        };
    }

    private static ResponseStatusException cutoverInProgress() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "ARTICLE_CUTOVER_IN_PROGRESS");
    }
}
