package cumt.zongzuo.community.ai.moderation.revision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ModerationAggregate(ModerationDecision decision,
                                  Set<ModerationCategory> categories,
                                  int severity,
                                  BigDecimal confidence,
                                  BigDecimal riskScore,
                                  boolean uncertain,
                                  boolean requiresHumanReview) {

    public static ModerationAggregate from(List<ModerationModelOutput> outputs,
                                           BigDecimal minimumConfidence) {
        Objects.requireNonNull(outputs, "outputs");
        Objects.requireNonNull(minimumConfidence, "minimumConfidence");
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("moderation outputs must not be empty");
        }
        ModerationDecision highest = ModerationDecision.PASS;
        int severity = 0;
        BigDecimal confidence = BigDecimal.ONE;
        Set<ModerationDecision> decisions = new LinkedHashSet<>();
        Set<ModerationCategory> categories = new LinkedHashSet<>();
        for (ModerationModelOutput output : outputs) {
            Objects.requireNonNull(output, "moderation output");
            decisions.add(output.decision());
            categories.addAll(output.categories());
            if (output.decision().riskRank() > highest.riskRank()) {
                highest = output.decision();
            }
            severity = Math.max(severity, output.severity());
            confidence = confidence.min(output.confidence());
        }
        boolean uncertain = decisions.size() > 1 || confidence.compareTo(minimumConfidence) < 0;
        BigDecimal risk = BigDecimal.valueOf(severity)
                .divide(BigDecimal.valueOf(4), 5, RoundingMode.HALF_UP)
                .max(BigDecimal.valueOf(highest.riskRank()).divide(BigDecimal.valueOf(2), 5,
                        RoundingMode.HALF_UP));
        return new ModerationAggregate(highest, Set.copyOf(categories), severity, confidence,
                risk, uncertain, true);
    }
}
