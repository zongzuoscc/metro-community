package cumt.zongzuo.community.ai.userprovider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 保存或替换用户模型配置的请求；空 apiKey 只表示沿用已保存密钥。 */
public record UserAiProviderSaveRequest(
        @NotBlank @Size(max = 24) String provider,
        @Size(max = 512) String baseUrl,
        @NotBlank @Size(max = 128) String model,
        @Size(max = 4096) String apiKey,
        boolean enabled) {
}
