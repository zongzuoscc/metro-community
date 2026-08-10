package cumt.zongzuo.community.article.rollout;

import java.util.Optional;

public interface StageBRolloutCheckpointReader {

    Optional<StageBRolloutCheckpoint> find();

    StageBRolloutCheckpoint require();

    StageBRolloutCheckpoint requireForUpdate();
}
