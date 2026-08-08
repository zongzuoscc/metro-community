package cumt.zongzuo.community.utils;

import cumt.zongzuo.community.security.JwtService;
import org.springframework.stereotype.Component;

/**
 * Temporary compatibility facade for legacy call sites. Token signing and
 * parsing are owned by {@link JwtService}; no secret is kept in source code.
 */
@Component
@Deprecated(forRemoval = true)
public class JwtUtils {

    private static JwtService jwtService;

    public JwtUtils(JwtService jwtService) {
        JwtUtils.jwtService = jwtService;
    }

    /**
     * 生成 Token
     * @param userId 用户ID
     * @return 加密后的 Token 字符串
     */
    public static String generateToken(Long userId) {
        return service().generate(userId);
    }

    /**
     * 解析 Token 获取用户ID
     */
    public static Long getUserId(String token) {
        return service().parse(token);
    }

    private static JwtService service() {
        if (jwtService == null) {
            throw new IllegalStateException("JWT 服务尚未初始化");
        }
        return jwtService;
    }
}
