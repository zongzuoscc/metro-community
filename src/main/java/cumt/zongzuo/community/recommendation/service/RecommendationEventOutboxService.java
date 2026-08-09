package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.dto.RecommendationEventCommand;
import cumt.zongzuo.community.recommendation.entity.RecommendationEventOutbox;
import cumt.zongzuo.community.recommendation.mapper.RecommendationEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendationEventOutboxService {

    private final RecommendationEventOutboxMapper mapper;

    public void enqueue(RecommendationEventCommand command) {
        mapper.insert(RecommendationEventOutbox.pending(command));
    }
}
