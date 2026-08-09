package cumt.zongzuo.community;

import cumt.zongzuo.community.config.SecurityProperties;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan({"cumt.zongzuo.community.mapper", "cumt.zongzuo.community.recommendation.mapper"})
@EnableScheduling
@EnableConfigurationProperties({SecurityProperties.class, RecommendationProperties.class})
public class CommunityApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommunityApplication.class, args);
	}

}
