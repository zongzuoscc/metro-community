package cumt.zongzuo.community.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import cumt.zongzuo.community.ai.agent.memory.AgentMemoryView;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentWorkingContextTest {
    @Test
    void committedMemoryRemainsVisibleWhenTheRetrievalEvidenceLimitIsFull() {
        var working = new AgentWorkingContext(null, List.of(), List.of());
        working.addRetrievedMemories(java.util.stream.LongStream.rangeClosed(1, 48)
                .mapToObj(id -> memory(id, "retrieved-" + id)).toList());
        working.committed(99, memory(99, "newly-committed-fact"));
        assertThat(working.decisionMessages().toString()).contains("newly-committed-fact");
        assertThat(working.evidence.memories()).extracting(AgentMemoryView::id).contains(99L);
        assertThat(working.evidence.memories()).hasSizeLessThanOrEqualTo(48);
    }

    @Test
    void unrelatedRetrievalDoesNotRestoreAFilteredCommittedMemory() {
        var working = new AgentWorkingContext(null, List.of(), List.of());
        var committed = memory(99, "filtered-committed-fact");
        working.committed(99, committed);
        working.filter(List.of("M99:V1"));
        working.addRetrievedMemories(List.of(memory(1, "unrelated-result")));
        assertThat(working.decisionMessages().toString()).doesNotContain("filtered-committed-fact");
        working.addRetrievedMemories(List.of(committed));
        assertThat(working.decisionMessages().toString()).contains("filtered-committed-fact");
    }

    private static AgentMemoryView memory(long id, String content) {
        return new AgentMemoryView(id, "PREFERENCE", content, 1, "ACTIVE", null, "CONVERSATION");
    }
}
