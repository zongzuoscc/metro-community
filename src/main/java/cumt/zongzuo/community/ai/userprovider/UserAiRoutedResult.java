package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiChatResult;

import java.util.Objects;

/** 将模型原始结果与费用来源绑定，避免业务层根据 provider 字符串猜测谁付费。 */
public record UserAiRoutedResult(AiChatResult result, UserAiFundingSource fundingSource) {

    public UserAiRoutedResult {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(fundingSource, "fundingSource must not be null");
    }
}
