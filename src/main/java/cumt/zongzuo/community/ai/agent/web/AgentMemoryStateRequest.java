package cumt.zongzuo.community.ai.agent.web;

import jakarta.validation.constraints.Positive;

/**
 * 用户暂停或恢复单条长期记忆时提交的乐观锁参数。
 * expectedVersion 对应界面已经读到的不可变内容版本，防止用户在旧页面上操作已被编辑的新版本。
 */
public record AgentMemoryStateRequest(boolean paused, @Positive long expectedVersion) {
}
