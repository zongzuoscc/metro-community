package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnAdmission;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnLifecycleService;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnRecord;
import cumt.zongzuo.community.ai.agent.temporary.TemporaryTurnStore;
import cumt.zongzuo.community.ai.agent.turn.AgentRunLeaseStore;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnLeaseService;
import cumt.zongzuo.community.ai.agent.turn.AgentTurnMapper;
import cumt.zongzuo.community.ai.web.AiApiException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 验证热路径保留取消、栅栏及临时会话边界，但不再启动写事务或延长 Redis TTL。 */
class AgentRunLeaseReadTest {
    @Test
    @SuppressWarnings("unchecked")
    void redisCheckReadsExactRunAndFenceWithoutExtendingTtl() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        UUID run = UUID.randomUUID();
        when(values.get("agent:run:user:9")).thenReturn(run + ":7", run + ":8", null);
        var store = new AgentRunLeaseStore(redis);

        assertThat(store.isCurrent(9, run, 7)).isTrue();
        assertThat(store.isCurrent(9, run, 7)).isFalse();
        assertThat(store.isCurrent(9, run, 7)).isFalse();

        verify(redis, times(3)).opsForValue();
        verify(values, times(3)).get("agent:run:user:9");
        verifyNoMoreInteractions(redis, values);
    }

    @Test
    void persistentCheckRejectsSqlCancellationEvenWhenRedisLeaseStillExists() {
        var redis = mock(AgentRunLeaseStore.class);
        var mapper = mock(AgentTurnMapper.class);
        var tx = mock(PlatformTransactionManager.class);
        UUID run = UUID.randomUUID();
        when(redis.isCurrent(9, run, 7)).thenReturn(true);
        when(mapper.isPersistentRunCurrent(41, 9, run, 7)).thenReturn(true, false);
        var service = new AgentTurnLeaseService(redis, mapper, tx);

        assertThat(service.isRunning(41, 9, run, 7)).isTrue();
        assertThat(service.isRunning(41, 9, run, 7)).isFalse();

        verify(mapper, times(2)).isPersistentRunCurrent(41, 9, run, 7);
        verify(redis, times(2)).isCurrent(9, run, 7);
        verifyNoMoreInteractions(mapper, redis);
        verifyNoInteractions(tx);
    }

    @Test
    void missingRedisLeaseDoesNotTouchMysql() {
        var redis = mock(AgentRunLeaseStore.class);
        var mapper = mock(AgentTurnMapper.class);
        var tx = mock(PlatformTransactionManager.class);
        var service = new AgentTurnLeaseService(redis, mapper, tx);

        assertThat(service.isRunning(41, 9, UUID.randomUUID(), 7)).isFalse();
        verifyNoInteractions(mapper, tx);
    }

    @Test
    void temporaryCheckRevalidatesParentAndStopsAfterAbsoluteExpiry() {
        var redis = mock(AgentRunLeaseStore.class);
        var mapper = mock(AgentTurnMapper.class);
        var turns = mock(TemporaryTurnStore.class);
        var tx = mock(PlatformTransactionManager.class);
        UUID run = UUID.randomUUID(), session = UUID.randomUUID();
        var admission = new TemporaryTurnAdmission(-7, session, run, 3, true, "RUNNING");
        when(redis.isCurrent(9, run, 3)).thenReturn(true);
        when(mapper.isTemporaryRunCurrent(9, run, 3)).thenReturn(true);
        when(turns.find(-7, 9)).thenReturn(new TemporaryTurnRecord(-7, 9, session, UUID.randomUUID(),
                run, 3, "hash", "RUNNING", true, "问题", null, null, 0, Instant.now(), null))
                .thenThrow(AiApiException.temporarySessionExpired());
        var service = new TemporaryTurnLifecycleService(turns, mapper, redis, tx);

        assertThat(service.isRunning(admission, 9)).isTrue();
        assertThatThrownBy(() -> service.isRunning(admission, 9))
                .isInstanceOf(AiApiException.class).hasMessage("TEMPORARY_SESSION_EXPIRED");

        verify(turns, times(2)).find(-7, 9);
        verify(mapper, times(2)).isTemporaryRunCurrent(9, run, 3);
        verify(redis, times(2)).isCurrent(9, run, 3);
        verifyNoMoreInteractions(turns, mapper, redis);
        verifyNoInteractions(tx);
    }

    @Test
    void temporaryCheckRejectsReplacedFenceOrCancelledTurn() {
        var redis = mock(AgentRunLeaseStore.class);
        var mapper = mock(AgentTurnMapper.class);
        var turns = mock(TemporaryTurnStore.class);
        var tx = mock(PlatformTransactionManager.class);
        UUID run = UUID.randomUUID(), session = UUID.randomUUID();
        var admission = new TemporaryTurnAdmission(-7, session, run, 3, true, "RUNNING");
        when(redis.isCurrent(9, run, 3)).thenReturn(true);
        when(mapper.isTemporaryRunCurrent(9, run, 3)).thenReturn(true);
        when(turns.find(-7, 9)).thenReturn(
                new TemporaryTurnRecord(-7, 9, session, UUID.randomUUID(), run, 4,
                        "hash", "RUNNING", true, "问题", null, null, 0, Instant.now(), null),
                new TemporaryTurnRecord(-7, 9, session, UUID.randomUUID(), run, 3,
                        "hash", "CANCELLED", true, "问题", null, null, 0, Instant.now(), null));
        var service = new TemporaryTurnLifecycleService(turns, mapper, redis, tx);

        assertThat(service.isRunning(admission, 9)).isFalse();
        assertThat(service.isRunning(admission, 9)).isFalse();
        verifyNoInteractions(tx);
    }
}
