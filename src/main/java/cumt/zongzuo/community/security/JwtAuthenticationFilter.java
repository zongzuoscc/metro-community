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
                if (user != null && !isBanned(user)) {
                    List<SimpleGrantedAuthority> authorities = user.getRole() != null && user.getRole() == 1
                            ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                            : List.of();
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
}
