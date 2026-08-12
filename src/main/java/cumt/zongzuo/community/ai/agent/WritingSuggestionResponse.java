package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;

/** 只读写作提案；应用动作只能由前端编辑器在用户确认后发起。 */
public record WritingSuggestionResponse(String operation, String originalText,
                                        String suggestedText, int selectionFrom, int selectionTo,
                                        long documentVersion, UserAiFundingSource fundingSource,
                                        String provider, String model) {
}
