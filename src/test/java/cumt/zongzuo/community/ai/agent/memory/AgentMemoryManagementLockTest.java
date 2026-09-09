package cumt.zongzuo.community.ai.agent.memory;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 管理事务必须与回答前压缩保持 conversation → setting/item 的同一锁序，避免反向等待。 */
class AgentMemoryManagementLockTest {
    @ParameterizedTest
    @ValueSource(strings = {"create", "edit", "expiry", "pause", "delete", "setting", "updateSetting"})
    void locksConversationBeforeAnySettingOrMemoryMutation(String operation) {
        var mapper = mock(AgentMemoryMapper.class);
        var transactionManager = mock(PlatformTransactionManager.class);
        var transaction = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        var memory = new AgentMemoryView(31, "PREFERENCE", "请先给我结论", 1,
                "ACTIVE", null, "MANUAL");
        when(mapper.find(31, 9)).thenReturn(memory);
        when(mapper.itemLockVersion(31, 9)).thenReturn(0L);
        when(mapper.activateVersion(anyLong(), eq(9L), anyLong())).thenReturn(1);
        when(mapper.updateCurrent(eq(31L), eq(9L), anyLong(), eq(0L))).thenReturn(1);
        when(mapper.updateExpiry(31, 9, null, 0)).thenReturn(1);
        when(mapper.updateState(31, 9, "ACTIVE", "PAUSED", 0)).thenReturn(1);
        when(mapper.deleteItem(31, 9)).thenReturn(1);
        when(mapper.updateSetting(9, false, 0)).thenReturn(1);
        doAnswer(invocation -> {
            invocation.getArgument(0, AgentMemoryMapper.MemoryInsert.class).id = 31L;
            return 1;
        }).when(mapper).insertItem(any());
        doAnswer(invocation -> {
            invocation.getArgument(0, AgentMemoryMapper.MemoryVersionInsert.class).id = 41L;
            return 1;
        }).when(mapper).insertVersion(any());
        var service = new AgentMemoryManagementService(mapper,
                new AgentMemoryRecallService(mapper, mock(org.springframework.beans.factory.ObjectProvider.class)),
                transactionManager, new AgentMemorySafetyPolicy());

        switch (operation) {
            case "create" -> assertThat(service.create(9, "PREFERENCE", "请先给我结论", null)).isEqualTo(memory);
            case "edit" -> assertThat(service.edit(9, 31, "请分步骤解释", 1)).isEqualTo(memory);
            case "expiry" -> assertThat(service.updateExpiry(9, 31, null, 1)).isEqualTo(memory);
            case "pause" -> assertThat(service.updateState(9, 31, true, 1)).isEqualTo(memory);
            case "delete" -> service.delete(9, 31);
            case "setting" -> assertThat(service.setting(9).version()).isZero();
            case "updateSetting" -> assertThat(service.updateSetting(9, false, 0).version()).isOne();
            default -> throw new AssertionError(operation);
        }

        var ordered = inOrder(transactionManager, mapper);
        ordered.verify(transactionManager).getTransaction(any());
        ordered.verify(mapper).lockConversationForMemory(9);
        if (operation.equals("create") || operation.equals("setting") || operation.equals("updateSetting")) {
            ordered.verify(mapper).ensureSetting(9);
        } else {
            ordered.verify(mapper).itemLockVersion(31, 9);
        }
        ordered.verify(transactionManager).commit(transaction);
    }
}
