package cumt.zongzuo.community.config;

import org.springframework.context.annotation.Configuration;

/**
 * MVC 扩展的预留位置。认证与 CORS 统一由 Spring Security 过滤链处理，
 * 避免两套拦截机制产生不一致的鉴权结果。
 */
@Configuration
public class WebMvcConfig {
}
