package cumt.zongzuo.community;

import cumt.zongzuo.community.config.SecurityProperties;
import cumt.zongzuo.community.config.WebSocketProperties;
import cumt.zongzuo.community.article.config.ArticleRevisionProperties;
import cumt.zongzuo.community.article.migration.StageBMigrationProperties;
import cumt.zongzuo.community.article.rollout.StageBRolloutBuildProperties;
import cumt.zongzuo.community.article.rollout.StageBRolloutOperatorApplication;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        UserDetailsServiceAutoConfiguration.class,
        OllamaApiAutoConfiguration.class,
        OllamaChatAutoConfiguration.class,
        OllamaEmbeddingAutoConfiguration.class
})
@MapperScan(value = {"cumt.zongzuo.community.mapper", "cumt.zongzuo.community.recommendation.mapper",
        "cumt.zongzuo.community.event", "cumt.zongzuo.community.article.persistence",
        "cumt.zongzuo.community.ai.moderation.revision",
        "cumt.zongzuo.community.ai.agent.turn",
        "cumt.zongzuo.community.ai.agent.memory",
        "cumt.zongzuo.community.ai.agent.history",
        "cumt.zongzuo.community.ai.userprovider",
        "cumt.zongzuo.community.account"}, annotationClass = Mapper.class)
@EnableScheduling
@EnableConfigurationProperties({SecurityProperties.class, WebSocketProperties.class,
        RecommendationProperties.class, ArticleRevisionProperties.class,
        StageBMigrationProperties.class, StageBRolloutBuildProperties.class})
public class CommunityApplication {

	public static void main(String[] args) {
		if (StageBRolloutOperatorApplication.isRequested(args)) {
			StageBRolloutOperatorApplication.run(args);
			return;
		}
		SpringApplication.run(CommunityApplication.class, args);
	}

}
