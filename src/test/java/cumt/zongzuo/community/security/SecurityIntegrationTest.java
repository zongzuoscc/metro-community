package cumt.zongzuo.community.security;

import cumt.zongzuo.community.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityIntegrationTest extends IntegrationTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeAll
    void createAuthenticatedUser() {
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password, email, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                1001L, "security-test", "unused", "security-test@example.com", 0, 0);
    }

    @Test
    void unauthenticatedFileUploadReturns401() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/file/upload"), HttpMethod.POST, null, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void bearerTokenAuthenticatesProtectedEndpointWithoutLegacyHeader() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/message/unread"), HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(new org.springframework.http.HttpHeaders() {{
                    setBearerAuth(jwtService.generate(1001L));
                }}), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void malformedLoginPayloadReturns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/auth/login"), new org.springframework.http.HttpEntity<>("{}", headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void messageDeadLetterQueuesAreDeclared() {
        assertThat(amqpAdmin.getQueueProperties("like.task.queue.dlq")).isNotNull();
        assertThat(amqpAdmin.getQueueProperties("es.sync.queue.dlq")).isNotNull();
    }

    @Test
    void recommendationDeadLetterQueueIsDeclared() {
        assertThat(amqpAdmin.getQueueProperties("recommendation.event.queue.dlq")).isNotNull();
    }

    @Test
    void profileUpdateCannotEscalateRoleOrChangeAccountState() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(1001L));
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> updateResponse = restTemplate.postForEntity(
                url("/api/user/update"), new org.springframework.http.HttpEntity<>(
                        "{\"username\":\"security-test-renamed\",\"role\":1,\"status\":1,"
                                + "\"email\":\"attacker@example.com\"}", headers), String.class);

        assertThat(updateResponse.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> profileResponse = restTemplate.exchange(
                url("/api/user/info"), HttpMethod.GET, new org.springframework.http.HttpEntity<>(headers), String.class);

        assertThat(profileResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(profileResponse.getBody())
                .contains("security-test-renamed", "\"role\":0", "\"status\":0")
                .doesNotContain("attacker@example.com");
    }

    @Test
    void authenticatedNonImageUploadReturns400BeforeCallingObjectStorage() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(1001L));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("not-an-image".getBytes()) {
            @Override
            public String getFilename() {
                return "payload.txt";
            }
        });

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/file/upload"), new org.springframework.http.HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
