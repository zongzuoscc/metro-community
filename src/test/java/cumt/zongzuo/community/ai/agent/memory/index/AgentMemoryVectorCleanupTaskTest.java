package cumt.zongzuo.community.ai.agent.memory.index;

import cumt.zongzuo.community.ai.agent.memory.AgentMemoryMapper;
import cumt.zongzuo.community.ai.agent.memory.AgentMemorySafetyPolicy;
import cumt.zongzuo.community.ai.runtime.AiCapabilityExecutor;
import cumt.zongzuo.community.ai.provider.EmbeddingGateway;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentMemoryVectorCleanupTaskTest {
    @Test
    void tombstoneSweepRepairsWriterCrashWithoutCatchAndWrapsForLaterResurrection() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        MemoryVectorRepository vectors = mock(MemoryVectorRepository.class);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AtomicReference<String> state = new AtomicReference<>("DELETED");
        AtomicLong version = new AtomicLong(2);
        // 写者 A 已在删除确认之后写回旧版本 1，随后崩溃；不存在任何异常回调。
        // 版本 2 是当前有效记忆，巡检不能误删它。
        Set<Long> visible = new HashSet<>(Set.of(1L, 2L));
        when(mapper.listRetiredVectorTombstones(0, 32)).thenAnswer(call ->
                "DELETED".equals(state.get()) ? List.of(new MemoryVectorTombstone(1, 7, version.get())) : List.of());
        when(mapper.requeueRetiredVectorTombstone(eq(1L), eq(7L), anyLong())).thenAnswer(call -> {
            if (!"DELETED".equals(state.get()) || call.<Long>getArgument(2) != version.get()) return 0;
            state.set("DELETING");
            version.incrementAndGet();
            return 1;
        });
        when(mapper.listVectorDeletionUsers(anyLong(), eq(32))).thenAnswer(call ->
                "DELETING".equals(state.get()) && call.<Long>getArgument(0) < 7 ? List.of(7L) : List.of());
        when(mapper.listVectorDeletes(eq(7L), anyLong(), eq(32))).thenAnswer(call ->
                "DELETING".equals(state.get()) && call.<Long>getArgument(1) < 1
                        ? List.of(new MemoryProjectionRow(1, 7, 1, "PREFERENCE", "LOW", "旧偏好", "hash",
                                null, "DELETING", version.get())) : List.of());
        doAnswer(call -> { visible.removeAll(call.<List<Long>>getArgument(1)); return null; })
                .when(vectors).deleteAndVerify(eq(7L), anyList(), any());
        when(mapper.markVectorDeleted(eq(1L), eq(7L), anyLong())).thenAnswer(call -> {
            if (!"DELETING".equals(state.get()) || call.<Long>getArgument(2) != version.get()) return 0;
            state.set("DELETED");
            version.incrementAndGet();
            return 1;
        });
        AgentMemoryVectorService service = new AgentMemoryVectorService(mapper, vectors,
                mock(AiCapabilityExecutor.class), mock(EmbeddingGateway.class), new AgentMemorySafetyPolicy(),
                clock, Duration.ofSeconds(5), "bge-m3", 32);
        AgentMemoryVectorCleanupTask task = new AgentMemoryVectorCleanupTask(mapper, service, clock, Duration.ofSeconds(20));

        task.cleanup();

        assertThat(visible).containsExactly(2L);
        assertThat(state).hasValue("DELETED");
        verify(mapper).requeueRetiredVectorTombstone(1, 7, 2);
        // 即使另一个迟到写者也崩溃，游标回绕仍会再次发现相同墓碑。
        visible.add(1L);
        task.cleanup();
        task.cleanup();
        assertThat(visible).containsExactly(2L);
        verify(mapper, times(2)).requeueRetiredVectorTombstone(eq(1L), eq(7L), anyLong());
        verify(mapper, never()).requeueRetiredVectorTombstone(eq(2L), anyLong(), anyLong());
        verify(mapper, never()).enabled(anyLong());
    }

    @Test
    void deletionWorkerSkipsFailedOwnerContinuesAndWrapsCursorForRetry() {
        AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
        AgentMemoryVectorService service = mock(AgentMemoryVectorService.class);
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        when(mapper.listVectorDeletionUsers(0, 32)).thenReturn(List.of(7L, 8L));
        when(mapper.listVectorDeletionUsers(8, 32)).thenReturn(List.of());
        doThrow(new IllegalStateException("unavailable")).when(service).deletePending(eq(7L), any());
        AgentMemoryVectorCleanupTask task = new AgentMemoryVectorCleanupTask(mapper, service,
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(20));
        task.cleanup();
        task.cleanup();
        task.cleanup();
        verify(service, times(2)).deletePending(7, now.plusSeconds(20));
        verify(service, times(2)).deletePending(8, now.plusSeconds(20));
        verify(mapper, never()).enabled(anyLong());
    }
}
