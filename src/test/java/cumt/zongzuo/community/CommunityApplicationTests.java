package cumt.zongzuo.community;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityApplicationTests {

    @Test
    void targetsJava21AndKeepsProductionConfigSecretFree() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        String config = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(pom).contains("<java.version>21</java.version>");
        assertThat(config)
                .doesNotContain("yangyiming.com")
                .doesNotContain("GTg3F34BVVjFK4XB");
    }

    @Test
    void documentsServingAsDisabledByDefaultWhileDailyTrainingRemainsEnabled() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String environment = Files.readString(Path.of(".env.example"));

        assertThat(readme)
                .contains("推荐排序 Serving 默认关闭")
                .contains("训练任务仍按 Asia/Shanghai 每日 02:15 运行")
                .doesNotContain("推荐训练默认关闭");
        assertThat(environment).contains(
                "RECOMMENDATION_ENABLED=false",
                "RECOMMENDATION_MODEL_WINDOW_DAYS=90",
                "RECOMMENDATION_LABEL_WINDOW_DAYS=7",
                "RECOMMENDATION_MODEL_MAX_AGE_DAYS=7",
                "RECOMMENDATION_TRAINING_SAMPLE_LIMIT=50000",
                "RECOMMENDATION_MODEL_DIRECTORY=");
    }

    @Test
    void documentsRecommendationProductModelAndObservabilityBoundaries() throws IOException {
        String readme = Files.readString(Path.of("README.md"));

        assertThat(readme).contains(
                "“推荐”仅供已认证用户使用",
                "“最新”始终使用按发布时间排序的时间线",
                "最近 30 天至少 20 条去重有效行为",
                "全站最近 90 天至少 500 条去重有效行为",
                "COLD_START",
                "FALLBACK",
                "九个投递时特征",
                "AUC",
                "/api/recommendations/feed",
                "40 天",
                "FOLLOW、TAG、SIMILAR、EXPLORE、CHRONOLOGICAL",
                "VIEW、LIKE、COLLECT、COMMENT、FOLLOW_AUTHOR",
                "00:05",
                "不提供公开指标 API 或 Dashboard");
    }

    @Test
    void environmentExampleCanBeSourcedByTheDocumentedShellCommand() throws Exception {
        Process process = new ProcessBuilder(
                "bash", "-c", "set -a; source .env.example; printf '%s' \"$DB_URL\"")
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes());

        assertThat(process.waitFor()).isZero();
        assertThat(output).isEqualTo(
                "jdbc:mysql://127.0.0.1:13306/metro_community?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai");
    }

    @Test
    void documentsTheDisabledByDefaultStageAiFoundationContract() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String environment = Files.readString(Path.of(".env.example"));
        String exampleConfig = Files.readString(Path.of("src/main/resources/application-example.yml"));
        String productionConfig = Files.readString(Path.of("src/main/resources/application.yml"));
        String pom = Files.readString(Path.of("pom.xml"));
        String integrationSupport = Files.readString(Path.of(
                "src/test/java/cumt/zongzuo/community/IntegrationTestSupport.java"));

        assertThat(readme).contains(
                "Java 21、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 3.5.17",
                "Stage A",
                "Provider 服务默认不会被访问",
                "人工待审",
                "Stage B",
                "Stage C",
                "Stage D",
                "metro.ai.enabled",
                "metro.ai.agent.enabled",
                "metro.ai.memory.enabled",
                "metro.ai.writing.enabled",
                "metro.ai.moderation.enabled",
                "metro.ai.embedding.enabled",
                "当前 Actuator 只暴露 health",
                "NOT RUN");
        assertThat(environment).contains(
                "METRO_AI_ENABLED=false",
                "METRO_AI_AGENT_ENABLED=false",
                "METRO_AI_MEMORY_ENABLED=false",
                "METRO_AI_WRITING_ENABLED=false",
                "METRO_AI_MODERATION_ENABLED=false",
                "METRO_AI_EMBEDDING_ENABLED=false",
                "DEEPSEEK_BASE_URL=https://api.deepseek.com",
                "DEEPSEEK_API_KEY=",
                "DEEPSEEK_MODEL=deepseek-v4-flash",
                "OLLAMA_BASE_URL=http://127.0.0.1:21434",
                "OLLAMA_EMBEDDING_MODEL=metro-bge-m3:790764642607");
        assertThat(exampleConfig).contains(
                "chat: none",
                "embedding: none",
                "max-attempts: 1",
                "enabled: ${METRO_AI_ENABLED:false}",
                "enabled: ${METRO_AI_AGENT_ENABLED:false}",
                "enabled: ${METRO_AI_MEMORY_ENABLED:false}",
                "enabled: ${METRO_AI_WRITING_ENABLED:false}",
                "enabled: ${METRO_AI_MODERATION_ENABLED:false}",
                "enabled: ${METRO_AI_EMBEDDING_ENABLED:false}");
        assertThat(exampleConfig.lines().filter("spring:"::equals).count()).isEqualTo(1);
        assertThat(productionConfig).contains(
                "enabled: ${METRO_AI_ENABLED:false}",
                "enabled: ${METRO_AI_AGENT_ENABLED:false}",
                "enabled: ${METRO_AI_MEMORY_ENABLED:false}",
                "enabled: ${METRO_AI_WRITING_ENABLED:false}",
                "enabled: ${METRO_AI_MODERATION_ENABLED:false}",
                "enabled: ${METRO_AI_EMBEDDING_ENABLED:false}");
        assertThat(pom).contains(
                "<version>3.5.16</version>",
                "<version>1.1.8</version>",
                "<version>3.5.17</version>");
        assertThat(integrationSupport).contains(
                "\"metro.ai.enabled=false\"",
                "\"metro.ai.agent.enabled=false\"",
                "\"metro.ai.memory.enabled=false\"",
                "\"metro.ai.writing.enabled=false\"",
                "\"metro.ai.moderation.enabled=false\"",
                "\"metro.ai.embedding.enabled=false\"",
                "\"DEEPSEEK_API_KEY=\"");
        assertThat(readme + environment + exampleConfig)
                .doesNotContain("AI_CHAT_ENABLED")
                .doesNotContain("/api/ai/")
                .doesNotContain("ChatUtils")
                .doesNotContain("MetroAiService")
                .doesNotContain("AiToolConfig")
                .doesNotContain("9999")
                .doesNotContain("1.0.0-M5")
                .doesNotContain("spring-ai-openai-spring-boot-starter")
                .doesNotContain("图片和音频模型");
    }
}
