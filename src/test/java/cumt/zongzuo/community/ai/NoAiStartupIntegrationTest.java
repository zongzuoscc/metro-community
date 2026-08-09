package cumt.zongzuo.community.ai;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.provider.AiChatGateway;
import cumt.zongzuo.community.ai.provider.DisabledAiChatGateway;
import cumt.zongzuo.community.ai.provider.DisabledEmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NoAiStartupIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private WebEndpointsSupplier webEndpointsSupplier;

    @Test
    void startsAsServletWithoutProviderModelsWhenAiIsOffAndKeysAreEmpty() {
        assertThat(context.getBeansOfType(DispatcherServlet.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(DeepSeekChatModel.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OllamaEmbeddingModel.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DeepSeekApi.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OllamaApi.class)).isEmpty();
        assertThat(context.getBean(AiChatGateway.class)).isInstanceOf(DisabledAiChatGateway.class);
        assertThat(context.getBean(EmbeddingGateway.class)).isInstanceOf(DisabledEmbeddingGateway.class);
        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("none");
        assertThat(environment.getProperty("spring.ai.retry.max-attempts")).isEqualTo("1");
        assertThat(environment.getProperty("metro.ai.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("metro.ai.agent.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("metro.ai.memory.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("metro.ai.writing.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("metro.ai.moderation.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("metro.ai.embedding.enabled")).isEqualTo("false");

        ResponseEntity<String> response = restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void exposesOnlyGetHealthFromTheActuatorSurface() {
        Set<String> exposedEndpointIds = webEndpointsSupplier.getEndpoints().stream()
                .map(ExposableWebEndpoint::getEndpointId)
                .map(Object::toString)
                .collect(Collectors.toSet());
        ResponseEntity<String> healthComponent = restTemplate.getForEntity(
                url("/actuator/health/db"), String.class);
        ResponseEntity<String> actuatorRoot = restTemplate.getForEntity(
                url("/actuator"), String.class);
        ResponseEntity<String> prometheus = restTemplate.getForEntity(
                url("/actuator/prometheus"), String.class);
        ResponseEntity<String> postHealth = restTemplate.exchange(
                url("/actuator/health"), HttpMethod.POST, null, String.class);

        assertThat(exposedEndpointIds).containsExactly("health");
        assertThat(healthComponent.getStatusCode().value()).isNotIn(401, 403);
        assertThat(actuatorRoot.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(prometheus.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(postHealth.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void publicArticleEndpointStillWorksWhenAllAiCapabilitiesAreOff() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/article/hot"), String.class);

        assertThat(response.getStatusCode().value()).isNotIn(401, 403);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"code\":200", "\"data\"");
    }
}
