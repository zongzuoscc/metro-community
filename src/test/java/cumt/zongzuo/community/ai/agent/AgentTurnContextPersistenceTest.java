package cumt.zongzuo.community.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.ai.agent.turn.*;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentTurnContextPersistenceTest {
    /** 原始历史可以很长，但使用记录的 VARCHAR(1000) 快照不能使最终回答事务失败。 */
    @Test
    void persistsABoundedUnicodeExcerptAndAFullContentHashForLongHistory() {
        var mapper = mock(AgentTurnMapper.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var run = UUID.randomUUID();
        var guard = new AgentRunGuardRecord();
        guard.setActiveRunId(run);
        guard.setRunFence(1L);
        var turn = new AgentTurnRecord();
        turn.setRunId(run);
        turn.setRunFence(1L);
        turn.setState("RUNNING");
        turn.setConversationId(3L);
        turn.setEpisodeId(4L);
        when(mapper.selectOwner(5)).thenReturn(9L);
        when(mapper.selectGuardForUpdate(9)).thenReturn(guard);
        when(mapper.selectByIdForUpdate(5, 9)).thenReturn(turn);
        when(mapper.completeTurn(5, 9, run, 1)).thenReturn(1);
        when(mapper.releaseGuard(9, run, 1)).thenReturn(1);
        when(mapper.advanceConversation(3, 9, 12)).thenReturn(1);
        when(mapper.incrementEpisode(4, 9)).thenReturn(1);
        doAnswer(invocation -> {
            invocation.getArgument(0, AgentTurnMapper.AgentMessageInsert.class).setId(12L);
            return 1;
        }).when(mapper).insertAssistantMessage(any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
        when(mapper.insertPersonalContextUse(anyLong(), anyLong(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    String excerpt = invocation.getArgument(6);
                    assertThat(excerpt.codePointCount(0, excerpt.length())).isLessThanOrEqualTo(1000);
                    assertThat(excerpt).endsWith("🙂");
                    assertThat(invocation.getArgument(7, String.class)).contains("contentHash", "contentCodePoints");
                    return 1;
                });
        var answer = new GroundedAgentAnswer("最终回答", List.of(), "stop", List.of(),
                List.of(new AgentHistoryUse(1, 2, "ASSISTANT", "🙂".repeat(1500), LocalDateTime.now())),
                List.of(), UserAiFundingSource.PLATFORM, "qwen", "model");
        var finalizer = new AgentTurnFinalizer(mapper, mock(AgentRunLeaseStore.class), transactions,
                new ObjectMapper(), null, false, mock(ObjectProvider.class));
        assertThat(finalizer.complete(5, run, 1, answer)).isTrue();
    }
}
