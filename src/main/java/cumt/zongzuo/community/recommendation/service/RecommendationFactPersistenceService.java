package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.entity.UserArticleEvent;
import cumt.zongzuo.community.recommendation.mapper.UserArticleEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Slf4j
@Service
public class RecommendationFactPersistenceService {

    private final UserArticleEventMapper eventMapper;
    private final RecommendationProfileRecoveryService profileRecoveryService;

    public RecommendationFactPersistenceService(UserArticleEventMapper eventMapper,
                                                RecommendationProfileRecoveryService profileRecoveryService) {
        this.eventMapper = eventMapper;
        this.profileRecoveryService = profileRecoveryService;
    }

    @Transactional
    public PersistenceResult persist(UserArticleEvent fact) {
        Objects.requireNonNull(fact, "fact must not be null");
        boolean inserted = false;
        try {
            eventMapper.insert(fact);
            inserted = true;
        } catch (DuplicateKeyException duplicate) {
            log.debug("Recommendation fact already exists: {}", fact.getDedupeKey());
        }
        Long factId = inserted ? fact.getId()
                : eventMapper.selectIdByDedupeKey(fact.getDedupeKey(), fact.getUserId());
        if (factId == null) {
            throw new IllegalStateException("Recommendation fact identity was not found");
        }
        profileRecoveryService.requestRebuild(fact.getUserId(), factId);
        return new PersistenceResult(inserted, factId);
    }

    public record PersistenceResult(boolean inserted, Long factId) {}
}
