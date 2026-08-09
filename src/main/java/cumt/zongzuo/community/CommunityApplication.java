package cumt.zongzuo.community;

import cumt.zongzuo.community.config.SecurityProperties;
import cumt.zongzuo.community.config.WebSocketProperties;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import org.mybatis.spring.annotation.MapperScan;
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
@MapperScan({"cumt.zongzuo.community.mapper", "cumt.zongzuo.community.recommendation.mapper"})
@EnableScheduling
@EnableConfigurationProperties({SecurityProperties.class, WebSocketProperties.class, RecommendationProperties.class})
public class CommunityApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommunityApplication.class, args);
	}

}
