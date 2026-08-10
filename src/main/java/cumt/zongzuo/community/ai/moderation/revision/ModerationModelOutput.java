package cumt.zongzuo.community.ai.moderation.revision;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record ModerationModelOutput(
        ModerationDecision decision,
        Set<ModerationCategory> categories,
        int severity,
        BigDecimal confidence,
        List<ModerationEvidence> evidenceOffsets,
        String reason,
        String model,
        String promptVersion) {
}
