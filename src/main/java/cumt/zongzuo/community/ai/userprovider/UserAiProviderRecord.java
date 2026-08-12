package cumt.zongzuo.community.ai.userprovider;

/**
 * 用户 AI 配置的数据库记录。
 *
 * <p>该对象只在服务端内部流转，绝不能作为 Controller 返回值。encryptedApiKey 即使已经
 * 加密也属于敏感字段，HTTP 层只能返回独立的脱敏 View。</p>
 */
public final class UserAiProviderRecord {

    private long userId;
    private String provider;
    private String baseUrl;
    private String model;
    private String encryptedApiKey;
    private String keyHint;
    private boolean enabled;

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getEncryptedApiKey() { return encryptedApiKey; }
    public void setEncryptedApiKey(String encryptedApiKey) { this.encryptedApiKey = encryptedApiKey; }
    public String getKeyHint() { return keyHint; }
    public void setKeyHint(String keyHint) { this.keyHint = keyHint; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
