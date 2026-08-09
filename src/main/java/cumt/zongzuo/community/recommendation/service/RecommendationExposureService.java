package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.entity.RecommendationExposure;
import cumt.zongzuo.community.recommendation.mapper.RecommendationExposureMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RecommendationExposureService {

    private final RecommendationExposureMapper mapper;

    public Long record(String sessionId, Long userId, RecommendationCandidate candidate) {
        return recordOne(sessionId, userId, candidate);
    }

    @Transactional
    public List<Long> recordPage(String sessionId, Long userId,
                                 List<RecommendationCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> recordOne(sessionId, userId, candidate))
                .toList();
    }

    private Long recordOne(String sessionId, Long userId, RecommendationCandidate candidate) {
        RecommendationExposure exposure = new RecommendationExposure();
        exposure.setUserId(userId);
        exposure.setArticleId(candidate.articleId());
        exposure.setSessionId(sessionId);
        exposure.setSource("CHRONOLOGICAL");
        exposure.setTagAffinity(candidate.tagAffinity());
        exposure.setAuthorAffinity(candidate.authorAffinity());
        exposure.setSimilarScore(candidate.similarScore());
        exposure.setHeatScore(candidate.heatScore());
        exposure.setFreshnessScore(candidate.freshnessScore());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        exposure.setExposedAt(now);
        exposure.setCreateTime(now);
        mapper.insertIfAbsent(exposure);
        RecommendationExposure stored = mapper.selectByIdentity(userId, candidate.articleId(), sessionId);
        if (stored == null) {
            throw new IllegalStateException("Unable to persist recommendation exposure");
        }
        return stored.getId();
    }

    public RecommendationExposure get(Long exposureId) {
        return exposureId == null ? null : mapper.selectById(exposureId);
    }
}
