package cumt.zongzuo.community.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.security.Key;
import java.util.Date;

public class JwtUtils {

    // 密钥（随便写一长串，这里为了方便直接写死）
    // 长度必须足够长（至少32个字符），否则会报错
    private static final String SECRET = "CumtZongzuoCommunityProjectSecretKey2026";
    private static final Key KEY = Keys.hmacShaKeyFor(SECRET.getBytes());

    // Token 有效期：7天 (单位毫秒)
    private static final long EXPIRE = 604800000L;

    /**
     * 生成 Token
     * @param userId 用户ID
     * @return 加密后的 Token 字符串
     */
    public static String generateToken(Long userId) {
        return Jwts.builder()
                .setSubject(String.valueOf(userId)) // 把用户ID存进去
                .setIssuedAt(new Date()) // 签发时间
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRE)) // 过期时间
                .signWith(KEY, SignatureAlgorithm.HS256) // 签名算法
                .compact();
    }

    /**
     * 解析 Token 获取用户ID
     */
    public static Long getUserId(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(KEY)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return Long.parseLong(claims.getSubject());
    }
}