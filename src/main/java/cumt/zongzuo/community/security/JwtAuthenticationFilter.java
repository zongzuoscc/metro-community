package cumt.zongzuo.community.security;

import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.service.UserService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserService userService;

    public JwtAuthenticationFilter(JwtService jwtService, UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Long userId = jwtService.parse(token);
                User user = userService.getUserCached(userId);
                if (user != null && !isBanned(user) && !isDeleted(user)) {
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    if (isActive(user)) {
                        authorities.add(new SimpleGrantedAuthority("ACCOUNT_ACTIVE"));
                        if (user.getRole() != null && user.getRole() == 1) {
                            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                        }
                    }
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId, null, authorities));
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // Do not authenticate invalid credentials. The security entry point
                // will return a uniform 401 response when a protected endpoint is hit.
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        String legacyToken = request.getHeader("token");
        return legacyToken == null || legacyToken.isBlank() ? null : legacyToken;
    }

    private boolean isBanned(User user) {
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            return false;
        }
        return user.getBanTime() == null || LocalDateTime.now().isBefore(user.getBanTime());
    }

    /** 旧数据库行在迁移瞬间可能暂时为 null，按 ACTIVE 兼容，迁移完成后列为 NOT NULL。 */
    private boolean isActive(User user) {
        return user.getAccountState() == null || "ACTIVE".equals(user.getAccountState());
    }

    private boolean isDeleted(User user) {
        return "DELETED".equals(user.getAccountState()) || Integer.valueOf(1).equals(user.getDeleted());
    }
}
