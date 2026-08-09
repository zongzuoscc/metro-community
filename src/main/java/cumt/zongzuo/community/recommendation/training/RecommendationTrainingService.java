package cumt.zongzuo.community.recommendation.training;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class RecommendationTrainingService {
    private final RecommendationTrainingDataset dataset;
    private final LogisticRegressionTrainer trainer;
    private final RecommendationModelStore store;
    private final Clock clock;
    private volatile ValidationMetrics last = new ValidationMetrics(Double.NaN, Double.NaN);

    @Autowired
    public RecommendationTrainingService(RecommendationTrainingDataset dataset, RecommendationModelStore store,
                                         ObjectProvider<Clock> clocks) {
        this(dataset, store, clocks.getIfAvailable(Clock::systemDefaultZone));
    }

    public RecommendationTrainingService(RecommendationTrainingDataset dataset, RecommendationModelStore store, Clock clock) {
        this.dataset = dataset;
        this.store = store;
        this.trainer = new LogisticRegressionTrainer();
        this.clock = clock;
    }
    public TrainingResult trainAndPublish() {
        RecommendationTrainingDataset.Dataset rows;
        try {
            rows = dataset.load();
        } catch (IllegalArgumentException numericFailure) {
            return TrainingResult.notPublished("NUMERIC_FAILURE", 0, 0, 0);
        }
        int samples = rows.sampleCount();
        int positives = rows.positiveCount();
        int negatives = rows.negativeCount();
        if (rows.status() == RecommendationTrainingDataset.Status.NO_REAL_BASELINE) {
            return TrainingResult.notPublished("NO_REAL_BASELINE", samples, positives, negatives);
        }
        if (rows.status() == RecommendationTrainingDataset.Status.NO_DATA) {
            return TrainingResult.notPublished("NO_DATA", samples, positives, negatives);
        }
        if (rows.isEmpty()) return TrainingResult.notPublished("NO_DATA", samples, positives, negatives);
        if (!rows.hasBothLabels()) return TrainingResult.notPublished("SPLIT_MISSING_LABEL", samples, positives, negatives);
        try {
            RecommendationModel raw = trainer.train(rows.training(), clock.instant());
            List<Double> probabilities = rows.validation().stream().map(row -> raw.score(row.features())).toList();
            List<Double> baseline = rows.validation().stream().map(TrainingExample::baselineScore).toList();
            List<Integer> labels = rows.validation().stream().map(TrainingExample::label).toList();
            double modelAuc = LogisticRegressionTrainer.auc(probabilities, labels);
            double baselineAuc = LogisticRegressionTrainer.auc(baseline, labels);
            last = new ValidationMetrics(modelAuc, baselineAuc);
            if (!(modelAuc > baselineAuc + 1e-6D)) return TrainingResult.notPublished("BASELINE_NOT_BEATEN", samples, positives, negatives);
            RecommendationModel model = new RecommendationModel(raw.version(), raw.trainedAt(), raw.featureNames(),
                    raw.means(), raw.standardDeviations(), raw.weights(), raw.bias(), modelAuc, baselineAuc);
            RecommendationModelStore.ModelPublicationResult published = store.publish(model);
            return published.published() ? TrainingResult.published(model.version(), samples, positives, negatives)
                    : TrainingResult.notPublished(published.reason(), samples, positives, negatives);
        } catch (IllegalArgumentException | IllegalStateException numericFailure) {
            return TrainingResult.notPublished("NUMERIC_FAILURE", samples, positives, negatives);
        }
    }
    public ValidationMetrics lastValidationMetrics() { return last; }
    public record TrainingResult(boolean published, String reason, int samples, int positives, int negatives) {
        static TrainingResult published(String version, int samples, int positives, int negatives) {
            return new TrainingResult(true, version, samples, positives, negatives);
        }
        static TrainingResult notPublished(String reason, int samples, int positives, int negatives) {
            return new TrainingResult(false, reason, samples, positives, negatives);
        }
    }
    public record ValidationMetrics(double modelAuc, double baselineAuc) {}
}
