package cumt.zongzuo.community.interceptor;

import cumt.zongzuo.community.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 放行 OPTIONS 请求 (跨域预检)
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }

        // 2. 如果不是映射到方法直接通过 (比如静态资源)
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // 3. 获取 Token (约定 Header key 为 "Authorization" 或 "token")
        String token = request.getHeader("token");
        if (token == null) {
            token = request.getHeader("Authorization");
        }

        // 4. 校验 Token
        if (token != null && !token.isEmpty()) {
            try {
                // 解析成功，说明是登录用户
                Long userId = JwtUtils.getUserId(token);
                // 这里可以将 userId 放入 ThreadLocal 以便后续 Controller 使用 (进阶做法，暂时先不写)
                return true; // 放行
            } catch (Exception e) {
                // Token 过期或非法
            }
        }

        // 5. 校验失败，设置响应状态码 401 (未授权)
        response.setStatus(401);
        return false; // 拦截
    }
}