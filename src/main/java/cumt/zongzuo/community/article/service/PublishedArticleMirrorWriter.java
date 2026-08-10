package cumt.zongzuo.community.article.service;

import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.article.model.ArticleRevision;
import cumt.zongzuo.community.mapper.ArticleMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public final class PublishedArticleMirrorWriter {

    private final ArticleMapper articleMapper;
    private final ArticleLegacyTagWriter legacyTagWriter;
    private final ArticleRevisionIntegrityVerifier integrityVerifier;

    public PublishedArticleMirrorWriter(ArticleMapper articleMapper,
                                        ArticleLegacyTagWriter legacyTagWriter,
                                        ArticleRevisionIntegrityVerifier integrityVerifier) {
        this.articleMapper = articleMapper;
        this.legacyTagWriter = legacyTagWriter;
        this.integrityVerifier = integrityVerifier;
    }

    public void publishLocked(long articleId, ArticleRevision revision,
                              long expectedArticleVersion, LocalDateTime decidedAt) {
        ArticleRevisionIntegrityVerifier.VerifiedRevision verified = integrityVerifier.verify(revision)
                .orElseThrow(AiApiException::optimisticLockConflict);
        if (articleMapper.approveRevisionCas(articleId, revision.getId(), revision.getContentHash(),
                revision.getTitle(), revision.getSummary(), revision.getBodyMarkdown(),
                revision.getCover(), expectedArticleVersion, decidedAt) != 1) {
            throw AiApiException.optimisticLockConflict();
        }
        legacyTagWriter.replace(articleId, verified.tags());
    }
}
