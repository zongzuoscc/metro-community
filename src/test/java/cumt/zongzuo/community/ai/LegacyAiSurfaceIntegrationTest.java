package cumt.zongzuo.community.ai;

import cumt.zongzuo.community.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyAiSurfaceIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 2_201L;

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    @Qualifier("articleAuditQueue")
    private Queue articleAuditQueue;

    @BeforeAll
    void createAuthenticatedUser() {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, role, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """, USER_ID, "legacy-ai-removal", "unused", "legacy-ai-removal@example.com", 0, 0);
    }

    @Test
    void legacyGetAiRoutesReturn404AndHaveNoHandlerMapping() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(USER_ID));
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> chat = restTemplate.exchange(
                url("/api/ai/chat?msg=hello"), HttpMethod.GET, request, String.class);
        ResponseEntity<String> summary = restTemplate.exchange(
                url("/api/ai/summarize/1"), HttpMethod.GET, request, String.class);

        assertThat(mappedPaths()).doesNotContain("/api/ai/chat", "/api/ai/summarize/{articleId}");
        assertThat(chat.getStatusCode().value()).isEqualTo(404);
        assertThat(summary.getStatusCode().value()).isEqualTo(404);
        assertThat(chat.getBody()).contains("\"code\":404", "\"msg\":\"资源不存在\"");
        assertThat(summary.getBody()).contains("\"code\":404", "\"msg\":\"资源不存在\"");
    }

    @Test
    void legacyAiBeansToolsAndFreeTextAuditSourcesAreAbsent() throws IOException {
        assertThat(context.containsBean("metroAiService")).isFalse();
        assertThat(context.containsBean("searchArticlesTool")).isFalse();

        assertThat(Path.of("src/main/java/cumt/zongzuo/community/controller/AiController.java")).doesNotExist();
        assertThat(Path.of("src/main/java/cumt/zongzuo/community/service/MetroAiService.java")).doesNotExist();
        assertThat(Path.of("src/main/java/cumt/zongzuo/community/config/AiToolConfig.java")).doesNotExist();
        assertThat(Path.of("src/main/java/cumt/zongzuo/community/mq/ArticleAuditConsumer.java")).doesNotExist();

        String productionSources;
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            productionSources = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::readSource)
                    .collect(Collectors.joining("\n"));
        }
        assertThat(productionSources).doesNotContain(
                "defaultFunctions(\"searchArticlesTool\")",
                "auditContent(",
                "startsWith(\"PASS\")",
                "startsWith(\"REJECT\")");
    }

    @Test
    void ordinaryChatArticleAdminPendingAndAuditQueueRemain() throws IOException {
        assertThat(mappedPaths()).contains(
                "/api/chat/friends",
                "/api/article/hot",
                "/api/article/admin/pending");
        assertThat(articleAuditQueue.getName()).isEqualTo("article.audit.queue");

        String articleService = Files.readString(Path.of(
                "src/main/java/cumt/zongzuo/community/service/impl/ArticleServiceImpl.java"));
        String articleMutationFacade = Files.readString(Path.of(
                "src/main/java/cumt/zongzuo/community/article/service/ArticleMutationFacade.java"));
        assertThat(articleService).contains(
                "articleMutationFacade.publishOrSave(dto, isPublish, userId)");
        assertThat(articleMutationFacade).contains(
                "if (mode == ArticleRevisionMode.LEGACY)",
                "int status = publish ? 2 : 0",
                "rabbitTemplate.convertAndSend(publish ? \"article.audit.queue\" : \"es.sync.queue\"");
    }

    @Test
    void platformDependencyBaselineUsesStableAiStartersWithoutLegacyRepositories() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        String applicationConfig = Files.readString(Path.of("src/main/resources/application.yml"));
        String integrationSupport = Files.readString(Path.of(
                "src/test/java/cumt/zongzuo/community/IntegrationTestSupport.java"));

        assertThat(pom).contains(
                "<version>3.5.16</version>",
                "<artifactId>spring-ai-bom</artifactId>",
                "<version>1.1.8</version>",
                "<artifactId>mybatis-plus-spring-boot3-starter</artifactId>",
                "<artifactId>mybatis-plus-jsqlparser-4.9</artifactId>",
                "<version>3.5.17</version>",
                "<artifactId>spring-ai-starter-model-deepseek</artifactId>",
                "<artifactId>spring-ai-starter-model-ollama</artifactId>",
                "<artifactId>spring-boot-starter-webflux</artifactId>",
                "<artifactId>spring-boot-starter-actuator</artifactId>",
                "<artifactId>micrometer-registry-prometheus</artifactId>",
                "<artifactId>resilience4j-spring-boot3</artifactId>",
                "<version>2.4.0</version>")
                .doesNotContain(
                        "spring-ai-openai-" + "spring-boot-starter",
                        "1.0.0-" + "M5",
                        "spring-" + "milestones",
                        "repo.spring.io/" + "milestone");
        assertThat(applicationConfig).contains(
                "chat: none",
                "embedding: none",
                "max-attempts: 1",
                "api-key: ${DEEPSEEK_API_KEY:}")
                .doesNotContain("  " + "openai:", "spring.ai." + "openai", "AI_" + "CHAT_ENABLED");
        assertThat(integrationSupport).contains(
                "spring.ai.model.chat",
                "spring.ai.model.embedding",
                "spring.ai.retry.max-attempts")
                .doesNotContain("spring.ai." + "openai", "api-key");
    }

    private Set<String> mappedPaths() {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .collect(Collectors.toSet());
    }

    private String readSource(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
