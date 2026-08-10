package cumt.zongzuo.community.event.outbox;

import lombok.Data;

import java.util.UUID;

@Data
public class ConsumerInboxRetentionKey {
    private String consumerName;
    private UUID eventId;
}
