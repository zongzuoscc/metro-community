package cumt.zongzuo.community.ai.web;

import cumt.zongzuo.community.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(AiProblemDetailIntegrationTest.AiContractController.class)
class AiCorsIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 7_710_011L;
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    void seedUser() {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, role, status)
                VALUES (?, 'ai-cors-user', 'unused', 'ai-cors-user@example.com', 0, 0)
                ON DUPLICATE KEY UPDATE role = 0, status = 0
                """, USER_ID);
    }

    @Test
    void patchPreflightAllowsEveryAgentProtocolHeader() throws Exception {
        mockMvc.perform(options("/api/agent/test/body")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "Authorization, token, Content-Type, Last-Event-ID, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        containsStringIgnoringCase("PATCH")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("token")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("content-type")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("last-event-id")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("idempotency-key")));
    }

    @Test
    void crossOriginQuotaResponseExposesRetryAfter() throws Exception {
        mockMvc.perform(get("/api/agent/test/api-error/quota")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generate(USER_ID)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsStringIgnoringCase("authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsStringIgnoringCase("retry-after")))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "7"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(7));
    }
}
