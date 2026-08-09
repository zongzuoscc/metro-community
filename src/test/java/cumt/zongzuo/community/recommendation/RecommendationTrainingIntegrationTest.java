package cumt.zongzuo.community.recommendation;

import cumt.zongzuo.community.IntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import cumt.zongzuo.community.recommendation.service.RecommendationEligibilityService;
import cumt.zongzuo.community.recommendation.training.RecommendationFeatureVector;
import cumt.zongzuo.community.recommendation.training.RecommendationModel;
import cumt.zongzuo.community.recommendation.training.RecommendationTrainingDataset;
import cumt.zongzuo.community.recommendation.training.RecommendationModelStore;
import cumt.zongzuo.community.recommendation.training.RecommendationTrainingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import javax.sql.DataSource;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Import(RecommendationTrainingIntegrationTest.FixedClockConfiguration.class)
class RecommendationTrainingIntegrationTest extends IntegrationTestSupport {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    @Autowired RecommendationTrainingDataset dataset;
    @Autowired RecommendationEligibilityService eligibility;
    @Autowired ObjectMapper objectMapper;
    @Autowired DataSource dataSource;
    @Autowired RecommendationProperties properties;

    @BeforeEach
    void clean() {
        properties.setTrainingSampleLimit(50_000);
        properties.setTrainingMaxSamplesPerUser(500);
        properties.setTrainingFactScanLimit(200_000);
        jdbcTemplate.update("DELETE FROM user_article_event");
        jdbcTemplate.update("DELETE FROM recommendation_exposure");
        jdbcTemplate.update("DELETE FROM article");
        article(1, 11); article(2, 11);
    }

    @Test
    void cohortCapsEachUserBeforeApplyingTheGlobalSampleLimit() {
        properties.setTrainingSampleLimit(10);
        properties.setTrainingMaxSamplesPerUser(2);
        article(3, 12);
        article(4, 13);
        article(5, 14);
        LocalDateTime start = LocalDateTime.of(2026, 7, 20, 20, 0);
        exposureForUser(1, 1, start, 1D);
        exposureForUser(1, 2, start.plusDays(1), 2D);
        exposureForUser(1, 3, start.plusDays(2), 3D);
        exposureForUser(2, 4, start.plusDays(3), 4D);
        exposureForUser(2, 5, start.plusDays(4), 5D);

        RecommendationTrainingDataset.Dataset rows = dataset.load();

        assertThat(rows.sampleCount()).isEqualTo(4);
        assertThat(all(rows).map(row -> row.features().tagAffinity()))
                .containsExactlyInAnyOrder(2D, 3D, 4D, 5D);
    }

    @Test
    void factAttributionAcceptsTheExactScanCapButRejectsCapPlusOneAsIncomplete(@TempDir Path modelDirectory) {
        properties.setTrainingSampleLimit(1);
        properties.setTrainingFactScanLimit(2);
        LocalDateTime exposedAt = LocalDateTime.of(2026, 7, 25, 20, 0);
        exposure(1, exposedAt, 0.5D);
        fact(2001, exposedAt.plusMinutes(1), "bounded-fact-1");
        fact(2002, exposedAt.plusMinutes(1), "bounded-fact-2");

        assertThat(dataset.load().status()).isEqualTo(RecommendationTrainingDataset.Status.READY);

        fact(2003, exposedAt.plusMinutes(1), "bounded-fact-overflow");
        RecommendationTrainingDataset.Dataset overflow = dataset.load();
        RecommendationModelStore store = new RecommendationModelStore(modelDirectory, objectMapper, 7);
        RecommendationModel active = validModel("before-fact-overflow");
        assertThat(store.publish(active).published()).isTrue();
        RecommendationTrainingService training = new RecommendationTrainingService(
                dataset, store, Clock.fixed(NOW, SHANGHAI));

        RecommendationTrainingService.TrainingResult result = training.trainAndPublish();

        assertThat(overflow.status()).isEqualTo(
                RecommendationTrainingDataset.Status.FACT_SCAN_LIMIT_EXCEEDED);
        assertThat(overflow.isEmpty()).isTrue();
        assertThat(result.published()).isFalse();
        assertThat(result.reason()).isEqualTo("FACT_SCAN_LIMIT_EXCEEDED");
        assertThat(store.loadActive(NOW).model()).contains(active);
    }

    @Test
    void assignsAnInteractionOnlyToTheLatestExposureAndExcludesImmatureRows() {
        LocalDateTime old = LocalDateTime.of(2026, 8, 1, 20, 0);
        LocalDateTime latest = LocalDateTime.of(2026, 8, 2, 19, 0);
        exposure(1, old, 1D); exposure(1, latest, 1D);
        exposure(2, LocalDateTime.of(2026, 8, 5, 20, 0), 1D); // immature at NOW
        event(1, LocalDateTime.of(2026, 8, 3, 19, 0));

        RecommendationTrainingDataset.Dataset rows = dataset.load();

        assertThat(rows.training()).isNotEmpty();
        assertThat(all(rows)
                .filter(row -> row.label() == 1)).hasSize(1);
        assertThat(rows.training().size() + rows.validation().size()).isEqualTo(2);
    }

    @Test
    void keepsTheExactMaturityBoundaryOutOfTheCohort() {
        article(3, 12);
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 2, 20, 0);
        exposure(1, cutoff.minusSeconds(1), 0.1D);
        exposure(2, cutoff, 0.2D);
        exposure(3, LocalDateTime.of(2026, 8, 5, 20, 0), 0.3D);

        RecommendationTrainingDataset.Dataset rows = dataset.load();

        assertThat(all(rows)).hasSize(1);
        assertThat(all(rows).map(row -> row.features().tagAffinity())).containsExactly(1D);
    }

    @Test
    void newerImmatureOrMissingBaselineExposurePreventsAnOlderExposureFromBecomingPositive() {
        article(3, 12);
        article(4, 13);
        LocalDateTime old = LocalDateTime.of(2026, 7, 25, 20, 0);
        LocalDateTime newestMature = LocalDateTime.of(2026, 8, 1, 20, 0);
        exposure(1, old, 0.1D);
        exposure(1, LocalDateTime.of(2026, 8, 5, 20, 0), 0.2D);
        event(1, LocalDateTime.of(2026, 8, 6, 20, 0));
        exposure(3, old, 0.3D);
        exposure(3, newestMature, null);
        event(3, LocalDateTime.of(2026, 8, 2, 20, 0));
        exposure(4, old, 0.4D); // an unrelated retained row confirms negatives remain represented

        RecommendationTrainingDataset.Dataset rows = dataset.load();

        assertThat(all(rows)).hasSize(3);
        assertThat(all(rows).allMatch(row -> row.label() == 0)).isTrue();
    }

    @Test
    void attributesFollowAuthorToTheLatestExposureForThatAuthor() {
        article(3, 77);
        article(4, 77);
        LocalDateTime first = LocalDateTime.of(2026, 7, 26, 20, 0);
        LocalDateTime latest = LocalDateTime.of(2026, 7, 28, 20, 0);
        exposure(3, first, 0.2D);
        exposure(4, latest, 0.2D);
        follow(77, LocalDateTime.of(2026, 7, 29, 20, 0));

        RecommendationTrainingDataset.Dataset rows = dataset.load();

        assertThat(all(rows).filter(row -> row.label() == 1)).hasSize(1);
        assertThat(all(rows).filter(row -> row.label() == 1).map(row -> row.features().tagAffinity()))
                .containsExactly(4D);
    }

    @Test
    void usesOldestEightyPercentForTrainingAndNewestTwentyPercentForValidation() {
        for (long article = 3; article <= 7; article++) {
            this.article(article, 100 + article);
            exposure(article, LocalDateTime.of(2026, 7, (int) (20 + article), 20, 0), article / 10D);
        }

        RecommendationTrainingDataset.Dataset rows = dataset.load();

        assertThat(rows.training()).hasSize(4);
        assertThat(rows.validation()).hasSize(1);
        assertThat(rows.training().getLast().features().tagAffinity())
                .isLessThan(rows.validation().getFirst().features().tagAffinity());
    }

    @Test
    void boundsTheCohortBeforeAttributionAndKeepsCapExcludedWinnersOutOfSelectedLabels() {
        properties.setTrainingSampleLimit(2);
        article(3, 12);
        exposure(1, LocalDateTime.of(2026, 7, 25, 20, 0), 0.1D);
        exposure(1, LocalDateTime.of(2026, 7, 26, 20, 0), 0.2D, 2D);
        exposure(3, LocalDateTime.of(2026, 7, 27, 20, 0), 0.3D);
        event(1, LocalDateTime.of(2026, 7, 25, 21, 0));

        RecommendationTrainingDataset.Dataset rows = dataset.load();

        assertThat(all(rows)).hasSize(2);
        assertThat(all(rows).map(row -> row.features().tagAffinity())).containsExactly(2D, 3D);
        assertThat(rows.positiveCount()).isZero();
    }

    @Test
    void sameTimestampAttributionUsesTheLargestExposureIdAsTheLatestPrior() {
        LocalDateTime exposedAt = LocalDateTime.of(2026, 7, 25, 20, 0);
        exposure(1, exposedAt, 0.1D, 1D);
        exposure(1, exposedAt, 0.2D, 2D);
        event(1, exposedAt.plusMinutes(1));

        RecommendationTrainingDataset.Dataset rows = dataset.load();

        assertThat(all(rows).filter(row -> row.label() == 1).map(row -> row.features().tagAffinity()))
                .containsExactly(2D);
    }

    @Test
    void continuesFactAttributionAfterOneThousandFactsUsingOccurredAtAndId() {
        LocalDateTime exposedAt = LocalDateTime.of(2026, 7, 25, 20, 0);
        exposure(1, exposedAt, 0.1D);
        for (int index = 0; index < 1_000; index++) {
            fact(2000 + index, exposedAt.plusMinutes(1), "batch-one-" + index);
        }
        event(1, exposedAt.plusMinutes(1));

        RecommendationTrainingDataset.Dataset rows = dataset.load();

        assertThat(rows.sampleCount()).isOne();
        assertThat(rows.positiveCount()).isOne();
    }

    @Test
    void eligibilityRequiresBothInclusiveTwentyAndFiveHundredFactCutoffs() {
        LocalDateTime userCutoff = LocalDateTime.of(2026, 7, 10, 20, 0);
        LocalDateTime globalCutoff = LocalDateTime.of(2026, 5, 11, 20, 0);
        for (int i = 0; i < 19; i++) fact(1001, userCutoff, "user-" + i);
        for (int i = 0; i < 481; i++) fact(2000, globalCutoff, "global-a-" + i);

        assertThat(eligibility.isEligible(1001L)).isFalse();

        jdbcTemplate.update("DELETE FROM user_article_event");
        for (int i = 0; i < 20; i++) fact(1001, userCutoff, "user-b-" + i);
        for (int i = 0; i < 479; i++) fact(2000, globalCutoff, "global-b-" + i);

        assertThat(eligibility.isEligible(1001L)).isFalse();

        fact(2000, globalCutoff, "global-five-hundredth-at-cutoff");

        assertThat(eligibility.isEligible(1001L)).isTrue();
    }

    @Test
    void publishesOnlyWhenTheRecordedBaselineIsBeatenAndRetainsThePriorActiveModel(@TempDir Path modelDirectory) {
        RecommendationModelStore store = new RecommendationModelStore(modelDirectory, objectMapper, 7);
        RecommendationTrainingService training = new RecommendationTrainingService(dataset, store, Clock.fixed(NOW, SHANGHAI));
        seedSeparableTrainingRows(false);

        RecommendationTrainingService.TrainingResult first = training.trainAndPublish();

        assertThat(first.published()).isTrue();
        String activeVersion = store.loadActive(NOW).model().orElseThrow().version();
        clean();
        seedSeparableTrainingRows(true);

        RecommendationTrainingService.TrainingResult second = training.trainAndPublish();

        assertThat(second).extracting(RecommendationTrainingService.TrainingResult::published,
                        RecommendationTrainingService.TrainingResult::reason)
                .containsExactly(false, "BASELINE_NOT_BEATEN");
        assertThat(store.loadActive(NOW).model().orElseThrow().version()).isEqualTo(activeVersion);
    }

    @Test
    void reportsNoDataAndOneClassSplitWithoutPublishing(@TempDir Path modelDirectory) {
        RecommendationTrainingService training = new RecommendationTrainingService(dataset,
                new RecommendationModelStore(modelDirectory, objectMapper, 7), Clock.fixed(NOW, SHANGHAI));

        assertThat(training.trainAndPublish().reason()).isEqualTo("NO_DATA");
        exposure(1, LocalDateTime.of(2026, 7, 25, 20, 0), 0.5D);
        exposure(2, LocalDateTime.of(2026, 7, 26, 20, 0), 0.5D);

        assertThat(training.trainAndPublish().reason()).isEqualTo("SPLIT_MISSING_LABEL");
    }

    @Test
    void distinguishesNoMatureExposureFromNoRealBaselineAndRetainsActiveModel(@TempDir Path modelDirectory) {
        RecommendationModelStore store = new RecommendationModelStore(modelDirectory, objectMapper, 7);
        RecommendationModel active = validModel("active-before-empty-loads");
        assertThat(store.publish(active).published()).isTrue();
        RecommendationTrainingService training = new RecommendationTrainingService(dataset, store, Clock.fixed(NOW, SHANGHAI));

        assertThat(training.trainAndPublish().reason()).isEqualTo("NO_DATA");
        assertThat(store.loadActive(NOW).model()).contains(active);

        exposure(1, LocalDateTime.of(2026, 7, 25, 20, 0), null);

        assertThat(training.trainAndPublish().reason()).isEqualTo("NO_REAL_BASELINE");
        assertThat(store.loadActive(NOW).model()).contains(active);
    }

    @Test
    void numericDatasetFailureDoesNotReplaceTheActiveModel(@TempDir Path modelDirectory) {
        RecommendationModelStore store = new RecommendationModelStore(modelDirectory, objectMapper, 7);
        RecommendationModel active = validModel("active-before-numeric-failure");
        assertThat(store.publish(active).published()).isTrue();
        RecommendationTrainingDataset failingDataset = mock(RecommendationTrainingDataset.class);
        when(failingDataset.load()).thenThrow(new IllegalArgumentException("non-finite training input"));
        RecommendationTrainingService training = new RecommendationTrainingService(
                failingDataset, store, Clock.fixed(NOW, SHANGHAI));

        assertThat(training.trainAndPublish().reason()).isEqualTo("NUMERIC_FAILURE");
        assertThat(store.loadActive(NOW).model()).contains(active);
    }

    @Test
    void forwardMigrationCreatesTheRecommendationTablesAndIsIdempotentOnMySql8() throws Exception {
        String schema = "task6_migration_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl;
        try (Connection applicationConnection = dataSource.getConnection()) {
            jdbcUrl = applicationConnection.getMetaData().getURL();
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", "test");
             Statement statement = connection.createStatement()) {
            String originalSchema = connection.getCatalog();
            statement.execute("CREATE DATABASE " + schema);
            statement.execute("USE " + schema);
            statement.execute("CREATE TABLE article (id BIGINT PRIMARY KEY, status INT, is_deleted INT, create_time DATETIME)");
            FileSystemResource migration = new FileSystemResource("docs/database/migrations/2026-08-09-recommendation-training.sql");

            ScriptUtils.executeSqlScript(connection, migration);
            statement.execute("INSERT INTO user_article_event "
                    + "(user_id,article_id,event_type,occurred_at,dedupe_key,source) "
                    + "VALUES (99,1,'VIEW',NOW(),'migration-profile-backfill','test')");
            ScriptUtils.executeSqlScript(connection, migration);

            try (var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"
                    + schema + "' AND table_name IN ('user_article_event','recommendation_event_outbox','recommendation_exposure',"
                    + "'recommendation_profile_checkpoint')")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(4);
            }
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='"
                    + schema + "' AND ((table_name='recommendation_exposure' AND column_name IN "
                    + "('source_follow','source_tag','source_similar','source_explore','baseline_score')))")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(5);
            }
            try (var result = statement.executeQuery("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='"
                    + schema + "' AND index_name IN ('idx_exposure_training','idx_user_article_event_at','idx_user_author_event_at',"
                    + "'idx_event_occurred_at','idx_exposure_user_article_at','idx_article_recommendation_feed',"
                    + "'idx_profile_checkpoint_repair')")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(7);
            }
            try (var result = statement.executeQuery("SELECT requested_event_id,rebuilt_event_id "
                    + "FROM recommendation_profile_checkpoint WHERE user_id=99")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isPositive();
                assertThat(result.getLong(2)).isZero();
            }
            statement.execute("USE " + originalSchema);
        } finally {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", "test");
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + schema);
            }
        }
    }

    private java.util.stream.Stream<cumt.zongzuo.community.recommendation.training.TrainingExample> all(RecommendationTrainingDataset.Dataset rows) {
        return java.util.stream.Stream.concat(rows.training().stream(), rows.validation().stream());
    }

    private void article(long id, long author) {
        jdbcTemplate.update("INSERT INTO article (id,title,summary,content,author_id,status,is_deleted,create_time,update_time) VALUES (?,?,?,?,?,1,0,?,?)",
                id, "t", "s", "c", author, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 0));
    }
    private void exposure(long article, LocalDateTime time, Double baseline) {
        jdbcTemplate.update("INSERT INTO recommendation_exposure (user_id,article_id,session_id,source,tag_affinity,author_affinity,similar_score,heat_score,freshness_score,source_follow,source_tag,source_similar,source_explore,baseline_score,exposed_at,create_time) VALUES (1,?,UUID(),'TAG',?,0,0,0,0,0,1,0,0,?,?,?)", article, article, baseline, time, time);
    }
    private void event(long article, LocalDateTime time) {
        jdbcTemplate.update("INSERT INTO user_article_event (user_id,article_id,event_type,occurred_at,dedupe_key,source,create_time) VALUES (1,?,'VIEW',?,UUID(),'recommendation',?)", article, time, time);
    }
    private void follow(long author, LocalDateTime time) {
        jdbcTemplate.update("INSERT INTO user_article_event (user_id,target_author_id,event_type,occurred_at,dedupe_key,source,create_time) VALUES (1,?,'FOLLOW_AUTHOR',?,UUID(),'recommendation',?)", author, time, time);
    }
    private void fact(long user, LocalDateTime time, String dedupe) {
        jdbcTemplate.update("INSERT INTO user_article_event (user_id,article_id,event_type,occurred_at,dedupe_key,source,create_time) VALUES (?,1,'VIEW',?,?, 'recommendation',?)", user, time, dedupe, time);
    }
    private void seedSeparableTrainingRows(boolean perfectBaseline) {
        for (long article = 3; article <= 12; article++) {
            this.article(article, 100 + article);
            boolean positive = article % 2 == 1;
            double tag = positive ? 1D : 0D;
            double baseline = perfectBaseline ? tag : 1D - tag;
            LocalDateTime exposedAt = LocalDateTime.of(2026, 7, 15 + (int) (article - 3), 20, 0);
            exposure(article, exposedAt, baseline, tag);
            if (positive) event(article, exposedAt.plusHours(1));
        }
    }
    private RecommendationModel validModel(String version) {
        return new RecommendationModel(version, NOW.minusSeconds(60), RecommendationFeatureVector.FEATURE_NAMES,
                List.of(0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D),
                List.of(1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D),
                List.of(1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D), 0D, .75D, .6D);
    }
    private void exposure(long article, LocalDateTime time, Double baseline, double tagAffinity) {
        jdbcTemplate.update("INSERT INTO recommendation_exposure (user_id,article_id,session_id,source,tag_affinity,author_affinity,similar_score,heat_score,freshness_score,source_follow,source_tag,source_similar,source_explore,baseline_score,exposed_at,create_time) VALUES (1,?,UUID(),'TAG',?,0,0,0,0,0,1,0,0,?,?,?)", article, tagAffinity, baseline, time, time);
    }
    private void exposureForUser(long user, long article, LocalDateTime time, double tagAffinity) {
        jdbcTemplate.update("INSERT INTO recommendation_exposure (user_id,article_id,session_id,source,tag_affinity,author_affinity,similar_score,heat_score,freshness_score,source_follow,source_tag,source_similar,source_explore,baseline_score,exposed_at,create_time) VALUES (?,?,UUID(),'TAG',?,0,0,0,0,0,1,0,0,1,?,?)",
                user, article, tagAffinity, time, time);
    }
    @TestConfiguration static class FixedClockConfiguration { @Bean @Primary Clock clock() { return Clock.fixed(NOW, SHANGHAI); } }
}
