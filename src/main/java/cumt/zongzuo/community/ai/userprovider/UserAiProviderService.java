package cumt.zongzuo.community.ai.userprovider;

import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 管理用户自有 AI 凭据，并保证加密、端点校验和所有权过滤集中在同一边界。
 */
public class UserAiProviderService {

    private final UserAiProviderMapper mapper;
    private final UserAiCredentialCipher cipher;
    private final AiProviderEndpointPolicy endpoints;

    public UserAiProviderService(UserAiProviderMapper mapper, UserAiCredentialCipher cipher,
                                 AiProviderEndpointPolicy endpoints) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.endpoints = endpoints;
    }

    public UserAiProviderView find(long userId) {
        UserAiProviderRecord record = mapper.findByUserId(userId);
        return record == null ? UserAiProviderView.platformDefault() : view(record);
    }

    /** 仅向模型路由器暴露已启用记录，关闭配置后会立即退回平台基础额度。 */
    public Optional<UserAiProviderRecord> findEnabledRecord(long userId) {
        UserAiProviderRecord record = mapper.findByUserId(userId);
        return record == null || !record.isEnabled() ? Optional.empty() : Optional.of(record);
    }

    @Transactional
    public UserAiProviderView save(long userId, UserAiProviderSaveRequest request) {
        UserAiProviderType provider = UserAiProviderType.parse(request.provider());
        String endpoint = provider == UserAiProviderType.CUSTOM
                ? endpoints.validateAndNormalize(request.baseUrl()) : provider.fixedBaseUrl();
        // 预设地址也走校验，防止未来维护时误把某个预设改成内网地址。
        endpoint = endpoints.validateAndNormalize(endpoint);
        String model = required(request.model(), "模型名称不能为空", 128);
        UserAiProviderRecord existing = mapper.findByUserId(userId);
        String suppliedKey = request.apiKey() == null ? "" : request.apiKey().strip();
        String encrypted;
        String hint;
        if (suppliedKey.isEmpty()) {
            if (existing == null || existing.getEncryptedApiKey() == null) {
                throw new IllegalArgumentException("首次配置必须填写 API Key");
            }
            encrypted = existing.getEncryptedApiKey();
            hint = existing.getKeyHint();
        }
        else {
            encrypted = cipher.encrypt(suppliedKey);
            hint = mask(suppliedKey);
        }
        UserAiProviderRecord record = new UserAiProviderRecord();
        record.setUserId(userId);
        record.setProvider(provider.name());
        record.setBaseUrl(endpoint);
        record.setModel(model);
        record.setEncryptedApiKey(encrypted);
        record.setKeyHint(hint);
        record.setEnabled(request.enabled());
        mapper.upsert(record);
        return view(record);
    }

    @Transactional
    public void setEnabled(long userId, boolean enabled) {
        if (mapper.setEnabled(userId, enabled) != 1) {
            throw new IllegalArgumentException("尚未配置用户 AI API");
        }
    }

    @Transactional
    public void delete(long userId) {
        mapper.deleteByUserId(userId);
    }

    /** 解密只发生在真正发送请求之前，调用者不得缓存返回的明文。 */
    public String decryptApiKey(UserAiProviderRecord record) {
        return cipher.decrypt(record.getEncryptedApiKey());
    }

    private static UserAiProviderView view(UserAiProviderRecord record) {
        return new UserAiProviderView(true, record.getProvider(), record.getBaseUrl(),
                record.getModel(), record.getKeyHint(), record.isEnabled(),
                record.isEnabled() ? UserAiFundingSource.USER : UserAiFundingSource.PLATFORM);
    }

    private static String required(String value, String message, int maximum) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static String mask(String apiKey) {
        int count = apiKey.codePointCount(0, apiKey.length());
        int start = apiKey.offsetByCodePoints(0, Math.max(0, count - 4));
        return "••••" + apiKey.substring(start);
    }
}
