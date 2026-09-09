package cumt.zongzuo.community.ai.agent.memory.index;

import cumt.zongzuo.community.ai.agent.memory.*;
import cumt.zongzuo.community.ai.provider.*;
import cumt.zongzuo.community.ai.runtime.*;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import java.util.stream.LongStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentMemoryVectorServiceTest {
    private final Instant now = Instant.parse("2026-09-09T00:00:00Z");
    private final Instant deadline = now.plusSeconds(60);
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
    private final MemoryVectorRepository vectors = mock(MemoryVectorRepository.class);
    private final List<List<String>> embedded = new ArrayList<>();
    private final List<AiInvocationContext> invocations = new ArrayList<>();
    private EmbeddingGateway embedding = command -> {
        embedded.add(command.inputs());
        return new EmbeddingResult(command.inputs().stream().map(s -> vector()).toList(), "test", "bge-m3");
    };

    @Test
    void synchronizesEveryPageIncludingMemoriesOlderThanOneHundredAndMarksOnlyAfterVisibility() {
        setup(LongStream.rangeClosed(1, 130).mapToObj(id -> row(id, "PENDING")).toList());
        Set<Long> visible = new HashSet<>();
        doAnswer(call -> {
            List<MemoryVectorDocument> docs = call.getArgument(0);
            assertThat(docs.size()).isLessThanOrEqualTo(32);
            docs.forEach(doc -> visible.add(doc.source().memoryVersionId()));
            return null;
        }).when(vectors).upsertAndVerify(anyList(), eq(deadline));
        when(mapper.markVectorProjected(anyLong(), anyLong(), anyLong(), anyString())).thenAnswer(call -> {
            assertThat(visible).contains(call.<Long>getArgument(0));
            return 1;
        });

        service().synchronize(7, deadline);

        assertThat(visible).hasSize(130).contains(1L, 130L);
        assertThat(embedded).hasSize(5);
        assertThat(invocations).allSatisfy(context -> assertThat(context.deadline()).isEqualTo(now.plusSeconds(5)));
    }

    @Test
    void invisibleUpsertFailsWithoutPromotingMysqlAndCanRetrySameVersion() {
        setup(List.of(row(1, "PENDING")));
        doThrow(new IllegalStateException("not visible")).doNothing()
                .when(vectors).upsertAndVerify(anyList(), eq(deadline));

        assertThatThrownBy(() -> service().synchronize(7, deadline)).hasMessageContaining("not visible");
        verify(mapper, never()).markVectorProjected(anyLong(), anyLong(), anyLong(), anyString());
        verify(mapper).markVectorFailed(1, 7, 0, "VECTOR_SYNC_FAILED");
        service().synchronize(7, deadline);
        verify(mapper).markVectorProjected(1, 7, 0, "bge-m3");
    }

    @Test
    void deletesWithoutEmbeddingAndDoesNotConvertDeletingRowBackToProjected() {
        setup(List.of(row(5, "DELETING")));
        service().synchronize(7, deadline);
        verify(vectors).deleteAndVerify(7, List.of(5L), deadline);
        verify(mapper).markVectorDeleted(5, 7, 0);
        verify(mapper, never()).markVectorProjected(anyLong(), anyLong(), anyLong(), anyString());
        assertThat(embedded).isEmpty();
    }

    @Test
    void rejectsStaleCasInsteadOfCertifyingConcurrentReplacement() {
        setup(List.of(row(1, "PENDING")));
        when(mapper.markVectorProjected(1, 7, 0, "bge-m3")).thenReturn(0);
        assertThatThrownBy(() -> service().synchronize(7, deadline)).hasMessageContaining("changed");
        verify(mapper).invalidateVectorModelConflict(1, 7, "bge-m3");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateWriterRequeuesCompletedDeletionAcrossServiceInstancesWithoutDeletingNewVersion(boolean rpcFailsAfterWrite) {
        AtomicReference<String> state = new AtomicReference<>("PENDING");
        AtomicLong lockVersion = new AtomicLong();
        AtomicBoolean retired = new AtomicBoolean();
        Set<Long> physicallyVisible = new HashSet<>(Set.of(1L, 2L));
        when(mapper.enabled(7)).thenReturn(true);
        when(mapper.listVectorWork(7, "bge-m3", 0, 32)).thenReturn(List.of(row(1, "PENDING")));
        when(mapper.listVectorDeletes(eq(7L), anyLong(), eq(32))).thenAnswer(call ->
                "DELETING".equals(state.get()) && call.<Long>getArgument(1) < 1
                        ? List.of(new MemoryProjectionRow(1, 7, 1, "PREFERENCE", "LOW", "旧偏好", "hash",
                                null, "DELETING", lockVersion.get())) : List.of());
        doAnswer(call -> {
            List<Long> ids = call.getArgument(1);
            physicallyVisible.removeAll(ids);
            return null;
        }).when(vectors).deleteAndVerify(eq(7L), anyList(), eq(deadline));
        when(mapper.markVectorDeleted(eq(1L), eq(7L), anyLong())).thenAnswer(call -> {
            if (!"DELETING".equals(state.get()) || call.<Long>getArgument(2) != lockVersion.get()) return 0;
            state.set("DELETED");
            lockVersion.incrementAndGet();
            return 1;
        });
        when(mapper.requeueRetiredVector(1, 7)).thenAnswer(call -> {
            if (!retired.get()) return 0;
            state.set("DELETING");
            lockVersion.incrementAndGet();
            return 1;
        });
        // 第二个实例代表独立清理进程，不共享 service 或 SDK 的实例锁。
        AgentMemoryVectorService cleanup = service();
        embedding = command -> {
            retired.set(true);
            state.set("DELETING");
            lockVersion.incrementAndGet();
            cleanup.deletePending(7, deadline);
            assertThat(state).hasValue("DELETED");
            assertThat(physicallyVisible).containsExactly(2L);
            return new EmbeddingResult(List.of(vector()), "test", "bge-m3");
        };
        doAnswer(call -> {
            physicallyVisible.add(1L);
            if (rpcFailsAfterWrite) throw new IllegalStateException("RPC failed after write");
            return null;
        }).when(vectors).upsertAndVerify(anyList(), eq(deadline));

        assertThatThrownBy(() -> service().synchronize(7, deadline)).isInstanceOf(IllegalStateException.class);

        assertThat(state).hasValue("DELETING");
        assertThat(physicallyVisible).containsExactlyInAnyOrder(1L, 2L);
        cleanup.deletePending(7, deadline);
        assertThat(state).hasValue("DELETED");
        assertThat(physicallyVisible).containsExactly(2L);
        verify(mapper).requeueRetiredVector(1, 7);
        verify(mapper, never()).requeueRetiredVector(2, 7);
    }

    @Test
    void recallsOnlyVectorIdsValidatedAgainstCurrentMysqlVersionAndNeverSelectsRecentCandidates() {
        setup(List.of());
        AgentMemoryView current = new AgentMemoryView(20, "PREFERENCE", "喜欢简短回答", 2, "ACTIVE", null, "MANUAL");
        when(vectors.search(eq(7L), eq("bge-m3"), any(), eq(12), eq(deadline)))
                .thenReturn(List.of(90L, 91L, 92L));
        when(mapper.findVectorRecall(91, 7, "bge-m3")).thenReturn(current);

        assertThat(service().recall(7, "请言简意赅", 3, deadline)).containsExactly(current);
        verify(mapper, never()).listActive(anyLong(), anyInt());
        assertThat(embedded).containsExactly(List.of("请言简意赅"));
    }

    @Test
    void disabledMemoryPerformsNoEmbeddingOrVectorSearch() {
        when(mapper.enabled(7)).thenReturn(false);
        assertThat(service().recall(7, "问题", 3, deadline)).isEmpty();
        service().synchronize(7, deadline);
        verifyNoInteractions(vectors);
    }

    @Test
    void disabledMemoryStillDeletesPendingPhysicalVectors() {
        when(mapper.enabled(7)).thenReturn(false);
        when(mapper.listVectorDeletes(7, 0, 32)).thenReturn(List.of(row(5, "DELETING")));
        when(mapper.markVectorDeleted(5, 7, 0)).thenReturn(1);
        service().synchronize(7, deadline);
        verify(vectors).deleteAndVerify(7, List.of(5L), deadline);
        verify(mapper).markVectorDeleted(5, 7, 0);
        assertThat(embedded).isEmpty();
    }

    @Test
    void versionDeletedAfterInitialValidationIsRecheckedBeforeReturning() {
        setup(List.of());
        when(vectors.search(anyLong(), anyString(), any(), anyInt(), any())).thenReturn(List.of(91L));
        when(mapper.findVectorRecall(91, 7, "bge-m3")).thenReturn(
                new AgentMemoryView(1, "PROFILE", "喜欢代码", 1, "ACTIVE", null, "MANUAL"), null);
        assertThat(service().recall(7, "问题", 3, deadline)).isEmpty();
    }

    @Test
    void deadlineAndModelDimensionMismatchesFailClosed() {
        setup(List.of(row(1, "PENDING")));
        assertThatThrownBy(() -> service().synchronize(7, now)).hasMessageContaining("deadline");
        embedding = command -> new EmbeddingResult(List.of(vector()), "test", "other-model");
        assertThatThrownBy(() -> service().synchronize(7, deadline)).hasMessageContaining("incompatible");
        embedding = command -> new EmbeddingResult(List.of(new float[]{1F}), "test", "bge-m3");
        assertThatThrownBy(() -> service().synchronize(7, deadline)).hasMessageContaining("1024");
        verify(vectors, never()).upsertAndVerify(anyList(), any());
    }

    @Test
    void sensitiveContentCannotReachEmbeddingAndNetworkNeverJoinsSqlTransaction() {
        MemoryProjectionRow unsafe = new MemoryProjectionRow(1, 7, 1, "PROFILE", "LOW", "密码是abcd1234", "hash", null, "PENDING", 0);
        setup(List.of(unsafe));
        assertThatThrownBy(() -> service().synchronize(7, deadline)).hasMessageContaining("safety");
        assertThat(embedded).isEmpty();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service().synchronize(7, deadline)).hasMessageContaining("transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void disablingSettingDuringSearchSuppressesAlreadyValidatedResults() {
        setup(List.of());
        when(vectors.search(anyLong(), anyString(), any(), anyInt(), any())).thenAnswer(call -> {
            when(mapper.enabled(7)).thenReturn(false);
            return List.of(91L);
        });
        when(mapper.findVectorRecall(91, 7, "bge-m3")).thenReturn(
                new AgentMemoryView(1, "PROFILE", "喜欢代码", 1, "ACTIVE", null, "MANUAL"));
        assertThat(service().recall(7, "问题", 3, deadline)).isEmpty();
    }

    private void setup(List<MemoryProjectionRow> rows) {
        when(mapper.enabled(7)).thenReturn(true);
        when(mapper.listVectorWork(eq(7L), eq("bge-m3"), anyLong(), eq(32))).thenAnswer(call -> {
            long after = call.getArgument(2);
            return rows.stream().filter(row -> row.memoryVersionId() > after).limit(32).toList();
        });
        when(mapper.markVectorProjected(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(1);
        when(mapper.markVectorDeleted(anyLong(), anyLong(), anyLong())).thenReturn(1);
    }

    private AgentMemoryVectorService service() {
        return new AgentMemoryVectorService(mapper, vectors, new AiCapabilityExecutor() {
            public <T> T execute(AiInvocationContext context, CheckedSupplier<T> operation) {
                invocations.add(context);
                try { return operation.get(); } catch (Throwable error) { throw new RuntimeException(error); }
            }
            public <A,T> T execute(AiInvocationContext context, AttemptObserver<A,T> observer, AttemptOperation<A,T> operation) {
                throw new UnsupportedOperationException();
            }
        }, embedding, new AgentMemorySafetyPolicy(), clock, Duration.ofSeconds(5), "bge-m3", 32);
    }

    private static MemoryProjectionRow row(long id, String state) {
        return new MemoryProjectionRow(id, 7, id, "PREFERENCE", "LOW", "喜欢简短回答" + id, "hash" + id, null, state, 0);
    }
    private static float[] vector() { float[] value = new float[1024]; value[0] = 1; return value; }
}
