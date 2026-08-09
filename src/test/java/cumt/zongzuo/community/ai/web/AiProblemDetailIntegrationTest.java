package cumt.zongzuo.community.ai.web;

import cumt.zongzuo.community.IntegrationTestSupport;
import cumt.zongzuo.community.ai.provider.AiProviderErrorReason;
import cumt.zongzuo.community.ai.provider.AiProviderException;
import cumt.zongzuo.community.ai.runtime.AiExecutionErrorReason;
import cumt.zongzuo.community.ai.runtime.AiExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import({AiProblemDetailIntegrationTest.AiContractController.class,
        AiProblemDetailIntegrationTest.ProxiedValidationController.class})
class AiProblemDetailIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 7_710_001L;
    private static final long ADMIN_ID = 7_710_002L;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    void seedUsers() {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, role, status)
                VALUES (?, 'ai-http-user', 'unused', 'ai-http-user@example.com', 0, 0)
                ON DUPLICATE KEY UPDATE role = 0, status = 0
                """, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, email, role, status)
                VALUES (?, 'ai-http-admin', 'unused', 'ai-http-admin@example.com', 1, 0)
                ON DUPLICATE KEY UPDATE role = 1, status = 0
                """, ADMIN_ID);
    }

    @Test
    void missingAndBadJwtOnAgentPathsUseProblemDetail() throws Exception {
        assertProblem(mockMvc.perform(get("/api/agent/test/ok")), 401, "AUTHENTICATION_REQUIRED")
                .andExpect(jsonPath("$.retryable").value(false));

        assertProblem(mockMvc.perform(get("/api/agent/test/ok")
                        .header("Authorization", "Bearer definitely-not-a-jwt")),
                401, "AUTHENTICATION_REQUIRED");

        assertProblem(mockMvc.perform(get("/api/agent")), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void aiApiIsAnInheritedRuntimeTypeMarkerOnly() {
        Target target = AiApi.class.getAnnotation(Target.class);
        Retention retention = AiApi.class.getAnnotation(Retention.class);

        assertThat(target).isNotNull();
        assertThat(Arrays.asList(target.value())).containsExactly(ElementType.TYPE);
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(AiApi.class.isAnnotationPresent(Inherited.class)).isTrue();
        assertThat(AiApi.class.isAnnotationPresent(RestController.class)).isFalse();
        assertThat(InheritedAiApiType.class.isAnnotationPresent(AiApi.class)).isTrue();
    }

    @Test
    void moderationPathRequiresAdministratorAndUsesProblemDetail() throws Exception {
        assertProblem(mockMvc.perform(get("/api/admin/moderation/test")
                        .header("Authorization", bearer(USER_ID))),
                403, "ADMIN_ROLE_REQUIRED");

        mockMvc.perform(get("/api/admin/moderation/test")
                        .header("Authorization", bearer(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void malformedJsonUsesStableProblemWithoutEchoingInput() throws Exception {
        assertProblem(mockMvc.perform(post("/api/agent/test/body")
                        .header("Authorization", bearer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"secret-user-content\"")),
                400, "MALFORMED_JSON")
                .andExpect(jsonPath("$.fieldErrors", hasSize(0)))
                .andExpect(content().string(not(containsString("secret-user-content"))));
    }

    @Test
    void bodyValidationErrorsAreSortedAndNeverContainRejectedValues() throws Exception {
        assertProblem(mockMvc.perform(post("/api/agent/test/body")
                        .header("Authorization", bearer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\",\"count\":0}")),
                400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors", hasSize(2)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("count"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("count must be at least one"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("message"))
                .andExpect(jsonPath("$.fieldErrors[1].message").value("message is required"))
                .andExpect(content().string(not(containsString("rejectedValue"))));
    }

    @Test
    void typeMismatchAndMissingInputUseValidationProblemWithoutEchoingValues() throws Exception {
        assertProblem(mockMvc.perform(get("/api/agent/test/type/leaked-type-input")
                        .header("Authorization", bearer(USER_ID))),
                400, "VALIDATION_FAILED")
                .andExpect(content().string(not(containsString("leaked-type-input"))));

        assertProblem(mockMvc.perform(get("/api/agent/test/missing")
                        .header("Authorization", bearer(USER_ID))),
                400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("value"));
    }

    @Test
    void handlerValidationUsesPathOnlyInstanceAndServerGeneratedRequestId() throws Exception {
        String body = mockMvc.perform(get("/api/agent/test/handler-validation")
                        .queryParam("limit", "0")
                        .queryParam("secret", "must-not-enter-instance")
                        .header("X-Request-Id", "attacker-controlled-request-id")
                        .header("Authorization", bearer(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.instance").value("/api/agent/test/handler-validation"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("must-not-enter-instance")
                .doesNotContain("attacker-controlled-request-id");
    }

    @Test
    void proxiedAndServletBindingValidationAlsoUseTheCompleteProblemContract() throws Exception {
        assertProblem(mockMvc.perform(get("/api/agent/test/constraint-validation")
                        .queryParam("amount", "0")
                        .header("Authorization", bearer(USER_ID))),
                400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"))
                .andExpect(content().string(not(containsString("rejectedValue"))));

        assertProblem(mockMvc.perform(get("/api/agent/test/missing-header")
                        .header("Authorization", bearer(USER_ID))),
                400, "VALIDATION_FAILED");
    }

    @Test
    void dynamicValidationMessagesNeverEchoRejectedValuesOrNormalizedVariants() throws Exception {
        String rejectedValue = "secret\nvalue";

        assertProblem(mockMvc.perform(get("/api/agent/test/dynamic-validation")
                        .queryParam("value", rejectedValue)
                        .header("Authorization", bearer(USER_ID))),
                400, "VALIDATION_FAILED")
                .andExpect(content().string(not(containsString(rejectedValue))))
                .andExpect(content().string(not(containsString("secret value"))))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void requestIdIsGeneratedOnceAndReusedWithinTheServerRequest() throws Exception {
        var result = mockMvc.perform(get("/api/agent/test/request-id")
                        .header("X-Request-Id", "untrusted-client-id")
                        .header("Authorization", bearer(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andReturn().getResponse();

        String serverRequestId = result.getHeader("X-Test-Server-Request-Id");
        String problemRequestId = com.jayway.jsonpath.JsonPath.read(
                result.getContentAsString(), "$.requestId");
        assertThat(serverRequestId).isNotBlank().isNotEqualTo("untrusted-client-id");
        assertThat(problemRequestId).isEqualTo(serverRequestId);
    }

    @Test
    void regexRoutePatternsAreSafelyEncodedInProblemInstances() throws Exception {
        assertProblem(mockMvc.perform(get("/api/agent/test/regex/123")
                        .header("Authorization", bearer(USER_ID))),
                404, "RESOURCE_NOT_FOUND")
                .andExpect(jsonPath("$.instance")
                        .value("/api/agent/test/regex/%7Bid:%5Cd+%7D"));
    }

    @ParameterizedTest(name = "api exception {0} -> {1} {2}")
    @MethodSource("apiExceptionContracts")
    void mapsEveryPublicAiApiException(String kind, int httpStatus, String code,
                                       boolean retryable, Integer retryAfter) throws Exception {
        ResultActions result = assertProblem(mockMvc.perform(get("/api/agent/test/api-error/{kind}", kind)
                        .header("Authorization", bearer(USER_ID))), httpStatus, code)
                .andExpect(jsonPath("$.retryable").value(retryable));
        assertRetryAfter(result, retryAfter);
    }

    @ParameterizedTest(name = "runtime reason {0} -> {1} {2}")
    @MethodSource("executionErrorContracts")
    void exhaustivelyMapsExecutionErrors(AiExecutionErrorReason reason, int httpStatus,
                                          String code, boolean retryable, Integer retryAfter) throws Exception {
        ResultActions result = assertProblem(mockMvc.perform(get("/api/agent/test/execution/{reason}", reason)
                        .header("Authorization", bearer(USER_ID))), httpStatus, code)
                .andExpect(jsonPath("$.retryable").value(retryable))
                .andExpect(content().string(not(containsString("sensitive-runtime-message"))));
        assertRetryAfter(result, retryAfter);
    }

    @ParameterizedTest(name = "provider reason {0} -> {1} {2}")
    @MethodSource("providerErrorContracts")
    void exhaustivelyMapsProviderErrors(AiProviderErrorReason reason, int httpStatus,
                                         String code, boolean retryable, Integer retryAfter) throws Exception {
        ResultActions result = assertProblem(mockMvc.perform(get("/api/agent/test/provider/{reason}", reason)
                        .header("Authorization", bearer(USER_ID))), httpStatus, code)
                .andExpect(jsonPath("$.retryable").value(retryable))
                .andExpect(content().string(not(containsString("sensitive-provider-body"))));
        assertRetryAfter(result, retryAfter);
    }

    @Test
    void unknownMarkedControllerFailureIsRealSanitized500() throws Exception {
        assertProblem(mockMvc.perform(get("/api/agent/test/internal")
                        .header("Authorization", bearer(USER_ID))),
                500, "INTERNAL_ERROR")
                .andExpect(content().string(not(containsString("private-stack-content"))));
    }

    @Test
    void unknownAiPathsAndFramework405And415AreProblemDetails() throws Exception {
        assertProblem(mockMvc.perform(get("/api/agent/does-not-exist")
                        .header("Authorization", bearer(USER_ID))),
                404, "RESOURCE_NOT_FOUND");
        assertProblem(mockMvc.perform(get("/api/admin/moderation/does-not-exist")
                        .header("Authorization", bearer(ADMIN_ID))),
                404, "RESOURCE_NOT_FOUND");
        assertProblem(mockMvc.perform(put("/api/agent/test/body")
                        .header("Authorization", bearer(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                405, "METHOD_NOT_ALLOWED");
        assertProblem(mockMvc.perform(post("/api/agent/test/body")
                        .header("Authorization", bearer(USER_ID))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("private-unsupported-body")),
                415, "UNSUPPORTED_MEDIA_TYPE")
                .andExpect(content().string(not(containsString("private-unsupported-body"))));
    }

    @Test
    void nearbyAndLegacyPathsKeepTheResultContract() throws Exception {
        mockMvc.perform(get("/api/agent-old/does-not-exist")
                        .header("Authorization", bearer(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").exists())
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(get("/api/admin/moderation-old/does-not-exist")
                        .header("Authorization", bearer(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").exists());

        mockMvc.perform(get("/api/message/unread"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").exists());

        mockMvc.perform(get("/api/legacy/does-not-exist")
                        .header("Authorization", bearer(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").exists());

        mockMvc.perform(get("/api/article/admin/pending")
                        .header("Authorization", bearer(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").exists());
    }

    @Test
    void legacyFramework405And415KeepTheirExistingBodyAndHttpStatus() throws Exception {
        String expected = "{\"code\":500,\"msg\":\"服务器开小差了，请稍后再试\",\"data\":null}";

        mockMvc.perform(put("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(expected));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("legacy-private-body"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(expected));
    }

    private ResultActions assertProblem(ResultActions result, int httpStatus, String code) throws Exception {
        return result
                .andExpect(status().is(httpStatus))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(httpStatus))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").isNotEmpty())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.retryable").isBoolean())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    private void assertRetryAfter(ResultActions result, Integer seconds) throws Exception {
        if (seconds == null) {
            result.andExpect(header().doesNotExist("Retry-After"))
                    .andExpect(jsonPath("$.retryAfterSeconds").value(org.hamcrest.Matchers.nullValue()));
        }
        else {
            result.andExpect(header().string("Retry-After", seconds.toString()))
                    .andExpect(jsonPath("$.retryAfterSeconds").value(seconds));
        }
    }

    private String bearer(long userId) {
        return "Bearer " + jwtService.generate(userId);
    }

    static Stream<Arguments> apiExceptionContracts() {
        return Stream.of(
                Arguments.of("not-found", 404, "RESOURCE_NOT_FOUND", false, null),
                Arguments.of("idempotency", 409, "IDEMPOTENCY_CONFLICT", false, null),
                Arguments.of("active-turn", 409, "ACTIVE_TURN_EXISTS", false, null),
                Arguments.of("optimistic-lock", 409, "OPTIMISTIC_LOCK_CONFLICT", false, null),
                Arguments.of("suggestion-state", 409, "SUGGESTION_STATE_CONFLICT", false, null),
                Arguments.of("temporary-expired", 410, "TEMPORARY_SESSION_EXPIRED", false, null),
                Arguments.of("stream-expired", 410, "EVENT_STREAM_EXPIRED", false, null),
                Arguments.of("input-too-large", 413, "AI_INPUT_TOO_LARGE", false, null),
                Arguments.of("quota", 429, "AI_QUOTA_EXCEEDED", true, 7),
                Arguments.of("concurrency", 429, "AI_CONCURRENCY_LIMIT", true, 3),
                Arguments.of("disabled", 503, "AI_DISABLED", false, null),
                Arguments.of("unavailable", 503, "AI_UNAVAILABLE", true, 5),
                Arguments.of("runtime", 503, "AGENT_RUNTIME_UNAVAILABLE", true, 4));
    }

    static Stream<Arguments> executionErrorContracts() {
        return Stream.of(
                Arguments.of(AiExecutionErrorReason.AI_DISABLED, 503, "AI_DISABLED", false, null),
                Arguments.of(AiExecutionErrorReason.INVALID_INVOCATION, 400, "VALIDATION_FAILED", false, null),
                Arguments.of(AiExecutionErrorReason.INPUT_TOO_LARGE, 413, "AI_INPUT_TOO_LARGE", false, null),
                Arguments.of(AiExecutionErrorReason.DEADLINE_EXCEEDED, 503, "AI_UNAVAILABLE", true, 7),
                Arguments.of(AiExecutionErrorReason.QUOTA_EXCEEDED, 429, "AI_QUOTA_EXCEEDED", true, 7),
                Arguments.of(AiExecutionErrorReason.AGENT_RUNTIME_UNAVAILABLE, 503,
                        "AGENT_RUNTIME_UNAVAILABLE", true, 7),
                Arguments.of(AiExecutionErrorReason.BULKHEAD_FULL, 429, "AI_CONCURRENCY_LIMIT", true, 7),
                Arguments.of(AiExecutionErrorReason.CIRCUIT_OPEN, 503, "AI_UNAVAILABLE", true, 7),
                Arguments.of(AiExecutionErrorReason.TIMEOUT, 503, "AI_UNAVAILABLE", true, 7),
                Arguments.of(AiExecutionErrorReason.CANCELLED, 503, "AGENT_RUNTIME_UNAVAILABLE", true, 7),
                Arguments.of(AiExecutionErrorReason.PROVIDER_FAILURE, 503, "AI_UNAVAILABLE", true, 7));
    }

    static Stream<Arguments> providerErrorContracts() {
        return Stream.of(
                Arguments.of(AiProviderErrorReason.AI_DISABLED, 503, "AI_DISABLED", false, null),
                Arguments.of(AiProviderErrorReason.AI_UNAVAILABLE, 503, "AI_UNAVAILABLE", true, 1),
                Arguments.of(AiProviderErrorReason.CONNECTION_FAILURE, 503, "AI_UNAVAILABLE", true, 1),
                Arguments.of(AiProviderErrorReason.TIMEOUT, 503, "AI_UNAVAILABLE", true, 1),
                Arguments.of(AiProviderErrorReason.RATE_LIMITED, 503, "AI_UNAVAILABLE", true, 1),
                Arguments.of(AiProviderErrorReason.RETRYABLE_PROVIDER_FAILURE, 503,
                        "AI_UNAVAILABLE", true, 1),
                Arguments.of(AiProviderErrorReason.NON_RETRYABLE_PROVIDER_FAILURE, 503,
                        "AI_UNAVAILABLE", false, null),
                Arguments.of(AiProviderErrorReason.MALFORMED_RESPONSE, 503,
                        "AI_UNAVAILABLE", false, null),
                Arguments.of(AiProviderErrorReason.EMPTY_RESPONSE, 503,
                        "AI_UNAVAILABLE", false, null));
    }

    @RestController
    @AiApi
    static class AiContractController {

        @GetMapping("/api/agent/test/ok")
        String ok() {
            return "ok";
        }

        @PostMapping(value = "/api/agent/test/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@Valid @RequestBody TestBody body) {
            return body.message();
        }

        @GetMapping("/api/agent/test/type/{id}")
        String type(@PathVariable Long id) {
            return id.toString();
        }

        @GetMapping("/api/agent/test/missing")
        String missing(@RequestParam String value) {
            return value;
        }

        @GetMapping("/api/agent/test/missing-header")
        String missingHeader(@RequestHeader("X-Required") String value) {
            return value;
        }

        @GetMapping("/api/agent/test/handler-validation")
        String handlerValidation(
                @RequestParam @Min(value = 1, message = "limit must be at least one") int limit) {
            return Integer.toString(limit);
        }

        @GetMapping("/api/agent/test/api-error/{kind}")
        String apiError(@PathVariable String kind) {
            throw switch (kind) {
                case "not-found" -> AiApiException.resourceNotFound();
                case "idempotency" -> AiApiException.idempotencyConflict();
                case "active-turn" -> AiApiException.activeTurnExists();
                case "optimistic-lock" -> AiApiException.optimisticLockConflict();
                case "suggestion-state" -> AiApiException.suggestionStateConflict();
                case "temporary-expired" -> AiApiException.temporarySessionExpired();
                case "stream-expired" -> AiApiException.eventStreamExpired();
                case "input-too-large" -> AiApiException.inputTooLarge();
                case "quota" -> AiApiException.quotaExceeded(Duration.ofSeconds(7));
                case "concurrency" -> AiApiException.concurrencyLimit(Duration.ofSeconds(3));
                case "disabled" -> AiApiException.disabled();
                case "unavailable" -> AiApiException.unavailable(Duration.ofSeconds(5));
                case "runtime" -> AiApiException.runtimeUnavailable(Duration.ofSeconds(4));
                default -> AiApiException.resourceNotFound();
            };
        }

        @GetMapping("/api/agent/test/execution/{reason}")
        String execution(@PathVariable AiExecutionErrorReason reason) {
            Duration retryAfter = reason == AiExecutionErrorReason.AI_DISABLED
                    ? null : Duration.ofSeconds(7);
            throw new AiExecutionException(reason, "sensitive-runtime-message", null, retryAfter);
        }

        @GetMapping("/api/agent/test/provider/{reason}")
        String provider(@PathVariable AiProviderErrorReason reason) {
            throw new AiProviderException(reason, 503, "sensitive-provider-body", null);
        }

        @GetMapping("/api/agent/test/internal")
        String internal() {
            throw new IllegalStateException("private-stack-content");
        }

        @GetMapping("/api/agent/test/request-id")
        String requestId(HttpServletRequest request, HttpServletResponse response) {
            String requestId = AiProblemDetails.requestId(request);
            response.setHeader("X-Test-Server-Request-Id", requestId);
            throw AiApiException.resourceNotFound();
        }

        @GetMapping("/api/agent/test/regex/{id:\\d+}")
        String regexRoute(@PathVariable long id) {
            throw AiApiException.resourceNotFound();
        }

        @GetMapping("/api/admin/moderation/test")
        String moderation() {
            return "ok";
        }
    }

    record TestBody(
            @NotBlank(message = "message is required") String message,
            @NotNull(message = "count is required")
            @Min(value = 1, message = "count must be at least one") Integer count) {
    }

    @AiApi
    static class BaseAiApiType {
    }

    static class InheritedAiApiType extends BaseAiApiType {
    }

    @RestController
    @AiApi
    @Validated
    static class ProxiedValidationController {

        @GetMapping("/api/agent/test/constraint-validation")
        String constraintValidation(
                @RequestParam @Min(value = 1, message = "amount must be at least one") int amount) {
            return Integer.toString(amount);
        }

        @GetMapping("/api/agent/test/dynamic-validation")
        String dynamicValidation(
                @RequestParam
                @Size(min = 100, message = "must not echo ${validatedValue}") String value) {
            return value;
        }
    }
}
