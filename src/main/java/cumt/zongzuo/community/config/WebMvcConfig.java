package cumt.zongzuo.community.config;

import cumt.zongzuo.community.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 核心配置类
 * 职责：
 * 1. 配置跨域 (CORS)
 * 2. 配置拦截器 (Interceptor)
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    // --- 整合部分 1：跨域配置 (原 CorsConfig 的内容) ---
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")               // 允许跨域的接口：所有
                .allowedOriginPatterns("*")      // 允许的来源：所有 (生产环境建议换成具体域名)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的方法
                .allowedHeaders("*")             // 允许的请求头
                .allowCredentials(true)          // 允许携带 Cookie/Token
                .maxAge(3600);                   // 预检请求缓存 1 小时
    }

    // --- 整合部分 2：拦截器配置 ---
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")  // 拦截所有路径
                // 配置不拦截的路径 (白名单)
                .excludePathPatterns(
                        "/api/auth/**",      // 登录、注册、验证码接口
                        "/error",            // Spring Boot 默认错误也
                        "/images/**",        // 静态资源
                        "/swagger-ui.html",  // 如果以后加 Swagger 文档需放行
                        "/swagger-resources/**",
                        "/webjars/**",
                        "/api/article/**"
                );
    }
}