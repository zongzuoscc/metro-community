package cumt.zongzuo.community.ai.userprovider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 用户自带模型的部署级安全配置，主密钥只允许由环境变量注入。 */
@ConfigurationProperties(prefix = "metro.ai.user-provider")
public class UserAiProviderProperties {

    private boolean enabled;
    private String credentialMasterKey = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(60);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCredentialMasterKey() { return credentialMasterKey; }
    public void setCredentialMasterKey(String credentialMasterKey) {
        this.credentialMasterKey = credentialMasterKey;
    }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }

    /** 只有显式开启时才要求密钥，未开启的旧部署继续使用平台模型并可正常启动。 */
    public void validate() {
        if (!enabled) return;
        if (credentialMasterKey == null || credentialMasterKey.isBlank()) {
            throw new IllegalStateException("METRO_AI_USER_CREDENTIAL_MASTER_KEY is required");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(connectTimeout) <= 0) {
            throw new IllegalStateException("User AI provider timeouts are invalid");
        }
    }
}
