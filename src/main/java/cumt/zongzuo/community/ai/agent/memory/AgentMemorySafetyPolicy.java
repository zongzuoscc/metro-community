package cumt.zongzuo.community.ai.agent.memory;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 默认拒绝的记忆安全策略，防止密码、令牌、身份号、金融数据和健康数据被自动捕获。
 * 这一层在分类与落库之前执行，即使后续模型或规则变更也不应绕过。
 */
@Component
public class AgentMemorySafetyPolicy {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(password|passwd|secret|token|api[-_ ]?key|credit card|bank card|id card|"
                    + "\u5bc6\u7801|\u53e3\u4ee4|\u4ee4\u724c|\u5bc6\u94a5|\u8eab\u4efd\u8bc1|\u94f6\u884c\u5361|\u4fe1\u7528\u5361|\u62a4\u7167|\u75c5\u53f2|\u60a3\u6709|\u8bca\u65ad|\u7cd6\u5c3f\u75c5|\u6291\u90c1\u75c7)");

    // 聊天上下文不是自动提炼的画像：允许讨论 Token/API Key 等技术名词，但仍拦截
    // 明确赋值的凭据及常见密钥格式。该启发式不是完整 DLP，不能宣称识别所有秘密。
    private static final Pattern CONTEXT_SENSITIVE = Pattern.compile(
            "(?i)(\\b(?:sk-|ghp_|github_pat_)[a-z0-9_-]{8,}|bearer\\s+[a-z0-9._-]{8,}|"
                    + "(?:password|passwd|secret|token|api[-_ ]?key|密码|口令|令牌|密钥)"
                    + "[\\s\"']*(?::|=|是|为)[\\s\"']*[^\\s\"']{4,}|"
                    + "身份证|银行卡|信用卡|护照|病史|患有|诊断|糖尿病|抑郁症)");

    /** 仅接受非空、不超过 1000 个 Unicode 码点且不命中敏感模式的低风险文本。 */
    public boolean canStore(String input) {
        return input != null && !input.isBlank()
                && input.codePointCount(0, input.length()) <= 1000
                && !SENSITIVE.matcher(input).find();
    }

    /** 历史原文长度由统一 Token 预算决定，不套用画像条目的 1000 字限制。 */
    public boolean canUseAsContext(String input) {
        return input != null && !input.isBlank() && !CONTEXT_SENSITIVE.matcher(input).find();
    }
}
