package cumt.zongzuo.community.ai.userprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "metro.ai.user-provider.enabled=true",
        "metro.ai.user-provider.credential-master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class UserAiProviderIntegrationTest extends IntegrationTestSupport {

    private static final long OWNER = 8_740_001L;

    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seedOwner() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO sys_user(id,username,password,email,role,status,deleted)
                VALUES (?,'byok-owner','encoded','byok-owner@example.test',0,0,0)
                """, OWNER);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM user_ai_provider_setting WHERE user_id=?", OWNER);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id=?", OWNER);
    }

    @Test
    void ownerCanSaveReadDisableAndDeleteWithoutEverReceivingTheKey() throws Exception {
        HttpHeaders headers = headers();
        ResponseEntity<String> saved = restTemplate.exchange(url("/api/agent/provider-settings"),
                HttpMethod.PUT, new HttpEntity<>(Map.of(
                        "provider", "OPENAI", "model", "gpt-4.1-mini",
                        "apiKey", "sk-integration-secret", "enabled", true), headers), String.class);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).doesNotContain("sk-integration-secret", "encryptedApiKey");
        JsonNode body = objectMapper.readTree(saved.getBody());
        assertThat(body.path("keyHint").asText()).isEqualTo("••••cret");
        assertThat(body.path("fundingSource").asText()).isEqualTo("USER");
        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT encrypted_api_key,key_hint,enabled FROM user_ai_provider_setting
                WHERE user_id=?
                """, OWNER);
        assertThat(stored.get("encrypted_api_key").toString()).doesNotContain("sk-integration-secret");

        ResponseEntity<String> disabled = restTemplate.exchange(
                url("/api/agent/provider-settings/enabled"), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("enabled", false), headers), String.class);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(disabled.getBody()).path("fundingSource").asText())
                .isEqualTo("PLATFORM");

        ResponseEntity<Void> deleted = restTemplate.exchange(url("/api/agent/provider-settings"),
                HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_ai_provider_setting WHERE user_id=?",
                Integer.class, OWNER)).isZero();
    }

    @Test
    void customLocalEndpointReturnsStableValidationProblemAndWritesNothing() throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(url("/api/agent/provider-settings"),
                HttpMethod.PUT, new HttpEntity<>(Map.of(
                        "provider", "CUSTOM", "baseUrl", "https://127.0.0.1/v1",
                        "model", "local", "apiKey", "secret", "enabled", true), headers()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).path("code").asText())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_ai_provider_setting WHERE user_id=?",
                Integer.class, OWNER)).isZero();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtService.generate(OWNER));
        return headers;
    }
}
