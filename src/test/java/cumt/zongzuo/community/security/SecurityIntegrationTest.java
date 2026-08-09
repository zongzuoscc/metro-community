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
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, role, status)
                VALUES (1002, 'security-admin', 'unused', 'security-admin@example.com', 1, 0)
                ON DUPLICATE KEY UPDATE role = 1, status = 0
                """);
    }

    @Test
    void unauthenticatedFileUploadReturns401() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/file/upload"), HttpMethod.POST, null, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void recommendationFeedRequiresAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/recommendations/feed"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void recommendationViewRequiresAuthentication() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/recommendations/views/1"),
                new org.springframework.http.HttpEntity<>("{}", headers), String.class);

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
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getBody()).contains("\"code\":400", "\"msg\"", "\"data\"");
    }

    @Test
    void aiSecurityContractIsStrictlyPathScoped() {
        ResponseEntity<String> unauthenticatedAgent = restTemplate.getForEntity(
                url("/api/agent"), String.class);
        ResponseEntity<String> ordinaryUserModeration = restTemplate.exchange(
                url("/api/admin/moderation"), HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearerHeaders(1001L)), String.class);
        ResponseEntity<String> nearbyLegacyPath = restTemplate.exchange(
                url("/api/agent-old/missing"), HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearerHeaders(1001L)), String.class);

        assertThat(unauthenticatedAgent.getStatusCode().value()).isEqualTo(401);
        assertThat(unauthenticatedAgent.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(unauthenticatedAgent.getBody())
                .contains("\"code\":\"AUTHENTICATION_REQUIRED\"", "\"requestId\"");
        assertThat(ordinaryUserModeration.getStatusCode().value()).isEqualTo(403);
        assertThat(ordinaryUserModeration.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(ordinaryUserModeration.getBody()).contains("\"code\":\"ADMIN_ROLE_REQUIRED\"");
        assertThat(nearbyLegacyPath.getStatusCode().value()).isEqualTo(404);
        assertThat(nearbyLegacyPath.getHeaders().getContentType()).isNotNull();
        assertThat(nearbyLegacyPath.getHeaders().getContentType()
                .isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(nearbyLegacyPath.getBody()).contains("\"code\":404", "\"msg\"");
    }

    @Test
    void authenticatedRecommendationBindingFailuresReturnGeneric400WithoutOutbox() {
        jdbcTemplate.update("DELETE FROM recommendation_event_outbox");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(1001L));

        ResponseEntity<String> invalidSize = restTemplate.exchange(
                url("/api/recommendations/feed?size=abc"), HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class);
        ResponseEntity<String> invalidArticleId = restTemplate.exchange(
                url("/api/recommendations/views/not-a-long"), HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("{}", jsonHeaders(headers)), String.class);
        ResponseEntity<String> invalidExposureId = restTemplate.exchange(
                url("/api/recommendations/views/1"), HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("{\"exposureId\":\"abc\"}", jsonHeaders(headers)),
                String.class);

        assertGenericBadRequest(invalidSize, "abc");
        assertGenericBadRequest(invalidArticleId, "not-a-long");
        assertGenericBadRequest(invalidExposureId, "abc");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recommendation_event_outbox", Integer.class)).isZero();
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
    void moderationQueueTopologyAndLegacyAdminAuthorizationAreEnforced() {
        assertThat(amqpAdmin.getQueueProperties("article.audit.queue")).isNotNull();
        assertThat(amqpAdmin.getQueueProperties("article.audit.queue.dlq")).isNotNull();

        ResponseEntity<String> unauthenticated = restTemplate.getForEntity(
                url("/api/article/admin/pending"), String.class);
        ResponseEntity<String> ordinaryUser = restTemplate.exchange(
                url("/api/article/admin/pending"), HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearerHeaders(1001L)), String.class);
        ResponseEntity<String> administrator = restTemplate.exchange(
                url("/api/article/admin/pending"), HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearerHeaders(1002L)), String.class);

        assertThat(unauthenticated.getStatusCode().value()).isEqualTo(401);
        assertThat(unauthenticated.getBody()).contains("\"code\":401", "\"msg\"");
        assertThat(ordinaryUser.getStatusCode().value()).isEqualTo(403);
        assertThat(ordinaryUser.getBody()).contains("\"code\":403", "\"msg\"");
        assertThat(administrator.getStatusCode().value()).isEqualTo(200);
        assertThat(administrator.getBody()).contains("\"code\":200", "\"data\"");
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

    private HttpHeaders jsonHeaders(HttpHeaders source) {
        HttpHeaders copy = new HttpHeaders();
        copy.putAll(source);
        copy.setContentType(MediaType.APPLICATION_JSON);
        return copy;
    }

    private HttpHeaders bearerHeaders(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generate(userId));
        return headers;
    }

    private void assertGenericBadRequest(ResponseEntity<String> response, String maliciousValue) {
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody())
                .contains("\"code\":400")
                .doesNotContain(maliciousValue);
    }
}
