package cumt.zongzuo.community.exception;

import cumt.zongzuo.community.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<String>> handleNoResourceFoundException(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(Result.error(404, "资源不存在"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<String>> handleRequestBindingException(Exception e) {
        log.warn("请求参数解析失败: {}", e.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(Result.error(400, "请求参数格式不正确"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<String>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(Result.error(400, message));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<String>> handleResponseStatusException(ResponseStatusException e) {
        log.warn("请求被拒绝: {}", e.getReason());
        return ResponseEntity.status(e.getStatusCode())
                .body(Result.error(e.getStatusCode().value(), e.getReason()));
    }

    /**
     * 捕获我们手动抛出的 RuntimeException (业务异常)
     */
    @ExceptionHandler(RuntimeException.class)
    public Result<String> handleRuntimeException(RuntimeException e) {
        // 打印简略日志
        log.warn("业务异常拦截: {}", e.getMessage());
        // 将异常信息包装进 Result 中返回，默认 code 为 500
        return Result.error(e.getMessage());
    }

    /**
     * 捕获其他未知的系统异常 (兜底)
     */
    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        // 未知异常打印完整堆栈日志，方便排查 BUG
        log.error("系统未知异常: ", e);
        return Result.error("服务器开小差了，请稍后再试");
    }
}
