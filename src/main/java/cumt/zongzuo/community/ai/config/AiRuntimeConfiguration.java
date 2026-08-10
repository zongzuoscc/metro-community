package cumt.zongzuo.community.ai.config;

import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiCapabilityPolicyResolver;
import cumt.zongzuo.community.ai.runtime.AiMetrics;
import cumt.zongzuo.community.ai.runtime.AiQuotaService;
import cumt.zongzuo.community.ai.runtime.DefaultAiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.RedisAiQuotaService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetroAiProperties.class)
public class AiRuntimeConfiguration {

    @Bean
    AiCapabilityPolicyResolver aiCapabilityPolicyResolver(MetroAiProperties properties) {
        properties.validateModeration();
        return new AiCapabilityPolicyResolver(properties);
    }

    @Bean
    AiMetrics aiMetrics(MeterRegistry meterRegistry) {
        return new AiMetrics(meterRegistry);
    }

    @Bean
    AiQuotaService aiQuotaService(StringRedisTemplate redisTemplate,
                                  AiCapabilityPolicyResolver policyResolver,
                                  AiMetrics metrics,
                                  MetroAiProperties properties) {
        return new RedisAiQuotaService(redisTemplate, policyResolver, metrics,
                properties.getRuntime().getQuotaNamespace());
    }

    @Bean(destroyMethod = "close")
    AiCapabilityExecutor aiCapabilityExecutor(AiCapabilityPolicyResolver policyResolver,
                                              AiQuotaService quotaService,
                                              AiMetrics metrics,
                                              Clock clock) {
        return new DefaultAiCapabilityExecutor(policyResolver, quotaService, metrics, clock);
    }
}
