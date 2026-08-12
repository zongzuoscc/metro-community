package cumt.zongzuo.community.ai.userprovider;

/**
 * 可以安全返回给浏览器的用户模型配置。
 *
 * <p>该类型故意没有 apiKey 或 encryptedApiKey 字段，序列化路径无法误把密钥返回前端。</p>
 */
public record UserAiProviderView(boolean configured, String provider, String baseUrl,
                                 String model, String keyHint, boolean enabled,
                                 UserAiFundingSource fundingSource) {

    public static UserAiProviderView platformDefault() {
        return new UserAiProviderView(false, null, null, null, null, false,
                UserAiFundingSource.PLATFORM);
    }
}
