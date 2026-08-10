package cumt.zongzuo.community.event.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "metro.events.retention.scheduling-enabled",
        havingValue = "true")
class DomainEventRetentionSchedule {

    private final DomainEventRetentionTask task;

    DomainEventRetentionSchedule(DomainEventRetentionTask task) {
        this.task = task;
    }

    @Scheduled(cron = "${metro.events.retention.cron:0 30 4 * * *}", zone = "UTC")
    void run() {
        task.runOnce();
    }
}
