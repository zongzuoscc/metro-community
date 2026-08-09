package cumt.zongzuo.community.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    public Clock applicationClock() {
        return Clock.systemDefaultZone();
    }
}
