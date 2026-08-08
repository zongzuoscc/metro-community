package cumt.zongzuo.community.security;

import cumt.zongzuo.community.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityIntegrationTest extends IntegrationTestSupport {

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
