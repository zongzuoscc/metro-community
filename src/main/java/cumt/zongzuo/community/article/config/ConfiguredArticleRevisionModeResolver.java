package cumt.zongzuo.community.article.config;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

@Component
public final class ConfiguredArticleRevisionModeResolver implements ArticleRevisionModeResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfiguredArticleRevisionModeResolver.class);

    private final ArticleRevisionMode startupMode;

    public ConfiguredArticleRevisionModeResolver(ArticleRevisionProperties properties) {
        this.startupMode = Objects.requireNonNull(properties.getRevisionMode(), "revisionMode");
        LOGGER.info("Article revision mode frozen at startup: {}", startupMode);
    }

    @Override
    public ArticleRevisionMode current() {
        return startupMode;
    }
}
