package cumt.zongzuo.community.ai.web;

import cumt.zongzuo.community.ai.provider.AiProviderErrorReason;
import cumt.zongzuo.community.ai.provider.AiProviderException;
import cumt.zongzuo.community.ai.runtime.AiExecutionErrorReason;
import cumt.zongzuo.community.ai.runtime.AiExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(annotations = AiApi.class)
public class AiProblemDetailAdvice extends ResponseEntityExceptionHandler {

    private static final int DEFAULT_RETRY_AFTER_SECONDS = 1;

    @ExceptionHandler(AiApiException.class)
    ResponseEntity<Object> handleAiApiException(AiApiException error, HttpServletRequest request) {
        Integer retryAfter = seconds(error.retryAfter().orElse(null));
        return response(request, error.status(), error.code(), error.retryable(), retryAfter, List.of());
    }

    @ExceptionHandler(AiExecutionException.class)
    ResponseEntity<Object> handleAiExecutionException(AiExecutionException error,
                                                       HttpServletRequest request) {
        ErrorContract contract = executionContract(error.reason());
        Integer retryAfter = contract.retryable()
                ? secondsOrDefault(error.retryAfter().orElse(null)) : null;
        return response(request, contract.status(), contract.code(), contract.retryable(),
                retryAfter, List.of());
    }

    @ExceptionHandler(AiProviderException.class)
    ResponseEntity<Object> handleAiProviderException(AiProviderException error,
                                                      HttpServletRequest request) {
        ErrorContract contract = providerContract(error.reason());
        Integer retryAfter = contract.retryable() ? DEFAULT_RETRY_AFTER_SECONDS : null;
        return response(request, contract.status(), contract.code(), contract.retryable(),
                retryAfter, List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolationException(ConstraintViolationException error,
                                                               HttpServletRequest request) {
        List<AiProblemDetails.FieldError> fieldErrors = error.getConstraintViolations().stream()
                .map(violation -> new AiProblemDetails.FieldError(
                        leafName(violation.getPropertyPath().toString()),
                        safeMessage(violation.getMessage(), violation.getInvalidValue())))
                .toList();
        return validationResponse(request, fieldErrors);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnknownAiException(Exception error, HttpServletRequest request) {
        String requestId = AiProblemDetails.requestId(request);
        log.error("AI API request failed requestId={} exceptionType={}",
                requestId, error.getClass().getName());
        return response(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                false, null, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException error, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return response(servletRequest(request), HttpStatus.BAD_REQUEST, "MALFORMED_JSON",
                false, null, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException error, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<AiProblemDetails.FieldError> fieldErrors = error.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        return validationResponse(servletRequest(request), fieldErrors);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException error, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return validationResponse(servletRequest(request), List.of(
                new AiProblemDetails.FieldError(error.getParameterName(), "is required")));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException error,
                                                         HttpHeaders headers,
                                                         HttpStatusCode status,
                                                         WebRequest request) {
        String field = error instanceof MethodArgumentTypeMismatchException argumentError
                ? argumentError.getName() : "parameter";
        return validationResponse(servletRequest(request), List.of(
                new AiProblemDetails.FieldError(field, "has an invalid type")));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException error, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<AiProblemDetails.FieldError> errors = new ArrayList<>();
        error.getParameterValidationResults().forEach(result -> {
            String field = parameterName(result.getMethodParameter());
            result.getResolvableErrors().forEach(resolvable -> errors.add(
                    new AiProblemDetails.FieldError(field,
                            safeMessage(resolvable.getDefaultMessage(), result.getArgument()))));
        });
        error.getCrossParameterValidationResults().forEach(resolvable -> errors.add(
                new AiProblemDetails.FieldError("request", safeMessage(resolvable.getDefaultMessage()))));
        return validationResponse(servletRequest(request), errors);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body,
                                                              HttpHeaders headers,
                                                              HttpStatusCode status,
                                                              WebRequest request) {
        HttpServletRequest servletRequest = servletRequest(request);
        return switch (status.value()) {
            case 400 -> validationResponse(servletRequest, List.of());
            case 404 -> response(servletRequest, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                    false, null, List.of());
            case 405 -> response(servletRequest, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    false, null, List.of());
            case 413 -> response(servletRequest, HttpStatus.PAYLOAD_TOO_LARGE, "AI_INPUT_TOO_LARGE",
                    false, null, List.of());
            case 415 -> response(servletRequest, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE", false, null, List.of());
            default -> response(servletRequest, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    false, null, List.of());
        };
    }

    private ResponseEntity<Object> validationResponse(HttpServletRequest request,
                                                       List<AiProblemDetails.FieldError> errors) {
        return response(request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                false, null, errors);
    }

    private AiProblemDetails.FieldError fieldError(FieldError error) {
        return new AiProblemDetails.FieldError(error.getField(),
                safeMessage(error.getDefaultMessage(), error.getRejectedValue()));
    }

    private String parameterName(MethodParameter parameter) {
        String name = parameter.getParameterName();
        return name == null || name.isBlank() ? "parameter" + parameter.getParameterIndex() : name;
    }

    private String safeMessage(String message) {
        String normalized = normalize(message);
        if (normalized.isBlank()) {
            return "must be valid";
        }
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private String safeMessage(String message, Object rejectedValue) {
        String normalizedMessage = normalize(message);
        if (rejectedValue == null) {
            return safeMessage(normalizedMessage);
        }
        String rejected;
        try {
            rejected = String.valueOf(rejectedValue);
        }
        catch (RuntimeException ignored) {
            return "must be valid";
        }
        if (rejected.isBlank()) {
            return safeMessage(normalizedMessage);
        }
        String normalizedRejected = normalize(rejected);
        if (normalizedMessage.contains(rejected)
                || (!normalizedRejected.isBlank() && normalizedMessage.contains(normalizedRejected))) {
            return "must be valid";
        }
        return safeMessage(normalizedMessage);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String leafName(String propertyPath) {
        int separator = propertyPath.lastIndexOf('.');
        String leaf = separator >= 0 ? propertyPath.substring(separator + 1) : propertyPath;
        return leaf.isBlank() ? "parameter" : leaf;
    }

    private ResponseEntity<Object> response(HttpServletRequest request, HttpStatus status,
                                            String code, boolean retryable,
                                            Integer retryAfterSeconds,
                                            List<AiProblemDetails.FieldError> fieldErrors) {
        ResponseEntity<org.springframework.http.ProblemDetail> response = AiProblemDetails.response(
                request, status, code, retryable, retryAfterSeconds, fieldErrors);
        return new ResponseEntity<>(response.getBody(), response.getHeaders(), response.getStatusCode());
    }

    private HttpServletRequest servletRequest(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }

    private static int secondsOrDefault(Duration duration) {
        Integer seconds = seconds(duration);
        return seconds == null ? DEFAULT_RETRY_AFTER_SECONDS : seconds;
    }

    private static Integer seconds(Duration duration) {
        if (duration == null) {
            return null;
        }
        long seconds = duration.getSeconds();
        long rounded = seconds >= Long.MAX_VALUE - 1
                ? Long.MAX_VALUE : seconds + (duration.getNano() == 0 ? 0 : 1);
        rounded = Math.max(1L, rounded);
        return rounded > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rounded;
    }

    private static ErrorContract executionContract(AiExecutionErrorReason reason) {
        return switch (reason) {
            case AI_DISABLED -> fixed(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED");
            case INVALID_INVOCATION -> fixed(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            case INPUT_TOO_LARGE -> fixed(HttpStatus.PAYLOAD_TOO_LARGE, "AI_INPUT_TOO_LARGE");
            case DEADLINE_EXCEEDED -> retryable(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            case QUOTA_EXCEEDED -> retryable(HttpStatus.TOO_MANY_REQUESTS, "AI_QUOTA_EXCEEDED");
            case AGENT_RUNTIME_UNAVAILABLE -> retryable(
                    HttpStatus.SERVICE_UNAVAILABLE, "AGENT_RUNTIME_UNAVAILABLE");
            case BULKHEAD_FULL -> retryable(HttpStatus.TOO_MANY_REQUESTS, "AI_CONCURRENCY_LIMIT");
            case CIRCUIT_OPEN -> retryable(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            case TIMEOUT -> retryable(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            case CANCELLED -> retryable(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_RUNTIME_UNAVAILABLE");
            case PROVIDER_FAILURE -> retryable(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
        };
    }

    private static ErrorContract providerContract(AiProviderErrorReason reason) {
        return switch (reason) {
            case AI_DISABLED -> fixed(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED");
            case AI_UNAVAILABLE -> retryable(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            case CONNECTION_FAILURE -> retryable(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            case TIMEOUT -> retryable(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            case RATE_LIMITED -> retryable(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            case RETRYABLE_PROVIDER_FAILURE -> retryable(
                    HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            case NON_RETRYABLE_PROVIDER_FAILURE -> fixed(
                    HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            case MALFORMED_RESPONSE -> fixed(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
            case EMPTY_RESPONSE -> fixed(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
        };
    }

    private static ErrorContract fixed(HttpStatus status, String code) {
        return new ErrorContract(status, code, false);
    }

    private static ErrorContract retryable(HttpStatus status, String code) {
        return new ErrorContract(status, code, true);
    }

    private record ErrorContract(HttpStatus status, String code, boolean retryable) {
    }
}
