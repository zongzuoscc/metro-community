package cumt.zongzuo.community.recommendation.task;

import cumt.zongzuo.community.recommendation.service.RecommendationMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

@Slf4j
@Component
public class RecommendationMetricsTask {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final RecommendationMetricsService metricsService;
    private final Clock clock;

    public RecommendationMetricsTask(RecommendationMetricsService metricsService, Clock clock) {
        this.metricsService = metricsService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 5 0 * * ?", zone = "Asia/Shanghai")
    public void run() {
        LocalDate date = null;
        try {
            date = LocalDate.now(clock.withZone(SHANGHAI)).minusDays(1);
            RecommendationMetricsService.DailySnapshot snapshot = metricsService.dailySnapshot(date);
            Map<String, Long> deliveries = snapshot.deliveryCounts();
            Map<String, Long> events = snapshot.eventCounts();
            log.info("recommendation_metrics date={} status=available "
                            + "delivery_follow={} delivery_tag={} delivery_similar={} delivery_explore={} "
                            + "delivery_chronological={} event_view={} event_like={} event_collect={} "
                            + "event_comment={} event_follow_author={}",
                    date,
                    deliveries.get("FOLLOW"), deliveries.get("TAG"), deliveries.get("SIMILAR"),
                    deliveries.get("EXPLORE"), deliveries.get("CHRONOLOGICAL"),
                    events.get("VIEW"), events.get("LIKE"), events.get("COLLECT"),
                    events.get("COMMENT"), events.get("FOLLOW_AUTHOR"));
        } catch (RuntimeException exception) {
            log.warn("recommendation_metrics date={} status=unavailable",
                    date == null ? "unknown" : date);
        }
    }
}
