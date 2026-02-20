package cumt.zongzuo.community.exception;

import cumt.zongzuo.community.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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