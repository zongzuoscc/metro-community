package cumt.zongzuo.community.recommendation.task;

import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.service.RecommendationProfileRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationProfileRepairTask {

    private final RecommendationProfileRecoveryService recoveryService;
    private final RecommendationProperties properties;

    @Scheduled(fixedDelayString = "${recommendation.profile-repair-delay-ms:30000}",
            initialDelayString = "${recommendation.profile-repair-initial-delay-ms:30000}")
    public void repairProfiles() {
        if (!properties.isProfileRepairEnabled()) {
            return;
        }
        int repaired = recoveryService.repairDueProfiles();
        if (repaired > 0) {
            log.info("Repaired {} stale recommendation profiles", repaired);
        }
    }
}
