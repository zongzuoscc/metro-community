package cumt.zongzuo.community.ai.agent.memory;

import cumt.zongzuo.community.ai.web.AiApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 校验工具权限、真实原文和提交代际；数据库锁行为另由 MySQL 集成测试验证。 */
class AgentMemoryToolServiceTest {
    final AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
    final AgentMemoryManagementService management = mock(AgentMemoryManagementService.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final UUID runId = UUID.randomUUID();
    final AgentMemoryToolService service = new AgentMemoryToolService(
            mapper, management, transactions, new AgentMemorySafetyPolicy());
    final AgentMemoryView original = new AgentMemoryView(31, "PREFERENCE", "回答时先给结论", 1,
            "ACTIVE", null, "CONVERSATION");
    final AgentMemoryView revised = new AgentMemoryView(31, "PREFERENCE", "回答时分步骤解释", 2,
            "ACTIVE", null, "CONVERSATION");

    @BeforeEach void ready() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.lockActiveAccountForMemoryTools(9)).thenReturn(9L);
        when(mapper.lockPersistentRunFence(9, runId)).thenReturn(6L);
        when(mapper.lockConversationForMemory(9)).thenReturn(7L);
        when(mapper.lockMemoryEnabled(9)).thenReturn(true);
        when(mapper.lockMemoryEpoch(9)).thenReturn(10L);
        when(mapper.lockPersistentTurnId(9, runId, 6)).thenReturn(21L);
        when(mapper.sourceMessageId(21, 9)).thenReturn(22L);
        when(mapper.messageContent(22, 9)).thenReturn("回答时先给结论，之后改成回答时分步骤解释，忘掉原来的回答习惯。");
        when(mapper.currentVersionId(31, 9)).thenReturn(41L);
        when(mapper.projectionState(31, 9)).thenReturn("PENDING");
        when(mapper.insertSource(anyLong(), anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(1);
        when(mapper.findMutationTarget(31, 9)).thenReturn(original);
        when(mapper.find(31, 9)).thenReturn(original);
    }

    @Test void addReturnsOnlyItsOwnCommittedEpochAndRecordsServerBoundSource() {
        when(management.create(9, "PREFERENCE", original.content(), null)).thenReturn(original);
        when(mapper.lockMemoryEpoch(9)).thenReturn(10L, 11L);

        var result = service.add(9, runId, 10, "PREFERENCE", original.content(), "回答时先给结论");

        assertThat(result.status()).isEqualTo("ADDED");
        assertThat(result.view()).isEqualTo(original);
        assertThat(result.beforeEpoch()).isEqualTo(10);
        assertThat(result.afterEpoch()).isEqualTo(11);
        assertThat(result.indexState()).isEqualTo("PENDING");
        var order = inOrder(mapper, management, transactions);
        order.verify(mapper).lockActiveAccountForMemoryTools(9);
        order.verify(mapper).lockPersistentRunFence(9, runId);
        order.verify(mapper).lockConversationForMemory(9);
        order.verify(mapper).lockMemoryEnabled(9);
        order.verify(mapper).lockPersistentTurnId(9, runId, 6);
        order.verify(management).create(9, "PREFERENCE", original.content(), null);
        order.verify(mapper).insertSource(9, 31, 41, 21, 22);
        order.verify(transactions).commit(any());
    }

    @Test void existingActiveContentIsIdempotentWithoutAdvancingEpoch() {
        when(mapper.findCurrentByHash(eq(9L), anyString())).thenReturn(original);
        var result = service.add(9, runId, 10, "PREFERENCE", original.content(), "回答时先给结论");
        assertThat(result.status()).isEqualTo("ALREADY_EXISTS");
        assertThat(result.afterEpoch()).isEqualTo(10);
        verifyNoInteractions(management);
        verify(mapper, never()).insertSource(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test void deletedOrSupersededDuplicateCannotBeRecreated() {
        when(mapper.contentHashCount(eq(9L), anyString())).thenReturn(1);
        assertThatThrownBy(() -> service.add(9, runId, 10, "PREFERENCE", original.content(), "回答时先给结论"))
                .isInstanceOf(AiApiException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        verifyNoInteractions(management);
        verify(transactions).rollback(any());
    }

    @Test void wrongSourceCannotWriteEvenIfModelSuppliesAClaim() {
        assertThatThrownBy(() -> service.add(9, runId, 10, "PREFERENCE", "我喜欢蓝色", "我喜欢蓝色"))
                .hasMessage("VALIDATION_FAILED");
        verifyNoInteractions(management);
        verify(transactions).rollback(any());
    }

    @Test void sensitiveOrBlankContentIsRejectedBeforeOpeningTransaction() {
        assertThatThrownBy(() -> service.add(9, runId, 10, "PROFILE", "密码是hunter2", "密码是hunter2"))
                .hasMessage("VALIDATION_FAILED");
        verifyNoInteractions(transactions, management);
    }

    @Test void externalEpochChangeCancelsInsteadOfAdoptingLatestEpoch() {
        when(mapper.lockMemoryEpoch(9)).thenReturn(12L);
        assertThatThrownBy(() -> service.add(9, runId, 10, "PREFERENCE", original.content(), "回答时先给结论"))
                .isInstanceOf(CancellationException.class);
        verifyNoInteractions(management);
    }

    @Test void lostOrTemporaryRunCannotWrite() {
        when(mapper.lockPersistentRunFence(9, runId)).thenReturn(null);
        assertThatThrownBy(() -> service.add(9, runId, 10, "PREFERENCE", original.content(), "回答时先给结论"))
                .isInstanceOf(CancellationException.class);
        verifyNoInteractions(management);
    }

    @Test void expiredOrReplacedTurnCannotUseAnOtherwiseValidGuard() {
        when(mapper.lockPersistentTurnId(9, runId, 6)).thenReturn(null);
        assertThatThrownBy(() -> service.add(9, runId, 10, "PREFERENCE", original.content(), "回答时先给结论"))
                .isInstanceOf(CancellationException.class);
        verifyNoInteractions(management);
    }

    @Test void guardExpiringWhileWaitingForOtherLocksCannotAuthorizeAWrite() {
        when(mapper.lockPersistentRunFence(9, runId)).thenReturn(6L, null);
        assertThatThrownBy(() -> service.add(9, runId, 10, "PREFERENCE", original.content(), "回答时先给结论"))
                .isInstanceOf(CancellationException.class);
        verifyNoInteractions(management);
    }

    @Test void memoryDisabledPreventsAutomaticMutations() {
        when(mapper.lockMemoryEnabled(9)).thenReturn(false);
        assertThatThrownBy(() -> service.add(9, runId, 10, "PREFERENCE", original.content(), "回答时先给结论"))
                .isInstanceOf(CancellationException.class);
        verifyNoInteractions(management);
    }

    @Test void updatePreservesVersionPreconditionAndSourceForNewVersion() {
        when(management.edit(9, 31, revised.content(), 1)).thenReturn(revised);
        when(mapper.lockMemoryEpoch(9)).thenReturn(10L, 11L);
        when(mapper.find(31, 9)).thenReturn(revised);
        when(mapper.currentVersionId(31, 9)).thenReturn(42L);
        var result = service.update(9, runId, 10, 31, 1, revised.content(), "回答时分步骤解释");
        assertThat(result.view()).isEqualTo(revised);
        assertThat(result.status()).isEqualTo("UPDATED");
        assertThat(result.afterEpoch()).isEqualTo(11);
        verify(mapper).insertSource(9, 31, 42, 21, 22);
    }

    @Test void staleVersionAndForeignOwnerCannotModifyMemory() {
        assertThatThrownBy(() -> service.update(9, runId, 10, 31, 2, revised.content(), "回答时分步骤解释"))
                .hasMessage("OPTIMISTIC_LOCK_CONFLICT");
        when(mapper.findMutationTarget(31, 9)).thenReturn(null);
        assertThatThrownBy(() -> service.delete(9, runId, 10, 31, 1, "忘掉原来的回答习惯"))
                .hasMessage("RESOURCE_NOT_FOUND");
        verifyNoInteractions(management);
    }

    @Test void pausedAndExpiredMemoriesAreNotAutomaticallyReactivated() {
        when(mapper.findMutationTarget(31, 9)).thenReturn(
                new AgentMemoryView(31, "PREFERENCE", original.content(), 1, "PAUSED", null, "CONVERSATION"));
        assertThatThrownBy(() -> service.update(9, runId, 10, 31, 1, revised.content(), "回答时分步骤解释"))
                .hasMessage("OPTIMISTIC_LOCK_CONFLICT");
        when(mapper.findMutationTarget(31, 9)).thenReturn(
                new AgentMemoryView(31, "PREFERENCE", original.content(), 1, "ACTIVE", LocalDateTime.now().minusDays(1), "CONVERSATION"));
        assertThatThrownBy(() -> service.update(9, runId, 10, 31, 1, revised.content(), "回答时分步骤解释"))
                .hasMessage("OPTIMISTIC_LOCK_CONFLICT");
        verifyNoInteractions(management);
    }

    @Test void deleteInvalidatesImmediatelyAndRepeatedDeletionIsExplicitlyIdempotent() {
        when(mapper.lockMemoryEpoch(9)).thenReturn(10L, 11L);
        var deleted = service.delete(9, runId, 10, 31, 1, "忘掉原来的回答习惯");
        assertThat(deleted.status()).isEqualTo("DELETED");
        assertThat(deleted.view()).isNull();
        assertThat(deleted.indexState()).isEqualTo("DELETING");
        verify(management).delete(9, 31);

        when(mapper.lockMemoryEpoch(9)).thenReturn(11L);
        when(mapper.findMutationTarget(31, 9)).thenReturn(
                new AgentMemoryView(31, "PREFERENCE", original.content(), 1, "DELETED", null, "CONVERSATION"));
        var repeated = service.delete(9, runId, 11, 31, 1, "忘掉原来的回答习惯");
        assertThat(repeated.status()).isEqualTo("ALREADY_DELETED");
        assertThat(repeated.afterEpoch()).isEqualTo(11);
        verify(management, times(1)).delete(9, 31);
    }
}
