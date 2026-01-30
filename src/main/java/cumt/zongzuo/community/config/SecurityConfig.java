package cumt.zongzuo.community.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 安全配置
 * 职责：负责底层的安全过滤链
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 1. 关闭 CSRF (前后端分离项目必须关闭，否则 POST 请求会被拦截)
                .csrf(csrf -> csrf.disable())

                // 2. 开启 CORS 支持 (非常重要！)
                // 加上这一行，Security 就会去读取我们在 WebMvcConfig 中写的 addCorsMappings 配置
                .cors(Customizer.withDefaults())

                // 3. 允许所有请求通过 Security
                // (因为具体的登录检查我们交给了 LoginInterceptor 去做，这里就全部放行)
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}