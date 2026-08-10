package cumt.zongzuo.community.ai.moderation.revision;

public enum ModerationDecision {
    PASS(0),
    REVIEW(1),
    REJECT(2);

    private final int riskRank;

    ModerationDecision(int riskRank) {
        this.riskRank = riskRank;
    }

    int riskRank() {
        return riskRank;
    }
}
