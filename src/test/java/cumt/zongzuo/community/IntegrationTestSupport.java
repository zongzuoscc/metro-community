package cumt.zongzuo.community;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.lifecycle.Startables;
import cumt.zongzuo.community.security.JwtService;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "metro.ai.enabled=false",
                "metro.ai.agent.enabled=false",
                "metro.ai.memory.enabled=false",
                "metro.ai.writing.enabled=false",
                "metro.ai.moderation.enabled=false",
                "metro.ai.embedding.enabled=false",
                "DEEPSEEK_API_KEY="
        })
public abstract class IntegrationTestSupport {

    private static final AtomicBoolean SCHEMA_INITIALIZED = new AtomicBoolean();

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    /**
     * The application mapping uses IK analyzers, so the test environment must use
     * the same Elasticsearch image composition as the local Docker deployment.
     */
    static final ImageFromDockerfile ELASTICSEARCH_WITH_IK = new ImageFromDockerfile(
            "community-elasticsearch-ik-test:8.4.1", false)
            .withFileFromPath("Dockerfile", Path.of("elasticsearch", "Dockerfile"))
            .withFileFromPath("elasticsearch-analysis-ik-8.4.1.zip",
                    Path.of("elasticsearch", "elasticsearch-analysis-ik-8.4.1.zip"));

    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse(ELASTICSEARCH_WITH_IK.get())
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("xpack.security.http.ssl.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    static {
        Startables.deepStart(Stream.of(MYSQL, REDIS, RABBIT, ELASTICSEARCH)).join();
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    private DataSource dataSource;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.addresses", RABBIT::getAmqpUrl);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("recommendation.outbox.dispatch-enabled", () -> "false");
        registry.add("metro.events.outbox.dispatch-enabled", () -> "false");
        registry.add("recommendation.profile-repair-initial-delay-ms", () -> "3600000");
        registry.add("spring.elasticsearch.uris", ELASTICSEARCH::getHttpHostAddress);
        registry.add("app.security.jwt-secret", () -> "test-secret-with-at-least-thirty-two-characters");
        registry.add("app.security.token-ttl", () -> "PT30M");
        registry.add("app.security.cors-allowed-origins", () -> "http://localhost:5173");
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("spring.ai.model.embedding", () -> "none");
        registry.add("spring.ai.retry.max-attempts", () -> "1");
    }

    @BeforeAll
    void initializeSchema() {
        if (SCHEMA_INITIALIZED.compareAndSet(false, true)) {
            new ResourceDatabasePopulator(new FileSystemResource("script.sql")).execute(dataSource);
        }
    }

    protected String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
