package cumt.zongzuo.community.recommendation.training;

import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RecommendationTrainingDataset {
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
        List<Row> universe = jdbc.query("""
                SELECT e.id,e.user_id,e.article_id,a.author_id,e.exposed_at,e.baseline_score,
                  e.tag_affinity,e.author_affinity,e.similar_score,e.heat_score,e.freshness_score,
                  e.source_follow,e.source_tag,e.source_similar,e.source_explore
                FROM recommendation_exposure e JOIN article a ON a.id=e.article_id
                WHERE e.exposed_at >= ? AND e.exposed_at < ? ORDER BY e.exposed_at ASC,e.id ASC
                """, (rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getObject(5, LocalDateTime.class), (Double) rs.getObject(6),
                new RecommendationFeatureVector(rs.getDouble(7), rs.getDouble(8), rs.getDouble(9),
                        rs.getDouble(10), rs.getDouble(11), rs.getDouble(12), rs.getDouble(13),
                        rs.getDouble(14), rs.getDouble(15))), windowStart, now);
        List<Fact> facts = jdbc.query("""
                SELECT user_id,article_id,target_author_id,event_type,occurred_at FROM user_article_event
                WHERE occurred_at >= ? AND occurred_at < ? AND event_type IN ('VIEW','LIKE','COLLECT','COMMENT','FOLLOW_AUTHOR')
                ORDER BY occurred_at ASC,id ASC
                """, (rs, n) -> new Fact(rs.getLong(1), (Long) rs.getObject(2), (Long) rs.getObject(3),
                rs.getString(4), rs.getObject(5, LocalDateTime.class)), windowStart, now);
        Map<ExposureKey, List<Row>> articleExposures = new HashMap<>();
        Map<ExposureKey, List<Row>> authorExposures = new HashMap<>();
        for (Row row : universe) {
            articleExposures.computeIfAbsent(new ExposureKey(row.userId, row.articleId), ignored -> new ArrayList<>()).add(row);
            authorExposures.computeIfAbsent(new ExposureKey(row.userId, row.authorId), ignored -> new ArrayList<>()).add(row);
        }
        Set<Long> positiveIds = new HashSet<>();
        for (Fact fact : facts) {
            List<Row> candidates = matchingExposures(fact, articleExposures, authorExposures);
            latestEligibleExposure(candidates, fact.occurredAt).ifPresent(row -> positiveIds.add(row.id));
        }
        List<Row> selected = universe.stream().filter(row -> row.baseline != null && row.exposedAt.isBefore(matureBefore))
                .sorted(Comparator.comparing(Row::exposedAt).thenComparing(Row::id).reversed())
                .limit(properties.getTrainingSampleLimit()).sorted(Comparator.comparing(Row::exposedAt).thenComparing(Row::id)).toList();
        List<TrainingExample> all = selected.stream().map(row -> new TrainingExample(row.features,
                positiveIds.contains(row.id) ? 1 : 0, row.baseline)).toList();
        int split = (int) Math.floor(all.size() * .8D);
        return new Dataset(all.subList(0, split), all.subList(split, all.size()));
    }

    private List<Row> matchingExposures(Fact fact, Map<ExposureKey, List<Row>> articleExposures,
                                        Map<ExposureKey, List<Row>> authorExposures) {
        if ("FOLLOW_AUTHOR".equals(fact.eventType)) {
            return fact.targetAuthorId == null ? List.of()
                    : authorExposures.getOrDefault(new ExposureKey(fact.userId, fact.targetAuthorId), List.of());
        }
        return fact.articleId == null ? List.of()
                : articleExposures.getOrDefault(new ExposureKey(fact.userId, fact.articleId), List.of());
    }

    private java.util.Optional<Row> latestEligibleExposure(List<Row> rows, LocalDateTime occurredAt) {
        int low = 0;
        int high = rows.size() - 1;
        int latest = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (rows.get(mid).exposedAt.compareTo(occurredAt) <= 0) {
                latest = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (latest < 0) {
            return java.util.Optional.empty();
        }
        Row row = rows.get(latest);
        return occurredAt.isBefore(row.exposedAt.plusDays(properties.getLabelWindowDays()))
                ? java.util.Optional.of(row) : java.util.Optional.empty();
    }

    public record Dataset(List<TrainingExample> training, List<TrainingExample> validation) {
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
    private record Row(long id,long userId,long articleId,long authorId,LocalDateTime exposedAt,Double baseline,RecommendationFeatureVector features) {}
    private record Fact(long userId,Long articleId,Long targetAuthorId,String eventType,LocalDateTime occurredAt) {}
    private record ExposureKey(long userId, long targetId) {}
}
