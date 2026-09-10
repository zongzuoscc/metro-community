package cumt.zongzuo.community.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityStreamHeadersTest {
    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class Mvc { }

    @Test void securityHeadersAreWrittenBeforeAnyAsyncBodyCanStart() {
        new WebApplicationContextRunner().withUserConfiguration(SecurityConfig.class, Mvc.class)
                .withBean(SecurityProperties.class, () -> new SecurityProperties("test-only-secret-".repeat(4), null, null))
                .withBean(JwtAuthenticationFilter.class, () -> org.mockito.Mockito.mock(JwtAuthenticationFilter.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var filter = context.getBean(SecurityFilterChain.class).getFilters().stream()
                            .filter(HeaderWriterFilter.class::isInstance).map(HeaderWriterFilter.class::cast)
                            .findFirst().orElseThrow();
                    var response = new MockHttpServletResponse();
                    filter.doFilter(new MockHttpServletRequest("GET", "/api/agent/turns/1/events"), response,
                            (request, downstream) -> {
                                // 断言发生在下游尚未返回时。延迟写头的旧配置必定失败，不依赖概率性 NPE。
                                assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
                                assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
                                assertThat(response.getHeader("Cache-Control")).contains("no-store");
                                downstream.getOutputStream().flush();
                            });
                    assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
                });
    }
}
