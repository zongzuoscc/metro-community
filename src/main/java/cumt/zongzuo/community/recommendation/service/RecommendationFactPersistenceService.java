package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.entity.UserArticleEvent;
import cumt.zongzuo.community.recommendation.mapper.UserArticleEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class RecommendationFactPersistenceService {

    private final UserArticleEventMapper eventMapper;
    private final RecommendationProfileRecoveryService profileRecoveryService;
    private final JdbcTemplate jdbc;

    public RecommendationFactPersistenceService(UserArticleEventMapper eventMapper,
                                                RecommendationProfileRecoveryService profileRecoveryService,
                                                JdbcTemplate jdbc) {
        this.eventMapper = eventMapper;
        this.profileRecoveryService = profileRecoveryService;
        this.jdbc = jdbc;
    }

    @Transactional
    public PersistenceResult persist(UserArticleEvent fact) {
        Objects.requireNonNull(fact, "fact must not be null");

        // 与账号注销清理使用同一 sys_user 行锁。这样无论延迟消息先执行还是清理先执行，
        // 最终顺序都确定：事实要么先落库后被清理，要么在看到 DELETED 后直接丢弃，
        // 不会在 purgeDue 提交之后重新创建个人推荐画像。
        List<String> states = jdbc.query("""
                SELECT account_state FROM sys_user WHERE id=? FOR UPDATE
                """, (rs, rowNum) -> rs.getString(1), fact.getUserId());
        if (states.isEmpty() || "DELETED".equals(states.getFirst())) {
            log.debug("Discarding recommendation event for deleted user {}", fact.getUserId());
            return PersistenceResult.discarded();
        }
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
        return new PersistenceResult(true, inserted, factId);
    }

    public record PersistenceResult(boolean accepted, boolean inserted, Long factId) {
        private static PersistenceResult discarded() {
            return new PersistenceResult(false, false, null);
        }
    }
}
