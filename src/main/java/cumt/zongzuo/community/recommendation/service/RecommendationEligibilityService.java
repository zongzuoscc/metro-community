package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.mapper.UserArticleEventMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class RecommendationEligibilityService {
    private final UserArticleEventMapper eventMapper;
    private final RecommendationProperties properties;
    private final Clock clock;

    @Autowired
    public RecommendationEligibilityService(UserArticleEventMapper eventMapper,
                                            RecommendationProperties properties,
                                            ObjectProvider<Clock> clockProvider) {
        this(eventMapper, properties, clockProvider.getIfAvailable(Clock::systemDefaultZone));
    }

    RecommendationEligibilityService(UserArticleEventMapper eventMapper,
                                    RecommendationProperties properties, Clock clock) {
        this.eventMapper = eventMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isEligible(Long userId) {
        if (userId == null) return false;
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        return eventMapper.countUserFactsSince(userId, now.minusDays(30)) >= properties.getMinimumUserEvents()
                && eventMapper.countGlobalFactsSince(now.minusDays(90)) >= properties.getMinimumGlobalEvents();
    }
}
