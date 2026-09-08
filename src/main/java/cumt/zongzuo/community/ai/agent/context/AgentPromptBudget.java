package cumt.zongzuo.community.ai.agent.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.userprovider.UserAiFundingSource;
import java.util.List;

/**
 * 统一估算最终序列化请求，而不是分别数片段后漏掉提示词、JSON 和角色开销。
 * CL100K 是本地估算器，不是所有模型的精确 tokenizer；另加 15% 余量并预留安全空间。
 * 不记录正文或密钥，供应商真实 usage 仍是计费依据。
 */
public final class AgentPromptBudget {
    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);
    private final AgentContextProperties properties;

    public AgentPromptBudget(AgentContextProperties properties) {
        properties.validate();
        this.properties = properties;
    }

    /** 容量取模型配置与业务预算的较小者；小窗口同时缩减输出预留，避免负输入预算。 */
    public Limits limits(String model, UserAiFundingSource source) {
        int capacity = properties.getModelWindows().getOrDefault(model,
                source == UserAiFundingSource.PLATFORM ? properties.getPlatformWindowTokens()
                        : properties.getUnknownModelWindowTokens());
        int window = Math.min(capacity, properties.getWorkingWindowTokens());
        int output = Math.min(properties.getMaxOutputTokens(), window / 4);
        int margin = Math.min(properties.getSafetyMarginTokens(), window / 8);
        return new Limits(window - output - margin, output);
    }

    public int estimate(List<AiPromptMessage> messages) {
        long tokens = 3;
        for (AiPromptMessage message : messages) {
            tokens += ENCODING.countTokensOrdinary(message.text()) + 8L;
        }
        return (int) Math.min(Integer.MAX_VALUE, (tokens * 115 + 99) / 100);
    }

    public int historyPageTurns() { return properties.getHistoryPageTurns(); }
    public java.time.Duration historyLoadTimeout() { return properties.getHistoryLoadTimeout(); }

    /** 输入与输出预算必须来自同一模型快照，输出值随后真正写入网关请求。 */
    public record Limits(int inputTokens, int outputTokens) { }
}
