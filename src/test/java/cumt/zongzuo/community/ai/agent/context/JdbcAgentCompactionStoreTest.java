package cumt.zongzuo.community.ai.agent.context;

import cumt.zongzuo.community.ai.agent.history.AgentConversationHistoryHit;
import cumt.zongzuo.community.ai.agent.memory.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 测本地事务与栅栏，不把 Mock 测试描述为已验证真实 MySQL/Milvus 跨库行为。 */
class JdbcAgentCompactionStoreTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final AgentMemoryMapper memories=mock(AgentMemoryMapper.class);
    final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
    final UUID run=UUID.randomUUID();
    final AgentCompactionStore.Snapshot snapshot=new AgentCompactionStore.Snapshot(4,1,1,true,0,0,"");
    final JdbcAgentCompactionStore store=spy(new JdbcAgentCompactionStore(jdbc,memories,new AgentMemorySafetyPolicy(),tx,true));
    final AgentCompactionModel.Extraction extraction=new AgentCompactionModel.Extraction("讨论外套偏好",List.of(
            new AgentCompactionModel.Memory("PREFERENCE","选外套偏向于黑色",2,"外套偏向于黑色")));
    final List<AgentConversationHistoryHit> evidence=List.of(
            new AgentConversationHistoryHit(2,1,1,"USER","我选外套偏向于黑色",LocalDateTime.now()));

    void ready() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForList(anyString(),eq(Long.class),any(Object[].class))).thenReturn(List.of(1L));
        doReturn(snapshot).when(store).snapshot(1);
        doReturn(null).when(store).pending(1,1,0);
        when(jdbc.update(any(PreparedStatementCreator.class),any(KeyHolder.class))).thenAnswer(call->{
            call.<KeyHolder>getArgument(1).getKeyList().add(Map.of("id",10L)); return 1;
        });
        when(memories.insertItem(any())).thenAnswer(call->{ call.<AgentMemoryMapper.MemoryInsert>getArgument(0).id=20L; return 1; });
        when(memories.insertVersion(any())).thenAnswer(call->{ call.<AgentMemoryMapper.MemoryVersionInsert>getArgument(0).id=30L; return 1; });
        when(memories.activateVersion(20,1,30)).thenReturn(1);
    }
    @Test void stageCommitsFactSourceAndPendingProjectionTogether() {
        ready();
        var result=store.stage(1,run,snapshot,1,extraction,evidence);
        assertThat(result.id()).isEqualTo(10);
        var order=inOrder(memories,tx);
        order.verify(memories).insertItem(any());
        order.verify(memories).insertVersion(any());
        order.verify(memories).activateVersion(20,1,30);
        order.verify(memories).insertSource(1,20,30,1,2);
        order.verify(memories).insertProjection(30,1);
        order.verify(memories).incrementEpoch(1);
        order.verify(tx).commit(any());
    }
    @Test void changedMemoryEpochRollsBackBeforeAnyInsert() {
        ready();
        doReturn(new AgentCompactionStore.Snapshot(4,1,2,true,0,0,"")).when(store).snapshot(1);
        assertThatThrownBy(()->store.stage(1,run,snapshot,1,extraction,evidence)).hasMessageContaining("snapshot changed");
        verify(memories,never()).insertItem(any()); verify(tx).rollback(any());
    }
    @Test void lostRunLeaseMustNotActivateSummary() {
        ready();
        when(jdbc.queryForList(contains("agent_run_guard"),eq(Long.class),any(Object[].class))).thenReturn(List.of());
        assertThatThrownBy(()->store.activate(1,run,new AgentCompactionStore.Batch(10,1,1,"摘要",true)))
                .hasMessageContaining("fence lost");
        verify(jdbc,never()).update(startsWith("UPDATE agent_context_compaction"),any(Object[].class));
        verify(tx).rollback(any());
    }
    @Test void duplicateContentNeverResurrectsDeletedMemory() {
        ready(); when(memories.contentHashCount(eq(1L),anyString())).thenReturn(1);
        store.stage(1,run,snapshot,1,extraction,evidence);
        verify(memories,never()).insertItem(any()); verify(memories,never()).incrementEpoch(anyLong());
        verify(tx).commit(any());
    }
    @Test void invalidEvidenceCannotOpenTransaction() {
        assertThatThrownBy(()->store.stage(2,run,snapshot,1,extraction,evidence)).hasMessageContaining("source evidence");
        verifyNoInteractions(tx,jdbc,memories);
    }
}
