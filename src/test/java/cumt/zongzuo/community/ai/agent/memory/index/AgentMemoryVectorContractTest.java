package cumt.zongzuo.community.ai.agent.memory.index;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThatCode;

class AgentMemoryVectorContractTest {
    @Test
    void exposesDeadlineBoundPersistentSynchronizationAndRecall() {
        assertThatCode(() -> {
            Class<?> service = Class.forName("cumt.zongzuo.community.ai.agent.memory.index.AgentMemoryVectorService");
            service.getMethod("synchronize", long.class, Instant.class);
            service.getMethod("recall", long.class, String.class, int.class, Instant.class);
        }).doesNotThrowAnyException();
    }
}
