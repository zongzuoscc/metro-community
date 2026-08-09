package cumt.zongzuo.community.ai;

import cumt.zongzuo.community.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.DispatcherServlet;

import static org.assertj.core.api.Assertions.assertThat;

class NoAiStartupIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void startsAsServletWithoutProviderModelsWhenAiIsOffAndKeysAreEmpty() {
        assertThat(context.getBeansOfType(DispatcherServlet.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(DeepSeekChatModel.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OllamaEmbeddingModel.class)).isEmpty();
        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("none");
        assertThat(environment.getProperty("spring.ai.retry.max-attempts")).isEqualTo("1");

        ResponseEntity<String> response = restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void exposesOnlyGetHealthFromTheActuatorSurface() {
        ResponseEntity<String> healthComponent = restTemplate.getForEntity(
                url("/actuator/health/db"), String.class);
        ResponseEntity<String> actuatorRoot = restTemplate.getForEntity(
                url("/actuator"), String.class);
        ResponseEntity<String> prometheus = restTemplate.getForEntity(
                url("/actuator/prometheus"), String.class);
        ResponseEntity<String> postHealth = restTemplate.exchange(
                url("/actuator/health"), HttpMethod.POST, null, String.class);

        assertThat(healthComponent.getStatusCode().value()).isNotIn(401, 403);
        assertThat(actuatorRoot.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(prometheus.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(postHealth.getStatusCode().is2xxSuccessful()).isFalse();
    }
}
