package cumt.zongzuo.community.launcher;

import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.context.annotation.Profile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityServiceTopologyTest {

    @Test
    void threeProcessesHaveStableRolesAndIndependentPorts() {
        assertThat(CommunityServiceRole.BACKEND.defaultPort()).isEqualTo(18080);
        assertThat(CommunityServiceRole.AGENT.defaultPort()).isEqualTo(18081);
        assertThat(CommunityServiceRole.WORKER.defaultPort()).isEqualTo(18082);

        assertThat(CommunityBackendServiceApplication.defaultProperties())
                .containsEntry("metro.service.role", "backend")
                .containsEntry("server.port", "18080")
                .containsEntry("spring.rabbitmq.listener.simple.auto-startup", "false");
        assertThat(CommunityAgentServiceApplication.defaultProperties())
                .containsEntry("metro.service.role", "agent")
                .containsEntry("server.port", "18081")
                .containsEntry("spring.rabbitmq.listener.simple.auto-startup", "false");
        assertThat(CommunityWorkerServiceApplication.defaultProperties())
                .containsEntry("metro.service.role", "worker")
                .containsEntry("server.port", "18082")
                .containsEntry("spring.rabbitmq.listener.simple.auto-startup", "true");
    }

    @Test
    void eachRoleHasAnIndependentPortProfileThatDoesNotReuseTheSharedServerPort() throws Exception {
        assertThat(Files.readString(Path.of("src/main/resources/application-backend-service.yml")))
                .contains("BACKEND_SERVER_PORT:18080");
        assertThat(Files.readString(Path.of("src/main/resources/application-agent-service.yml")))
                .contains("AGENT_SERVER_PORT:18081");
        assertThat(Files.readString(Path.of("src/main/resources/application-worker-service.yml")))
                .contains("WORKER_SERVER_PORT:18082");
    }

    @Test
    void splitRuntimeKeepsTheLegacyElasticsearchRepositoryInItsExplicitScanBoundary() {
        EnableElasticsearchRepositories repositories = CommunityServiceRuntimeConfiguration.class
                .getAnnotation(EnableElasticsearchRepositories.class);

        assertThat(repositories).isNotNull();
        assertThat(repositories.basePackages())
                .containsExactly("cumt.zongzuo.community.repository");
    }

    @Test
    void splitRuntimeConfigurationIsInvisibleToTheLegacyApplicationContext() {
        Profile profile = CommunityServiceRuntimeConfiguration.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder(
                "backend-service", "agent-service", "worker-service");
    }

    @Test
    void mavenPackageProducesThreeRoleSpecificExecutableArtifacts() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .contains("<id>repackage</id>", "<classifier>backend</classifier>",
                        "<mainClass>cumt.zongzuo.community.launcher.CommunityBackendServiceApplication</mainClass>")
                .contains("<id>agent-service</id>", "<classifier>agent</classifier>",
                        "<mainClass>cumt.zongzuo.community.launcher.CommunityAgentServiceApplication</mainClass>")
                .contains("<id>worker-service</id>", "<classifier>worker</classifier>",
                        "<mainClass>cumt.zongzuo.community.launcher.CommunityWorkerServiceApplication</mainClass>");
    }

    @Test
    void localSupervisorStartsAndStopsAllThreeRoleSpecificArtifacts() throws Exception {
        Path path = Path.of("scripts/run-local-services.sh");
        assertThat(path).exists().isRegularFile();
        String script = Files.readString(path);

        assertThat(script)
                .contains("community-0.0.1-SNAPSHOT-backend.jar")
                .contains("community-0.0.1-SNAPSHOT-agent.jar")
                .contains("community-0.0.1-SNAPSHOT-worker.jar")
                .contains("http://127.0.0.1:18080/actuator/health")
                .contains("http://127.0.0.1:18081/actuator/health")
                .contains("http://127.0.0.1:18082/actuator/health")
                .contains("trap cleanup EXIT INT TERM");
    }

    @Test
    void eachProcessOnlyPublishesTheHttpEntryPointsOwnedByItsRole() {
        String articleController = "cumt.zongzuo.community.controller.ArticleController";
        String moderationController = "cumt.zongzuo.community.ai.moderation.web.ModerationAdminController";
        String agentController = "cumt.zongzuo.community.ai.agent.web.AgentTurnController";
        String providerController = "cumt.zongzuo.community.ai.userprovider.UserAiProviderController";
        String agentAdvice = "cumt.zongzuo.community.ai.web.AiProblemDetailAdvice";

        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.BACKEND, articleController)).isTrue();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.BACKEND, moderationController)).isTrue();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.BACKEND, agentController)).isFalse();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.BACKEND, providerController)).isFalse();

        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.AGENT, articleController)).isFalse();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.AGENT, moderationController)).isFalse();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.AGENT, agentController)).isTrue();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.AGENT, providerController)).isTrue();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.AGENT, agentAdvice)).isTrue();

        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.WORKER, articleController)).isFalse();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.WORKER, agentController)).isFalse();

        // 普通 Service、Mapper、消息消费者仍由三个进程共享代码包加载；边界只裁剪 HTTP 入口，
        // 避免为了赶进度复制领域逻辑，同时确保异步 Worker 能复用完整业务服务。
        String articleService = "cumt.zongzuo.community.service.impl.ArticleServiceImpl";
        String springWebServerConfiguration =
                "org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryConfiguration$EmbeddedTomcat";
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.BACKEND, articleService)).isTrue();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.AGENT, articleService)).isTrue();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.WORKER, articleService)).isTrue();
        assertThat(CommunityRoleBeanBoundary.isAllowed(CommunityServiceRole.WORKER, springWebServerConfiguration))
                .isTrue();
    }
}
