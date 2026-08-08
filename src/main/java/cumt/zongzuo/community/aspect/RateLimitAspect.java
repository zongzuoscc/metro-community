package cumt.zongzuo.community.aspect;

import cumt.zongzuo.community.annotation.RateLimit;
import cumt.zongzuo.community.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint point, RateLimit rateLimit) throws Throwable {
        // 获取 Request
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return point.proceed();
        HttpServletRequest request = attributes.getRequest();

        // 1. 获取请求标识 (优先使用用户 ID，没登录则使用 IP)
        String identify = getIpAddress(request);
        Long userId = CurrentUser.idOrNull();
        if (userId != null) {
            identify = "user:" + userId;
        }

        // 2. 构造 Redis Key (格式: rate_limit:业务名:用户标识)
        // 例如: rate_limit:publish_article:user:1
        String key = "rate_limit:" + rateLimit.name() + ":" + identify;

        // 3. Redis 计数
        Long count = stringRedisTemplate.opsForValue().increment(key, 1);
        if (count != null && count == 1) {
            // 如果是第一次访问，设置过期时间
            stringRedisTemplate.expire(key, rateLimit.time(), TimeUnit.SECONDS);
        }

        // 4. 判断是否超限
        if (count != null && count > rateLimit.count()) {
            log.warn("触发限流: IP/User={}, 业务={}", identify, rateLimit.name());
            throw new RuntimeException("操作过于频繁，请稍后再试"); // 全局异常捕获会将其返回给前端
        }

        // 5. 放行
        return point.proceed();
    }

    // 辅助方法：获取真实 IP
    private String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
