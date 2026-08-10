package cumt.zongzuo.community.article.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "metro.article")
public class ArticleRevisionProperties {

    private ArticleRevisionMode revisionMode = ArticleRevisionMode.LEGACY;

    public ArticleRevisionMode getRevisionMode() {
        return revisionMode;
    }

    public void setRevisionMode(ArticleRevisionMode revisionMode) {
        this.revisionMode = revisionMode == null ? ArticleRevisionMode.LEGACY : revisionMode;
    }
}
