package cumt.zongzuo.community.recommendation.training;

import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class RecommendationTrainingDataset {
    private static final int FACT_BATCH_SIZE = 1_000;
    private static final int MAX_COHORT_SIZE = 50_000;
    private final JdbcTemplate jdbc;
    private final RecommendationProperties properties;
    private final Clock clock;

    public RecommendationTrainingDataset(JdbcTemplate jdbc, RecommendationProperties properties,
                                         ObjectProvider<Clock> clocks) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clocks.getIfAvailable(Clock::systemDefaultZone);
    }

    public Dataset load() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime windowStart = now.minusDays(properties.getModelWindowDays());
        LocalDateTime matureBefore = now.minusDays(properties.getLabelWindowDays());
        int cohortLimit = Math.max(1, Math.min(MAX_COHORT_SIZE, properties.getTrainingSampleLimit()));
        int perUserLimit = Math.max(1, Math.min(cohortLimit, properties.getTrainingMaxSamplesPerUser()));
        List<Row> cohort = jdbc.query("""
                WITH per_user AS (
                  SELECT e.id,e.exposed_at,e.baseline_score,
                    e.tag_affinity,e.author_affinity,e.similar_score,e.heat_score,e.freshness_score,
                    e.source_follow,e.source_tag,e.source_similar,e.source_explore,
                    ROW_NUMBER() OVER (PARTITION BY e.user_id ORDER BY e.exposed_at DESC,e.id DESC) AS user_position
                  FROM recommendation_exposure e
                  WHERE e.exposed_at >= ? AND e.exposed_at < ? AND e.baseline_score IS NOT NULL
                )
                SELECT id,exposed_at,baseline_score,
                  tag_affinity,author_affinity,similar_score,heat_score,freshness_score,
                  source_follow,source_tag,source_similar,source_explore
                FROM per_user WHERE user_position <= ?
                ORDER BY exposed_at DESC,id DESC LIMIT ?
                """, (rs, rowNumber) -> new Row(rs.getLong(1), rs.getObject(2, LocalDateTime.class),
                rs.getDouble(3), new RecommendationFeatureVector(rs.getDouble(4), rs.getDouble(5),
                rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10),
                rs.getDouble(11), rs.getDouble(12))), windowStart, matureBefore, perUserLimit, cohortLimit);
        if (cohort.isEmpty()) {
            Integer matureExposureExists = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM recommendation_exposure
                      WHERE exposed_at >= ? AND exposed_at < ? LIMIT 1)
                    """, Integer.class, windowStart, matureBefore);
            Status status = Integer.valueOf(1).equals(matureExposureExists)
                    ? Status.NO_REAL_BASELINE : Status.NO_DATA;
            return new Dataset(status, List.of(), List.of());
        }

        Set<Long> cohortIds = new HashSet<>(cohort.size());
        cohort.forEach(row -> cohortIds.add(row.id));
        AttributionResult attribution = attributeFacts(windowStart, now, cohortIds);
        if (!attribution.complete()) {
            return new Dataset(Status.FACT_SCAN_LIMIT_EXCEEDED, List.of(), List.of());
        }
        Set<Long> positiveIds = attribution.positiveIds();
        List<Row> chronological = cohort.stream()
                .sorted(Comparator.comparing(Row::exposedAt).thenComparing(Row::id))
                .toList();
        List<TrainingExample> all = chronological.stream()
                .map(row -> new TrainingExample(row.features, positiveIds.contains(row.id) ? 1 : 0, row.baseline))
                .toList();
        int split = (int) Math.floor(all.size() * .8D);
        return new Dataset(Status.READY, all.subList(0, split), all.subList(split, all.size()));
    }

    private AttributionResult attributeFacts(LocalDateTime windowStart, LocalDateTime now, Set<Long> cohortIds) {
        Set<Long> positiveIds = new HashSet<>();
        LocalDateTime cursorTime = windowStart.minusSeconds(1);
        long cursorId = 0L;
        int scanLimit = Math.max(1, properties.getTrainingFactScanLimit());
        int scanned = 0;
        while (true) {
            int remaining = scanLimit - scanned;
            int queryLimit = remaining >= FACT_BATCH_SIZE ? FACT_BATCH_SIZE : remaining + 1;
            List<Attribution> batch = jdbc.query("""
                    WITH fact_batch AS (
                      SELECT id,user_id,article_id,target_author_id,event_type,occurred_at
                      FROM user_article_event
                      WHERE occurred_at >= ? AND occurred_at < ?
                        AND event_type IN ('VIEW','LIKE','COLLECT','COMMENT','FOLLOW_AUTHOR')
                        AND (occurred_at > ? OR (occurred_at = ? AND id > ?))
                      ORDER BY occurred_at ASC,id ASC
                      LIMIT ?
                    )
                    SELECT f.id,f.occurred_at,
                      CASE WHEN f.event_type='FOLLOW_AUTHOR' THEN (
                        SELECT e.id
                        FROM recommendation_exposure e
                        JOIN article a ON a.id=e.article_id
                        WHERE e.user_id=f.user_id AND a.author_id=f.target_author_id
                          AND e.exposed_at >= ? AND e.exposed_at <= f.occurred_at
                          AND f.occurred_at < TIMESTAMPADD(DAY, ?, e.exposed_at)
                        ORDER BY e.exposed_at DESC,e.id DESC LIMIT 1
                      ) ELSE (
                        SELECT e.id
                        FROM recommendation_exposure e
                        WHERE e.user_id=f.user_id AND e.article_id=f.article_id
                          AND e.exposed_at >= ? AND e.exposed_at <= f.occurred_at
                          AND f.occurred_at < TIMESTAMPADD(DAY, ?, e.exposed_at)
                        ORDER BY e.exposed_at DESC,e.id DESC LIMIT 1
                      ) END AS exposure_id
                    FROM fact_batch f
                    ORDER BY f.occurred_at ASC,f.id ASC
                    """, (rs, rowNumber) -> new Attribution(rs.getLong(1),
                    rs.getObject(2, LocalDateTime.class), (Long) rs.getObject(3)),
                    windowStart, now, cursorTime, cursorTime, cursorId, queryLimit,
                    windowStart, properties.getLabelWindowDays(),
                    windowStart, properties.getLabelWindowDays());
            if (batch.size() > remaining) {
                return new AttributionResult(Set.of(), false);
            }
            for (Attribution attribution : batch) {
                if (attribution.exposureId != null && cohortIds.contains(attribution.exposureId)) {
                    positiveIds.add(attribution.exposureId);
                }
            }
            scanned += batch.size();
            if (batch.size() < queryLimit) {
                return new AttributionResult(positiveIds, true);
            }
            Attribution last = batch.getLast();
            cursorTime = last.occurredAt;
            cursorId = last.factId;
        }
    }

    public enum Status { READY, NO_DATA, NO_REAL_BASELINE, FACT_SCAN_LIMIT_EXCEEDED }

    public record Dataset(Status status, List<TrainingExample> training, List<TrainingExample> validation) {
        public Dataset {
            training = List.copyOf(training);
            validation = List.copyOf(validation);
        }
        public boolean isEmpty() { return training.isEmpty() && validation.isEmpty(); }
        public boolean hasBothLabels() { return both(training) && both(validation); }
        public int sampleCount() { return training.size() + validation.size(); }
        public int positiveCount() {
            return (int) java.util.stream.Stream.concat(training.stream(), validation.stream())
                    .filter(row -> row.label() == 1).count();
        }
        public int negativeCount() { return sampleCount() - positiveCount(); }
        private static boolean both(List<TrainingExample> rows) {
            return rows.stream().anyMatch(row -> row.label() == 0) && rows.stream().anyMatch(row -> row.label() == 1);
        }
    }

    private record Row(long id, LocalDateTime exposedAt, double baseline, RecommendationFeatureVector features) {}
    private record Attribution(long factId, LocalDateTime occurredAt, Long exposureId) {}
    private record AttributionResult(Set<Long> positiveIds, boolean complete) {}
}
