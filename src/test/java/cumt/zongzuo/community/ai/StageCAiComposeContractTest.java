package cumt.zongzuo.community.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class StageCAiComposeContractTest {

    @TempDir
    Path temporaryDirectory;

    private static final Path COMPOSE = Path.of("docker-compose.yml");
    private static final Path IMAGE_LOCK = Path.of("deploy/stage-c-images.lock.json");
    private static final Path START_SCRIPT = Path.of("scripts/stage-c-ai-compose.sh");
    private static final Path MODEL_PROVISION_SCRIPT = Path.of("scripts/stage-c-provision-bge-m3.sh");
    private static final Path MILVUS_CONFIG = Path.of("deploy/milvus/user.yaml");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> AI_SERVICES = Set.of("etcd", "minio", "milvus", "ollama");

    @Test
    void aiServicesAreOptInIsolatedHealthyAndNeverExposeNonLoopbackPorts() throws Exception {
        JsonNode root = YAML.readTree(COMPOSE.toFile());
        JsonNode services = root.path("services");

        assertThat(iterableFieldNames(services)).containsAll(AI_SERVICES);
        for (String name : AI_SERVICES) {
            JsonNode service = services.path(name);
            assertThat(stringValues(service.path("profiles")))
                    .as("%s must be opt-in", name)
                    .contains("ai");
            assertThat(service.path("healthcheck").isObject())
                    .as("%s must declare a health check", name)
                    .isTrue();
            assertThat(service.has("container_name"))
                    .as("%s must remain project scoped", name)
                    .isFalse();
        }

        assertThat(stringValues(services.path("etcd").path("networks")))
                .containsExactly("metro-milvus-internal-net");
        assertThat(stringValues(services.path("minio").path("networks")))
                .containsExactly("metro-milvus-internal-net");
        assertThat(stringValues(services.path("milvus").path("networks")))
                .containsExactlyInAnyOrder("metro-ai-app-net", "metro-milvus-internal-net");
        assertThat(stringValues(services.path("ollama").path("networks")))
                .containsExactly("metro-ai-app-net");
        assertThat(root.path("networks").path("metro-milvus-internal-net").path("internal").asBoolean())
                .isTrue();

        services.properties().forEach(entry -> {
            JsonNode service = entry.getValue();
            assertThat(service.has("container_name"))
                    .as("%s must not escape the Compose project", entry.getKey())
                    .isFalse();
            stringValues(service.path("ports")).forEach(port -> assertThat(port)
                    .as("%s host port must bind to loopback", entry.getKey())
                    .startsWith("127.0.0.1:"));
        });

        assertThat(stringValues(services.path("etcd").path("ports"))).isEmpty();
        assertThat(stringValues(services.path("minio").path("ports"))).isEmpty();
    }

    @Test
    void composeImagesArePinnedByTheCheckedInMultiArchitectureLock() throws Exception {
        assertThat(IMAGE_LOCK).exists();
        JsonNode lock = JSON.readTree(IMAGE_LOCK.toFile());
        JsonNode images = lock.path("images");

        for (String service : AI_SERVICES) {
            JsonNode entry = images.path(service);
            assertThat(entry.isObject()).as("missing lock for %s", service).isTrue();
            assertDigest(entry.path("manifestDigest").asText());
            assertDigest(entry.path("platforms").path("linux/amd64").asText());
            assertDigest(entry.path("platforms").path("linux/arm64").asText());
            assertThat(entry.path("tag").asText()).doesNotEndWith(":latest");

            String composeImage = YAML.readTree(COMPOSE.toFile())
                    .path("services").path(service).path("image").asText();
            assertThat(composeImage)
                    .isEqualTo(entry.path("reference").asText())
                    .contains("@" + entry.path("manifestDigest").asText());
        }
    }

    @Test
    void checkedInLauncherPreflightsPortsAndStartsWithoutPullingMutableImages() throws IOException {
        assertThat(START_SCRIPT).exists().isExecutable();
        String script = Files.readString(START_SCRIPT);

        assertThat(script)
                .contains("docker compose")
                .contains("--profile ai")
                .contains("config --format json")
                .contains("--pull never")
                .contains("stage-c-images.lock.json")
                .contains("127.0.0.1")
                .contains("bind(")
                .doesNotContain("docker compose pull")
                .doesNotContain("ollama pull");
    }

    @Test
    void checkedInDefaultsEnableMilvusAuthorizationWithoutEnablingAiCapabilities() throws IOException {
        assertThat(MILVUS_CONFIG).exists();
        String milvus = Files.readString(MILVUS_CONFIG);
        String environment = Files.readString(Path.of(".env.example"));
        String production = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(milvus).contains("authorizationEnabled: true");
        assertThat(environment).contains(
                "MILVUS_HOST_PORT=29530",
                "MILVUS_MINIO_ROOT_USER=metro_minio",
                "MILVUS_MINIO_ROOT_PASSWORD=",
                "OLLAMA_HOST_PORT=21434",
                "OLLAMA_BASE_URL=http://127.0.0.1:21434");
        assertThat(production).contains(
                "enabled: ${METRO_AI_ENABLED:false}",
                "enabled: ${METRO_AI_EMBEDDING_ENABLED:false}")
                .doesNotContain("ollama pull")
                .doesNotContain("auto-pull");
    }

    @Test
    void bgeM3ProvisioningIsExplicitDigestCheckedAndUsesAnImmutableLocalAlias() throws Exception {
        JsonNode model = JSON.readTree(IMAGE_LOCK.toFile()).path("models").path("bgeM3");
        assertThat(model.path("upstreamReference").asText()).isEqualTo("bge-m3:latest");
        assertThat(model.path("manifestDigest").asText())
                .isEqualTo("sha256:7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab");
        assertThat(model.path("localAlias").asText()).isEqualTo("metro-bge-m3:790764642607");
        assertThat(model.path("blobs").findValuesAsText("digest"))
                .containsExactly(
                        "sha256:0c4c9c2a325fb1cdafec606e6809cb745f1cb26a6d919994400d27372303e276",
                        "sha256:daec91ffb5dd0c27411bd71f29932917c49cf529a641d0168496c3a501e3062c",
                        "sha256:a406579cd136771c705c521db86ca7d60a6f3de7c9b5460e6193a2df27861bde");

        assertThat(MODEL_PROVISION_SCRIPT).exists().isExecutable();
        String provision = Files.readString(MODEL_PROVISION_SCRIPT);
        assertThat(provision).contains(
                "registry.ollama.ai/v2/library/bge-m3/manifests/latest",
                "/api/pull",
                "/api/tags",
                "/api/copy",
                "manifestDigest");
        assertThat(Files.readString(Path.of(".env.example")))
                .contains("OLLAMA_EMBEDDING_MODEL=metro-bge-m3:790764642607")
                .doesNotContain("OLLAMA_EMBEDDING_MODEL=bge-m3\n");
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
                .contains("model: \"${OLLAMA_EMBEDDING_MODEL:metro-bge-m3:790764642607}\"");
        assertThat(Files.readString(Path.of("src/main/resources/application-example.yml")))
                .contains("model: \"${OLLAMA_EMBEDDING_MODEL:metro-bge-m3:790764642607}\"");
    }

    @Test
    void aRunningBaseStackPortDoesNotBlockTheAiOnlyPreflight() throws Exception {
        try (ServerSocket occupiedBasePort = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            Path rendered = temporaryDirectory.resolve("compose.json");
            ProcessBuilder render = new ProcessBuilder(
                    "docker", "compose", "--env-file", ".env.example", "--profile", "ai",
                    "config", "--format", "json");
            render.environment().put(
                    "ELASTICSEARCH_HOST_PORT", Integer.toString(occupiedBasePort.getLocalPort()));
            Process renderProcess = render.redirectOutput(rendered.toFile()).redirectErrorStream(true).start();
            assertThat(renderProcess.waitFor()).isZero();

            Path invocationLog = temporaryDirectory.resolve("docker-invocations.log");
            Path fakeDocker = temporaryDirectory.resolve("docker");
            Files.writeString(fakeDocker, """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
                    if [[ "$1" == "compose" && "$*" == *"config --format json"* ]]; then
                      /bin/cat "$FAKE_COMPOSE_CONFIG"
                      exit 0
                    fi
                    if [[ "$1" == "image" && "$2" == "inspect" ]]; then
                      exit 0
                    fi
                    exit 91
                    """);
            assertThat(fakeDocker.toFile().setExecutable(true)).isTrue();

            ProcessBuilder launch = new ProcessBuilder(START_SCRIPT.toAbsolutePath().toString());
            launch.environment().put("PATH", temporaryDirectory + ":" + System.getenv("PATH"));
            launch.environment().put("ENV_FILE", Path.of(".env.example").toAbsolutePath().toString());
            launch.environment().put("CHECK_ONLY", "true");
            launch.environment().put("COMPOSE_PROJECT_NAME", "metro-stage-c-ai-contract");
            launch.environment().put("FAKE_COMPOSE_CONFIG", rendered.toAbsolutePath().toString());
            launch.environment().put("FAKE_DOCKER_LOG", invocationLog.toAbsolutePath().toString());
            Process launchProcess = launch.redirectErrorStream(true).start();
            String output = new String(launchProcess.getInputStream().readAllBytes());

            assertThat(launchProcess.waitFor()).as(output).isZero();
            assertThat(output).contains("Stage C AI Compose preflight passed");
            assertThat(Files.readString(invocationLog)).doesNotContain(" up ");
        }
    }

    private static void assertDigest(String value) {
        assertThat(value).matches(DIGEST);
    }

    private static List<String> stringValues(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return node.valueStream().map(JsonNode::asText).toList();
    }

    private static Set<String> iterableFieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
    }
}
