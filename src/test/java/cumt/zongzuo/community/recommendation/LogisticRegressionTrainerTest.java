package cumt.zongzuo.community.recommendation;

import cumt.zongzuo.community.recommendation.training.LogisticRegressionTrainer;
import cumt.zongzuo.community.recommendation.training.RecommendationFeatureVector;
import cumt.zongzuo.community.recommendation.training.RecommendationModel;
import cumt.zongzuo.community.recommendation.training.TrainingExample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogisticRegressionTrainerTest {

    @Test
    void learnsHigherProbabilityForSeparablePositiveFeatures() {
        LogisticRegressionTrainer trainer = new LogisticRegressionTrainer();
        RecommendationModel model = trainer.train(List.of(
                example(1, .9, .8, .8, .7, .8),
                example(1, .8, .9, .7, .8, .7),
                example(0, 0, .1, 0, .2, .1),
                example(0, .1, 0, .1, .1, 0)), Instant.parse("2026-08-09T12:00:00Z"));

        assertThat(model.score(vector(.9, .8, .8, .7, .8)))
                .isGreaterThan(model.score(vector(0, .1, 0, .2, .1)));
    }

    @Test
    void aucAwardsHalfCreditForTies() {
        assertThat(LogisticRegressionTrainer.auc(List.of(.5, .5), List.of(1, 0))).isEqualTo(.5);
    }

    @Test
    void aucRejectsNonFiniteScoresAndIllegalLabels() {
        assertThatThrownBy(() -> LogisticRegressionTrainer.auc(List.of(Double.NaN, .5), List.of(1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogisticRegressionTrainer.auc(List.of(.2, .5), List.of(2, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aucIsIndependentOfDatabaseRowOrder() {
        double ordered = LogisticRegressionTrainer.auc(List.of(.1, .9, .2, .8), List.of(0, 1, 0, 1));
        double shuffled = LogisticRegressionTrainer.auc(List.of(.8, .2, .9, .1), List.of(1, 0, 1, 0));

        assertThat(shuffled).isEqualTo(ordered);
    }

    @Test
    void rejectsNonFiniteFeatureAtConstructionTime() {
        assertThatThrownBy(() -> vector(Double.NaN, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesOneForZeroStandardDeviationAndKeepsFiniteExtremeProbabilities() {
        LogisticRegressionTrainer trainer = new LogisticRegressionTrainer();
        RecommendationModel trained = trainer.train(List.of(
                example(1, 1, 0, 0, 0, 0), example(0, 0, 0, 0, 0, 0)), Instant.parse("2026-08-09T12:00:00Z"));
        RecommendationModel extreme = new RecommendationModel("finite-extreme", Instant.parse("2026-08-09T12:00:00Z"),
                RecommendationFeatureVector.FEATURE_NAMES,
                List.of(0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D),
                List.of(1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D),
                List.of(Double.MAX_VALUE, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D), 0D, .75D, .5D);

        assertThat(trained.standardDeviations().subList(1, 9)).containsOnly(1D);
        assertThat(extreme.score(vector(1, 0, 0, 0, 0))).isFinite().isBetween(0D, 1D);
    }

    @Test
    void rejectsSingleLabelTrainingAndValidationExtremesDoNotChangeTrainingMeans() {
        LogisticRegressionTrainer trainer = new LogisticRegressionTrainer();
        assertThatThrownBy(() -> trainer.train(List.of(example(1, 0, 0, 0, 0, 0), example(1, 1, 0, 0, 0, 0)),
                Instant.parse("2026-08-09T12:00:00Z"))).isInstanceOf(IllegalArgumentException.class);

        RecommendationModel trained = trainer.train(List.of(
                example(0, 0, 0, 0, 0, 0), example(1, 1, 0, 0, 0, 0)), Instant.parse("2026-08-09T12:00:00Z"));
        trained.score(vector(1_000_000D, 0, 0, 0, 0));

        assertThat(trained.means().getFirst()).isEqualTo(.5D);
    }

    private static TrainingExample example(int label, double tag, double author, double similar,
                                            double heat, double freshness) {
        return new TrainingExample(vector(tag, author, similar, heat, freshness), label, heat + freshness);
    }

    private static RecommendationFeatureVector vector(double tag, double author, double similar,
                                                       double heat, double freshness) {
        return new RecommendationFeatureVector(tag, author, similar, heat, freshness,
                0, 0, similar > 0 ? 1 : 0, 1);
    }
}
