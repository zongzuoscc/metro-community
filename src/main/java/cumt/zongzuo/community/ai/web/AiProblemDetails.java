package cumt.zongzuo.community.ai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Shared construction and serialization for the RFC 9457 AI error contract. */
public final class AiProblemDetails {

    private static final String REQUEST_ID_ATTRIBUTE = AiProblemDetails.class.getName() + ".requestId";

    private AiProblemDetails() {
    }

    public record FieldError(String field, String message) {
        public FieldError {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(message, "message");
        }
    }

    public static ProblemDetail create(HttpStatus status, String code, String requestId,
                                       boolean retryable, Integer retryAfterSeconds,
                                       List<FieldError> fieldErrors) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(requestId, "requestId");
        List<FieldError> stableErrors = fieldErrors == null ? List.of() : fieldErrors.stream()
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail(code));
        problem.setType(URI.create("about:blank"));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("requestId", requestId);
        problem.setProperty("retryable", retryable);
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        problem.setProperty("fieldErrors", stableErrors);
        return problem;
    }

    public static boolean isAiPath(HttpServletRequest request) {
        String path = requestPath(request);
        return isPathOrChild(path, "/api/agent")
                || isPathOrChild(path, "/api/admin/moderation");
    }

    public static ResponseEntity<ProblemDetail> response(HttpServletRequest request, HttpStatus status,
                                                          String code, boolean retryable,
                                                          Integer retryAfterSeconds,
                                                          List<FieldError> fieldErrors) {
        ProblemDetail problem = problem(request, status, code, retryable, retryAfterSeconds, fieldErrors);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfterSeconds != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString());
        }
        return new ResponseEntity<>(problem, headers, status);
    }

    public static void write(HttpServletRequest request, HttpServletResponse response,
                             ObjectMapper objectMapper, HttpStatus status, String code,
                             boolean retryable, Integer retryAfterSeconds) throws IOException {
        ProblemDetail problem = problem(request, status, code, retryable, retryAfterSeconds, List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (retryAfterSeconds != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString());
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    public static String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (existing instanceof String value && !value.isBlank()) {
            return value;
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, generated);
        return generated;
    }

    private static ProblemDetail problem(HttpServletRequest request, HttpStatus status, String code,
                                         boolean retryable, Integer retryAfterSeconds,
                                         List<FieldError> fieldErrors) {
        ProblemDetail problem = create(status, code, requestId(request), retryable,
                retryAfterSeconds, fieldErrors);
        problem.setInstance(instanceUri(request));
        return problem;
    }

    private static URI instanceUri(HttpServletRequest request) {
        String path = instancePath(request);
        try {
            return URI.create(path);
        }
        catch (IllegalArgumentException invalidEncodedPath) {
            try {
                return new URI(null, null, path, null);
            }
            catch (URISyntaxException invalidPath) {
                return URI.create("/");
            }
        }
    }

    private static String instancePath(HttpServletRequest request) {
        Object matchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (matchingPattern != null) {
            String pattern = matchingPattern.toString();
            if (pattern.startsWith("/") && !pattern.equals("/**")) {
                return pattern;
            }
        }
        return requestPath(request);
    }

    private static String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.isEmpty() ? "/" : path;
    }

    private static boolean isPathOrChild(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static String detail(String code) {
        return switch (code) {
            case "MALFORMED_JSON" -> "The request body is malformed.";
            case "VALIDATION_FAILED" -> "The request did not pass validation.";
            case "AUTHENTICATION_REQUIRED" -> "Authentication is required.";
            case "ADMIN_ROLE_REQUIRED" -> "Administrator permission is required.";
            case "RESOURCE_NOT_FOUND" -> "The requested resource was not found.";
            case "IDEMPOTENCY_CONFLICT" -> "The idempotency key conflicts with an earlier request.";
            case "ACTIVE_TURN_EXISTS" -> "Another Agent turn is already active.";
            case "OPTIMISTIC_LOCK_CONFLICT" -> "The resource changed before this request completed.";
            case "SUGGESTION_STATE_CONFLICT" -> "The writing suggestion is no longer applicable.";
            case "TEMPORARY_SESSION_EXPIRED" -> "The temporary session has expired.";
            case "EVENT_STREAM_EXPIRED" -> "The requested event stream prefix has expired.";
            case "AI_INPUT_TOO_LARGE" -> "The AI input exceeds the configured limit.";
            case "AI_QUOTA_EXCEEDED" -> "The AI request quota has been exceeded.";
            case "AI_CONCURRENCY_LIMIT" -> "The AI concurrency limit has been reached.";
            case "AI_DISABLED" -> "This AI capability is disabled.";
            case "AI_UNAVAILABLE" -> "The AI capability is temporarily unavailable.";
            case "AGENT_RUNTIME_UNAVAILABLE" -> "The Agent runtime is temporarily unavailable.";
            case "METHOD_NOT_ALLOWED" -> "The HTTP method is not supported for this resource.";
            case "UNSUPPORTED_MEDIA_TYPE" -> "The request media type is not supported.";
            case "INTERNAL_ERROR" -> "An internal error prevented the request from completing.";
            default -> "The request could not be completed.";
        };
    }
}
