package cumt.zongzuo.community.event.projection;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProjectionWatermark {
    private String consumerName;
    private String aggregateType;
    private Long aggregateId;
    private Long lastAppliedVersion;
    private Long lifecycleEpoch;
    private Boolean tombstone;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime updatedAt;
}
