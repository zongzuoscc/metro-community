package cumt.zongzuo.community.launcher;

import cumt.zongzuo.community.CommunityApplication;
import cumt.zongzuo.community.article.config.ArticleRevisionProperties;
import cumt.zongzuo.community.article.migration.StageBMigrationProperties;
import cumt.zongzuo.community.article.rollout.StageBRolloutBuildProperties;
import cumt.zongzuo.community.config.SecurityProperties;
import cumt.zongzuo.community.config.WebSocketProperties;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * 三个运行角色共享的 Spring 容器底座。
 *
 * <p>这里故意排除旧的单体启动类和 launcher 包，避免组件扫描再次导入
 * {@link CommunityApplication} 上的全局调度开关，也避免三个启动器互相注册。
 * Worker 会在自己的显式配置中开启调度，Backend 与 Agent 默认不执行后台任务。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile({"backend-service", "agent-service", "worker-service"})
@EnableAutoConfiguration(exclude = {
        UserDetailsServiceAutoConfiguration.class,
        OllamaApiAutoConfiguration.class,
        OllamaChatAutoConfiguration.class,
        OllamaEmbeddingAutoConfiguration.class
})
@ComponentScan(basePackages = "cumt.zongzuo.community", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = CommunityApplication.class),
        @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "cumt\\.zongzuo\\.community\\.launcher\\..*")
})
@MapperScan(value = {"cumt.zongzuo.community.mapper", "cumt.zongzuo.community.recommendation.mapper",
        "cumt.zongzuo.community.event", "cumt.zongzuo.community.article.persistence",
        "cumt.zongzuo.community.ai.moderation.revision",
        "cumt.zongzuo.community.ai.agent.turn",
        "cumt.zongzuo.community.ai.agent.memory",
        "cumt.zongzuo.community.ai.agent.history",
        "cumt.zongzuo.community.ai.userprovider"}, annotationClass = Mapper.class)
@EnableElasticsearchRepositories(basePackages = "cumt.zongzuo.community.repository")
@EnableConfigurationProperties({SecurityProperties.class, WebSocketProperties.class,
        RecommendationProperties.class, ArticleRevisionProperties.class,
        StageBMigrationProperties.class, StageBRolloutBuildProperties.class})
class CommunityServiceRuntimeConfiguration {

    /**
     * 该方法必须是 static，使入口裁剪器在普通业务 Bean 实例化前完成注册。
     * 角色来自每个启动器写入的默认属性，部署环境仍可用同名配置显式覆盖。
     */
    @Bean
    static CommunityRoleBeanBoundary communityRoleBeanBoundary(
            org.springframework.core.env.Environment environment) {
        String configuredRole = environment.getRequiredProperty("metro.service.role");
        CommunityServiceRole role = CommunityServiceRole.valueOf(
                configuredRole.trim().toUpperCase(java.util.Locale.ROOT));
        return new CommunityRoleBeanBoundary(role);
    }
}
