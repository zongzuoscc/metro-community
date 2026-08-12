package cumt.zongzuo.community.ai.agent.turn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {
        "metro.ai.enabled",
        "metro.ai.agent.enabled",
        "metro.ai.agent.turn-recovery-enabled"
}, havingValue = "true")
class AgentTurnRecoverySchedule {

    private final AgentTurnRecovery recovery;

    AgentTurnRecoverySchedule(AgentTurnRecovery recovery) {
        this.recovery = recovery;
    }

    @Scheduled(fixedDelayString = "${metro.ai.agent.turn-recovery-delay-ms:10000}")
    void recoverDueTurns() {
        recovery.recoverDueTurns();
    }
}
