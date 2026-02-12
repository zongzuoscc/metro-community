package cumt.zongzuo.community.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.service.UserService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.LocalDateTime;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 放行 OPTIONS 请求 (跨域预检)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 2. 获取 Token
        String token = request.getHeader("token");
        if (token == null || token.isEmpty()) {
            returnError(response, "未登录");
            return false;
        }

        // 3. 校验 Token 签名
        Long userId;
        try {
            userId = JwtUtils.getUserId(token);
        } catch (Exception e) {
            returnError(response, "Token无效或已过期");
            return false;
        }

        // 4. 【核心新增】检查用户状态
        // 这里使用 getUserCached 走 Redis 缓存，性能高
        User user = userService.getUserCached(userId);

        if (user == null) {
            returnError(response, "用户不存在");
            return false;
        }

        if (user.getStatus() != null && user.getStatus() == 1) {
            // 检查是否有封禁时间限制
            if (user.getBanTime() != null) {
                if (LocalDateTime.now().isBefore(user.getBanTime())) {
                    // 还在封禁期内 -> 强制下线
                    returnError(response, "账号已被封禁，解封时间：" + user.getBanTime().toString().replace("T", " "));
                    return false;
                } else {
                    // 封禁时间已过 -> 自动解封 (可选：这里顺手更新库，或者等下次管理员操作)
                    // 为了性能，这里可以暂时放行，或者异步去更新数据库 status=0
                    user.setStatus(0);
                }
            } else {
                // banTime 为 null 但 status=1 -> 永久封禁
                returnError(response, "账号已被永久封禁");
                return false;
            }
        }

        // 验证通过，放行
        return true;
    }

    // 辅助方法：返回 JSON 错误信息
    private void returnError(HttpServletResponse response, String msg) throws Exception {
        response.setContentType("application/json;charset=utf-8");
        Result<String> result = Result.error(401, msg); // 401 未授权
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}