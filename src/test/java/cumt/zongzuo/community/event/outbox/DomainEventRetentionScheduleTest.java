package cumt.zongzuo.community.event.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DomainEventRetentionScheduleTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(DomainEventRetentionTask.class, () -> mock(DomainEventRetentionTask.class))
            .withUserConfiguration(DomainEventRetentionSchedule.class);

    @Test
    void schedulingIsDisabledByDefault() {
        context.run(application -> assertThat(application)
                .doesNotHaveBean(DomainEventRetentionSchedule.class));
        assertThat(Arrays.stream(DomainEventRetentionTask.class.getDeclaredMethods()))
                .noneMatch(method -> method.isAnnotationPresent(Scheduled.class));
    }

    @Test
    void operatorMustExplicitlyEnableScheduling() {
        context.withPropertyValues("metro.events.retention.scheduling-enabled=true")
                .run(application -> assertThat(application)
                        .hasSingleBean(DomainEventRetentionSchedule.class));
    }
}
