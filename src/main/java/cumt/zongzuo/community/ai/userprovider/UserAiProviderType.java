package cumt.zongzuo.community.ai.userprovider;

import java.util.Locale;

/**
 * 用户可以选择的模型供应商。
 *
 * <p>预设供应商固定服务端地址，防止前端伪造地址；只有 CUSTOM 才接受用户填写的
 * OpenAI 兼容 HTTPS 地址，并且仍需经过统一 SSRF 校验。</p>
 */
public enum UserAiProviderType {
    OPENAI("https://api.openai.com/v1", "gpt-4.1-mini"),
    DEEPSEEK("https://api.deepseek.com/v1", "deepseek-chat"),
    QWEN("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    CUSTOM(null, null);

    private final String fixedBaseUrl;
    private final String defaultModel;

    UserAiProviderType(String fixedBaseUrl, String defaultModel) {
        this.fixedBaseUrl = fixedBaseUrl;
        this.defaultModel = defaultModel;
    }

    public String fixedBaseUrl() {
        return fixedBaseUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }

    /** 大小写不敏感地解析接口值，拒绝未知供应商，避免静默回退到平台额度。 */
    public static UserAiProviderType parse(String value) {
        try {
            return valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("不支持的 AI 供应商", invalid);
        }
    }
}
