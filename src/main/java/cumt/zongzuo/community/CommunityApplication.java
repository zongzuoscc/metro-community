package cumt.zongzuo.community;

import cumt.zongzuo.community.config.SecurityProperties;
import cumt.zongzuo.community.config.WebSocketProperties;
import cumt.zongzuo.community.article.config.ArticleRevisionProperties;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
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
        DeepSeekChatAutoConfiguration.class,
        OllamaApiAutoConfiguration.class,
        OllamaChatAutoConfiguration.class,
        OllamaEmbeddingAutoConfiguration.class
})
@MapperScan(value = {"cumt.zongzuo.community.mapper", "cumt.zongzuo.community.recommendation.mapper",
        "cumt.zongzuo.community.event", "cumt.zongzuo.community.article.persistence",
        "cumt.zongzuo.community.ai.moderation.revision"}, annotationClass = Mapper.class)
@EnableScheduling
@EnableConfigurationProperties({SecurityProperties.class, WebSocketProperties.class, RecommendationProperties.class,
        ArticleRevisionProperties.class})
public class CommunityApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommunityApplication.class, args);
	}

}
