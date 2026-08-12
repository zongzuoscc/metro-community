package cumt.zongzuo.community.ai.userprovider;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 验证用户自定义 OpenAI 兼容端点的 SSRF 边界。
 *
 * <p>端点必须是公网 HTTPS，保存时与每次调用前都要执行同一校验。
 * 这样 DNS 在保存后被重绑到内网时，真正发请求之前仍会被拒绝。</p>
 */
public final class AiProviderEndpointPolicy {

    /**
     * 一次安全解析的不可变结果。
     *
     * <p>调用方必须同时使用规范化 URL 和这里已经检查过的地址，不能再按域名重新做 DNS，
     * 否则攻击者可以在“校验”和“连接”之间切换解析结果。</p>
     */
    public record ValidatedEndpoint(String normalizedBaseUrl, List<InetAddress> approvedAddresses) {
        public ValidatedEndpoint {
            approvedAddresses = List.copyOf(approvedAddresses);
        }
    }

    @FunctionalInterface
    public interface HostResolver {
        List<InetAddress> resolve(String host) throws Exception;
    }

    private final HostResolver resolver;

    public AiProviderEndpointPolicy() {
        this(host -> List.of(InetAddress.getAllByName(host)));
    }

    AiProviderEndpointPolicy(HostResolver resolver) {
        this.resolver = resolver;
    }

    public String validateAndNormalize(String value) {
        return validateAndResolve(value).normalizedBaseUrl();
    }

    /** 校验端点并返回本次网络连接必须使用的公网地址集合。 */
    public ValidatedEndpoint validateAndResolve(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.strip());
        }
        catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("AI provider endpoint is invalid", malformed);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getHost().isBlank() || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getQuery() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException(
                    "AI provider endpoint must be public HTTPS without credentials or query parameters");
        }
        if (isForbiddenHostName(uri.getHost())) {
            throw new IllegalArgumentException("AI provider endpoint must not target a local host");
        }
        // URI 中直接出现 IP 时不依赖 DNS resolver，否则测试替身或定制解析器可以绕过直连内网地址检查。
        InetAddress literal = parseLiteralAddress(uri.getHost());
        if (literal != null && isForbiddenAddress(literal)) {
            throw new IllegalArgumentException("AI provider endpoint must not target a non-public address");
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        }
        catch (Exception resolutionFailure) {
            throw new IllegalArgumentException("AI provider endpoint cannot be resolved", resolutionFailure);
        }
        if (addresses.isEmpty() || addresses.stream().anyMatch(AiProviderEndpointPolicy::isForbiddenAddress)) {
            throw new IllegalArgumentException("AI provider endpoint resolved to a non-public address");
        }
        String normalized = uri.normalize().toString();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return new ValidatedEndpoint(normalized, addresses);
    }

    private static boolean isForbiddenHostName(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal");
    }

    private static boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            return first == 0 || first == 10 || first == 127 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19));
        }
        // Java 已处理 IPv6 loopback/link-local/site-local；额外拦截唯一本地地址 :: 和 ULA fc00::/7。
        return raw.length == 16 && ((raw[0] & 0xfe) == 0xfc);
    }

    private static InetAddress parseLiteralAddress(String host) {
        if (!host.matches("[0-9.]+") && !host.contains(":")) return null;
        try {
            return InetAddress.getByName(host);
        }
        catch (Exception invalidLiteral) {
            throw new IllegalArgumentException("AI provider endpoint contains an invalid IP address",
                    invalidLiteral);
        }
    }
}
