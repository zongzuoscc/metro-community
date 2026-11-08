package cumt.zongzuo.community.ai.agent.memory.index;

import cumt.zongzuo.community.ai.agent.memory.AgentMemoryMapper;
import cumt.zongzuo.community.ai.agent.memory.AgentMemorySafetyPolicy;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import cumt.zongzuo.community.ai.provider.EmbeddingResult;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.runtime.AiInvocationContext;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentMemoryVectorProjectionTaskTest {
    private final Instant now = Instant.parse("2026-11-08T00:00:00Z");
    private final AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
    private final MemoryVectorRepository repository = mock(MemoryVectorRepository.class);
    private final Set<Long> projected = new HashSet<>();
    private final List<MemoryProjectionRow> pending = new ArrayList<>();
    private boolean failFirstOwner;
    private final List<List<String>> embedded = new ArrayList<>();
    private final MutableClock clock = new MutableClock(now);
    private final AiCapabilityExecutor executor = new AiCapabilityExecutor() {
        public <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation) {
            try { return operation.get(); } catch (Throwable error) { throw new RuntimeException(error); }
        }
        public <A,T> T execute(AiInvocationContext context, AttemptObserver<A,T> observer, AttemptOperation<A,T> operation) {
            throw new UnsupportedOperationException();
        }
    };
    private final EmbeddingGateway embedding = command -> {
        embedded.add(command.inputs());
        if (failFirstOwner && command.inputs().getFirst().equals("记忆1")) throw new IllegalStateException("unavailable");
        return new EmbeddingResult(command.inputs().stream().map(s -> vector()).toList(), "test", "bge-m3");
    };

    @Test
    void failedOwnerDoesNotBlockOtherUsersAndDurableWorkSurvivesTaskRestart() {
        pending.addAll(List.of(row(1, 7), row(2, 8), row(3, 7)));
        setup();
        failFirstOwner = true;

        task().projectPending();

        assertThat(projected).containsExactlyInAnyOrder(2L, 3L);
        assertThat(pending).extracting(MemoryProjectionRow::memoryVersionId).containsExactly(1L);
        failFirstOwner = false;
        // 新实例没有任何内存任务状态，从仍存于数据库的 PENDING/FAILED 恢复。
        task().projectPending();
        assertThat(projected).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(pending).isEmpty();
    }

    @Test
    void boundedPageDoesNotDrainAnEntireOwnersBacklogInOneTick() {
        for (int i = 1; i <= 70; i++) pending.add(row(i, 7));
        setup();
        AgentMemoryVectorProjectionTask task = task();

        task.projectPending();
        assertThat(projected).hasSize(32).contains(1L, 32L).doesNotContain(33L);
        task.projectPending();
        assertThat(projected).hasSize(64).contains(64L).doesNotContain(65L);
        task.projectPending();
        assertThat(projected).hasSize(70);
        assertThat(embedded).extracting(List::size).containsExactly(32, 32, 6);
    }

    @Test
    void exhaustedDeadlineResumesAfterAttemptedOwnerInsteadOfSkippingTheRemainingPage() {
        pending.addAll(List.of(row(1, 7), row(2, 8), row(3, 7)));
        setup();
        doAnswer(call -> {
            List<MemoryVectorDocument> docs = call.getArgument(0);
            if (docs.getFirst().source().userId() == 7) clock.instant = now.plusSeconds(30);
            return null;
        }).when(repository).upsertAndVerify(anyList(), any());
        AgentMemoryVectorProjectionTask task = task();
        task.projectPending();
        assertThat(projected).isEmpty();
        // 失败的 owner 7 已消费本轮预算；下一轮必须接着处理 owner 8。
        task.projectPending();
        assertThat(projected).contains(2L, 3L).doesNotContain(1L);
    }

    private void setup() {
        when(mapper.enabled(anyLong())).thenReturn(true);
        when(mapper.listPendingVectorProjections(eq("bge-m3"), anyLong(), eq(32))).thenAnswer(call ->
                pending.stream().filter(row -> row.memoryVersionId() > call.<Long>getArgument(1)).limit(32).toList());
        when(mapper.markVectorProjected(anyLong(), anyLong(), anyLong(), eq("bge-m3"))).thenAnswer(call -> {
            long versionId = call.getArgument(0);
            long userId = call.getArgument(1);
            assertThat(pending).anySatisfy(row -> {
                assertThat(row.memoryVersionId()).isEqualTo(versionId);
                assertThat(row.userId()).isEqualTo(userId);
            });
            pending.removeIf(row -> row.memoryVersionId() == versionId);
            projected.add(versionId);
            return 1;
        });
    }

    private AgentMemoryVectorProjectionTask task() {
        AgentMemoryVectorService service = new AgentMemoryVectorService(mapper, repository, executor, embedding,
                new AgentMemorySafetyPolicy(), clock, Duration.ofSeconds(5), "bge-m3", 32);
        return new AgentMemoryVectorProjectionTask(mapper, service, clock, Duration.ofSeconds(20), "bge-m3", 32);
    }

    private static MemoryProjectionRow row(long id, long userId) {
        return new MemoryProjectionRow(id, userId, id, "PREFERENCE", "LOW", "记忆" + id, "hash" + id,
                null, "PENDING", 0);
    }
    private static float[] vector() { float[] value = new float[1024]; value[0] = 1; return value; }
    private static class MutableClock extends Clock {
        Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }
}
