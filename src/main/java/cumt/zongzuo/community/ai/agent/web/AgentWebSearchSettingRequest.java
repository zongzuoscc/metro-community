package cumt.zongzuo.community.ai.agent.web;

import jakarta.validation.constraints.NotNull;

/** 主对话联网开关请求；包装类型用于拒绝缺失字段。 */
public record AgentWebSearchSettingRequest(@NotNull Boolean enabled) {
}
