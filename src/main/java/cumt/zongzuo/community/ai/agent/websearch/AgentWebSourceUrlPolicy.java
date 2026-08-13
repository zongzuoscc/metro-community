package cumt.zongzuo.community.ai.agent.websearch;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Agent 联网来源的统一 URL 安全边界。
 *
 * <p>搜索供应商响应、回答持久化和历史恢复都必须调用同一规则，只允许带主机名、
 * 不含用户信息的 HTTP/HTTPS 绝对地址。这样旧数据或测试写入也不能绕过浏览器链接边界。</p>
 */
public final class AgentWebSourceUrlPolicy {

    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https");

    private AgentWebSourceUrlPolicy() {
    }

    public static boolean isSafe(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            return uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getScheme() != null
                    && SAFE_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
