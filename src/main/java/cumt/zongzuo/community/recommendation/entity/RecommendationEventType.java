package cumt.zongzuo.community.recommendation.entity;

public enum RecommendationEventType {
    VIEW(1), LIKE(4), COLLECT(8), COMMENT(6), FOLLOW_AUTHOR(10);

    private final int weight;

    RecommendationEventType(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
