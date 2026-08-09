package cumt.zongzuo.community.recommendation.task;

import cumt.zongzuo.community.recommendation.training.RecommendationTrainingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RecommendationTrainingTask {
    private final RecommendationTrainingService training;
    public RecommendationTrainingTask(RecommendationTrainingService training) { this.training = training; }
    @Scheduled(cron = "0 15 2 * * ?", zone = "Asia/Shanghai")
    public void train() {
        try { log.info("Recommendation training result: {}", training.trainAndPublish()); }
        catch (RuntimeException exception) { log.warn("Recommendation training failed", exception); }
    }
}
