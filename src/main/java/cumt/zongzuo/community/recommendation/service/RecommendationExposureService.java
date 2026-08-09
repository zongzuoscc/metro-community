package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.dto.RecommendationExposureDraft;
import cumt.zongzuo.community.recommendation.dto.RecommendationFeatureSnapshot;
import cumt.zongzuo.community.recommendation.entity.RecommendationExposure;
import cumt.zongzuo.community.recommendation.mapper.RecommendationExposureMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class RecommendationExposureService {

    private final RecommendationExposureMapper mapper;
    private final Clock clock;

    @Autowired
    public RecommendationExposureService(RecommendationExposureMapper mapper, ObjectProvider<Clock> clockProvider) {
        this(mapper, clockProvider.getIfAvailable(Clock::systemDefaultZone));
    }

    RecommendationExposureService(RecommendationExposureMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public List<Long> recordPage(String sessionId, Long userId,
                                 List<RecommendationExposureDraft> drafts) {
        return drafts.stream()
                .map(draft -> recordOne(sessionId, userId, draft))
                .toList();
    }

    private Long recordOne(String sessionId, Long userId, RecommendationExposureDraft draft) {
        RecommendationFeatureSnapshot snapshot = draft.snapshot();
        RecommendationExposure exposure = new RecommendationExposure();
        exposure.setUserId(userId);
        exposure.setArticleId(draft.articleId());
        exposure.setSessionId(sessionId);
        exposure.setSource(draft.source());
        exposure.setTagAffinity(snapshot.tagAffinity());
        exposure.setAuthorAffinity(snapshot.authorAffinity());
        exposure.setSimilarScore(snapshot.similarScore());
        exposure.setHeatScore(snapshot.heatScore());
        exposure.setFreshnessScore(snapshot.freshnessScore());
        exposure.setSourceFollow(snapshot.sourceFollow());
        exposure.setSourceTag(snapshot.sourceTag());
        exposure.setSourceSimilar(snapshot.sourceSimilar());
        exposure.setSourceExplore(snapshot.sourceExplore());
        exposure.setBaselineScore(draft.baselineScore());
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        exposure.setExposedAt(now);
        exposure.setCreateTime(now);
        mapper.insertIfAbsent(exposure);
        RecommendationExposure stored = mapper.selectByIdentity(userId, draft.articleId(), sessionId);
        if (stored == null) {
            throw new IllegalStateException("Unable to persist recommendation exposure");
        }
        return stored.getId();
    }

    public RecommendationExposure get(Long exposureId) {
        return exposureId == null ? null : mapper.selectById(exposureId);
    }
}
