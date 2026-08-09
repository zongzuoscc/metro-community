package cumt.zongzuo.community.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigurationTest {

    @Test
    void providesOneProductionClockUsingTheJvmDefaultZoneWithoutATestOverride() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ClockConfiguration.class);
            context.refresh();

            assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.systemDefault());
        }
    }
}
