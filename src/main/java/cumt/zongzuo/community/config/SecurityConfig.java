package cumt.zongzuo.community.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.web.AiProblemDetails;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 安全配置
 * 职责：负责底层的安全过滤链
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final SecurityProperties securityProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(SecurityProperties securityProperties, JwtAuthenticationFilter jwtAuthenticationFilter,
                          ObjectMapper objectMapper) {
        this.securityProperties = securityProperties;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (AiProblemDetails.isAiPath(request)) {
                                AiProblemDetails.write(request, response, objectMapper, HttpStatus.UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED", false, null);
                            }
                            else {
                                writeError(response, 401, "未登录或登录已过期");
                            }
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            if (AiProblemDetails.isAiPath(request)) {
                                AiProblemDetails.write(request, response, objectMapper, HttpStatus.FORBIDDEN,
                                        "ADMIN_ROLE_REQUIRED", false, null);
                            }
                            else {
                                writeError(response, 403, "无权访问该资源");
                            }
                        }))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**", "/error", "/im/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/tag/**",
                                "/api/article/hot", "/api/article/feed", "/api/article/hot-rank",
                                "/api/article/detail/**", "/api/article/user/**", "/api/article/search",
                                "/api/article/hot-feed", "/api/article/{id}/similar",
                                "/api/comment/list/**", "/api/follow/following/**", "/api/follow/fans/**",
                                "/api/user/profile/**", "/api/user/search").permitAll()
                        .requestMatchers("/api/admin/moderation", "/api/admin/moderation/**").hasRole("ADMIN")
                        .requestMatchers("/api/article/admin/**", "/api/user/admin/**", "/api/report/admin/**").hasRole("ADMIN")
                        // 待注销账号仍需用旧 JWT 进入恢复入口，但不能继续读取或修改其它私人资源。
                        .requestMatchers("/api/user/account-deletion", "/api/user/account-deletion/**").authenticated()
                        .requestMatchers("/api/**").hasAuthority("ACCOUNT_ACTIVE")
                        .anyRequest().denyAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(securityProperties.corsAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "token", "Content-Type", "Last-Event-ID", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Authorization", "Retry-After"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void writeError(HttpServletResponse response, int code, String message) throws java.io.IOException {
        response.setStatus(code);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.error(code, message));
    }
}
