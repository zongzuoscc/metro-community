package cumt.zongzuo.community.article.rollout;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;

@FunctionalInterface
public interface StageBRolloutStartupGate {

    void verify(ArticleRevisionMode configuredMode, ArticleRevisionBuildIdentity buildIdentity);
}
