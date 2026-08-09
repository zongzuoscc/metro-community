package cumt.zongzuo.community.ai.moderation;

public interface ManualReviewRoutingService {

    void routeLegacyArticle(Long articleId, String reasonCode);
}
