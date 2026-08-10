package cumt.zongzuo.community.article.config;

import cumt.zongzuo.community.article.rollout.ArticleRevisionBuildIdentity;
import cumt.zongzuo.community.article.rollout.StageBRolloutStartupGate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

@Component
public final class ConfiguredArticleRevisionModeResolver implements ArticleRevisionModeResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfiguredArticleRevisionModeResolver.class);

    private final ArticleRevisionMode startupMode;

    public ConfiguredArticleRevisionModeResolver(ArticleRevisionProperties properties,
                                                 StageBRolloutStartupGate startupGate,
                                                 ArticleRevisionBuildIdentity buildIdentity) {
        this.startupMode = Objects.requireNonNull(properties.getRevisionMode(), "revisionMode");
        startupGate.verify(startupMode, buildIdentity);
        LOGGER.info("Article revision mode frozen at startup: {}", startupMode);
    }

    @Override
    public ArticleRevisionMode current() {
        return startupMode;
    }
}
