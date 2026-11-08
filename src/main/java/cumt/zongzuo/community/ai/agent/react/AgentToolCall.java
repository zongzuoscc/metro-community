package cumt.zongzuo.community.ai.agent.react;

import com.fasterxml.jackson.databind.JsonNode;
import cumt.zongzuo.community.ai.agent.planner.AgentReadOnlyTool;
import java.util.Objects;
import java.util.regex.Pattern;

/** 服务端白名单工具参数；身份和运行权始终由调用栈绑定，模型不能提供。 */
public record AgentToolCall(AgentReadOnlyTool tool, String query, JsonNode arguments) {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public AgentToolCall(AgentReadOnlyTool tool, String query) { this(tool, query, null); }

    public AgentToolCall {
        Objects.requireNonNull(tool, "tool");
        if (arguments == null) {
            Objects.requireNonNull(query, "query");
            query = WHITESPACE.matcher(query.strip()).replaceAll(" ");
            if (query.isBlank() || query.codePointCount(0, query.length()) > 2_000)
                throw new IllegalArgumentException("Agent tool query must contain 1 to 2000 code points");
        } else {
            if (!arguments.isObject()) throw new IllegalArgumentException("Object arguments required");
            arguments = arguments.deepCopy();
            query = "";
        }
    }
}
