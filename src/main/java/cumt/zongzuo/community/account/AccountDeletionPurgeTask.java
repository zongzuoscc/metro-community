package cumt.zongzuo.community.account;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只有 Worker 进程运行到期清理，Backend 与 Agent 不会争抢定时任务。 */
@Component
@Profile("worker-service")
@ConditionalOnProperty(prefix = "metro.account-deletion", name = "purge-enabled", havingValue = "true")
public class AccountDeletionPurgeTask {

    private final AccountDeletionService service;

    public AccountDeletionPurgeTask(AccountDeletionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${metro.account-deletion.purge-delay-ms:60000}")
    public void purgeDueAccounts() {
        service.purgeDue(50);
    }
}
