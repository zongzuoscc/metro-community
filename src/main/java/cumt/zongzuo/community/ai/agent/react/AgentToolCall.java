package cumt.zongzuo.community.ai.agent.react;

import cumt.zongzuo.community.ai.agent.planner.AgentReadOnlyTool;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 模型一次只能选择一个后端白名单工具，并仅提供检索文字。
 *
 * <p>查询在边界上统一去掉首尾空白；按 Unicode code point 限制长度，
 * 避免表情等补充字符被 UTF-16 错误按两字计数。此协议不接收 URL、SQL
 * 或用户身份等可扩大权限的独立参数。</p>
 */
public record AgentToolCall(AgentReadOnlyTool tool, String query) {
    private static final int MAX_QUERY_CODE_POINTS = 2_000;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+",
            Pattern.UNICODE_CHARACTER_CLASS);

    public AgentToolCall {
        Objects.requireNonNull(tool, "tool must not be null");
        Objects.requireNonNull(query, "query must not be null");
        query = WHITESPACE.matcher(query.strip()).replaceAll(" ");
        if (query.isBlank() || query.codePointCount(0, query.length()) > MAX_QUERY_CODE_POINTS) {
            throw new IllegalArgumentException("Agent tool query must contain 1 to 2000 code points");
        }
    }
}
