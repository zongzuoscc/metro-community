package cumt.zongzuo.community.recommendation.training;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class LogisticRegressionTrainer {
    private static final double LEARNING_RATE = .05D;
    private static final double L2 = .01D;
    private static final int ITERATIONS = 300;

    public RecommendationModel train(List<TrainingExample> examples, Instant trainedAt) {
        if (examples == null || examples.isEmpty() || trainedAt == null || !hasBothLabels(examples)) {
            throw new IllegalArgumentException("Training requires both labels");
        }
        int columns = RecommendationFeatureVector.FEATURE_NAMES.size();
        double[] means = new double[columns];
        for (TrainingExample example : examples) {
            double[] values = example.features().values();
            for (int i = 0; i < columns; i++) means[i] += values[i];
        }
        for (int i = 0; i < columns; i++) means[i] /= examples.size();
        double[] deviations = new double[columns];
        for (TrainingExample example : examples) {
            double[] values = example.features().values();
            for (int i = 0; i < columns; i++) deviations[i] += Math.pow(values[i] - means[i], 2D);
        }
        for (int i = 0; i < columns; i++) {
            deviations[i] = Math.sqrt(deviations[i] / examples.size());
            if (deviations[i] == 0D) deviations[i] = 1D;
        }
        double[] weights = new double[columns];
        double bias = 0D;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            double[] gradients = new double[columns];
            double biasGradient = 0D;
            for (TrainingExample example : examples) {
                double[] normalized = normalize(example.features().values(), means, deviations);
                double probability = probability(normalized, weights, bias);
                double error = probability - example.label();
                biasGradient += error;
                for (int i = 0; i < columns; i++) gradients[i] += error * normalized[i];
            }
            for (int i = 0; i < columns; i++) {
                weights[i] -= LEARNING_RATE * ((gradients[i] / examples.size()) + L2 * weights[i]);
                requireFinite(weights[i]);
            }
            bias -= LEARNING_RATE * biasGradient / examples.size();
            requireFinite(bias);
        }
        return new RecommendationModel("model-" + trainedAt.toEpochMilli(), trainedAt,
                RecommendationFeatureVector.FEATURE_NAMES, doubles(means), doubles(deviations), doubles(weights),
                bias, .5D, .5D);
    }

    public static double auc(List<Double> scores, List<Integer> labels) {
        if (scores == null || labels == null || scores.size() != labels.size() || scores.isEmpty()) {
            throw new IllegalArgumentException("Scores and labels must have equal non-empty size");
        }
        List<ScoredLabel> pairs = new ArrayList<>(scores.size());
        for (int i = 0; i < scores.size(); i++) {
            Double score = scores.get(i);
            Integer label = labels.get(i);
            if (score == null || !Double.isFinite(score) || label == null || (label != 0 && label != 1)) {
                throw new IllegalArgumentException("Scores must be finite and labels binary");
            }
            pairs.add(new ScoredLabel(score, label));
        }
        long positive = pairs.stream().filter(pair -> pair.label == 1).count();
        long negative = pairs.size() - positive;
        if (positive == 0 || negative == 0) throw new IllegalArgumentException("AUC requires both labels");
        pairs.sort(Comparator.comparingDouble(ScoredLabel::score));
        long negativesBelow = 0L;
        double wins = 0D;
        for (int start = 0; start < pairs.size();) {
            int end = start + 1;
            while (end < pairs.size() && Double.compare(pairs.get(start).score, pairs.get(end).score) == 0) end++;
            long groupPositive = pairs.subList(start, end).stream().filter(pair -> pair.label == 1).count();
            long groupNegative = end - start - groupPositive;
            wins += groupPositive * negativesBelow + groupPositive * groupNegative * .5D;
            negativesBelow += groupNegative;
            start = end;
        }
        return wins / (positive * negative);
    }

    private static boolean hasBothLabels(List<TrainingExample> examples) {
        return examples.stream().anyMatch(example -> example.label() == 0)
                && examples.stream().anyMatch(example -> example.label() == 1);
    }

    private static double probability(double[] values, double[] weights, double bias) {
        double z = bias;
        for (int i = 0; i < values.length; i++) z += values[i] * weights[i];
        requireFinite(z);
        return 1D / (1D + Math.exp(-Math.max(-35D, Math.min(35D, z))));
    }

    private static double[] normalize(double[] values, double[] means, double[] deviations) {
        double[] normalized = new double[values.length];
        for (int i = 0; i < values.length; i++) normalized[i] = (values[i] - means[i]) / deviations[i];
        return normalized;
    }

    private static List<Double> doubles(double[] values) {
        List<Double> result = new ArrayList<>(values.length);
        for (double value : values) result.add(value);
        return result;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) throw new IllegalStateException("Numeric training failure");
    }

    private record ScoredLabel(double score, int label) {}
}
