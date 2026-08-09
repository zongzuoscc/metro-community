package cumt.zongzuo.community.recommendation;

import cumt.zongzuo.community.recommendation.entity.RecommendationEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationPolicyTest {

    @Test
    void eventTypesExposeTheApprovedInterestWeights() {
        assertThat(RecommendationEventType.VIEW.weight()).isEqualTo(1);
        assertThat(RecommendationEventType.LIKE.weight()).isEqualTo(4);
        assertThat(RecommendationEventType.COLLECT.weight()).isEqualTo(8);
        assertThat(RecommendationEventType.COMMENT.weight()).isEqualTo(6);
        assertThat(RecommendationEventType.FOLLOW_AUTHOR.weight()).isEqualTo(10);
    }
}
