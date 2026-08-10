package cumt.zongzuo.community.article.rollout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StageBRolloutBuildConfiguration {

    @Bean
    @ConditionalOnMissingBean(ArticleRevisionBuildIdentity.class)
    ArticleRevisionBuildIdentity articleRevisionBuildIdentity(
            StageBRolloutBuildProperties properties) {
        return new ArticleRevisionBuildIdentity(
                properties.getBinaryGeneration(),
                properties.getSchemaGeneration(),
                properties.getDigest());
    }
}
