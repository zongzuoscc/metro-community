package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;

/** 页面 Agent 能力的通用响应，同时告知用户本次的模型与成本来源。 */
public record AgentCapabilityResponse(String content, UserAiFundingSource fundingSource,
                                      String provider, String model) {
}
