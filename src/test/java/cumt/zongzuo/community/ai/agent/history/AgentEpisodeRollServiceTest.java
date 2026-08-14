package cumt.zongzuo.community.ai.agent.history;

import cumt.zongzuo.community.ai.agent.turn.AgentRunGuardRecord;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEpisodeRollServiceTest {

    @Test
    void sealsAndReplacesAnEpisodeAfterTheConfiguredTurnThreshold() {
        AgentTurnMapper mapper = mock(AgentTurnMapper.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        AgentRunGuardRecord guard = new AgentRunGuardRecord();
        guard.setUserId(7L);
        when(mapper.selectGuardForUpdate(7L)).thenReturn(guard);
        when(mapper.selectConversationIdForUpdate(7L)).thenReturn(9L);
        when(mapper.selectActiveEpisodeStatsForUpdate(7L, 9L))
                .thenReturn(new AgentEpisodeStats(31L, 20));
        when(mapper.sealActiveEpisode(31L, 7L)).thenReturn(1);
        when(mapper.insertNextEpisode(7L, 9L)).thenReturn(1);

        boolean rolled = new AgentEpisodeRollService(mapper, transactions, 20)
                .rollIfThresholdReached(7L);

        assertThat(rolled).isTrue();
        verify(mapper).sealActiveEpisode(31L, 7L);
        verify(mapper).insertNextEpisode(7L, 9L);
    }
}
