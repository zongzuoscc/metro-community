package cumt.zongzuo.community.ai.userprovider;

import cumt.zongzuo.community.ai.provider.AiCapability;
import cumt.zongzuo.community.ai.provider.AiChatCommand;
import cumt.zongzuo.community.ai.provider.AiPromptMessage;
import cumt.zongzuo.community.ai.provider.AiPromptRole;
import cumt.zongzuo.community.ai.provider.AiResponseMode;
import cumt.zongzuo.community.ai.web.AiApi;
import cumt.zongzuo.community.ai.web.AiApiException;
import cumt.zongzuo.community.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户自己的模型设置入口，所有操作只作用于当前 JWT 用户。 */
@AiApi
@RestController
@RequestMapping("/api/agent/provider-settings")
public class UserAiProviderController {

    private final UserAiProviderService settings;
    private final UserOpenAiCompatibleGateway gateway;
    private final UserAiProviderProperties properties;

    public UserAiProviderController(UserAiProviderService settings,
                                    UserOpenAiCompatibleGateway gateway,
                                    UserAiProviderProperties properties) {
        this.settings = settings;
        this.gateway = gateway;
        this.properties = properties;
    }

    @GetMapping
    public UserAiProviderView find() {
        return settings.find(CurrentUser.id());
    }

    @PutMapping
    public UserAiProviderView save(@Valid @RequestBody UserAiProviderSaveRequest request) {
        requireEnabled();
        try {
            return settings.save(CurrentUser.id(), request);
        }
        catch (IllegalArgumentException invalid) {
            throw AiApiException.validationFailed();
        }
    }

    @PatchMapping("/enabled")
    public UserAiProviderView setEnabled(@RequestBody ProviderEnabledRequest request) {
        requireEnabled();
        try {
            settings.setEnabled(CurrentUser.id(), request.enabled());
            return settings.find(CurrentUser.id());
        }
        catch (IllegalArgumentException invalid) {
            throw AiApiException.resourceNotFound();
        }
    }

    /** 测试只使用用户凭据，绝不悄悄回退平台，以免把错误配置显示为成功。 */
    @PostMapping("/test")
    public ProviderConnectionTestResponse testConnection() {
        requireEnabled();
        UserAiProviderRecord record = settings.findEnabledRecord(CurrentUser.id())
                .orElseThrow(AiApiException::resourceNotFound);
        var result = gateway.generate(record, settings.decryptApiKey(record),
                new AiChatCommand(AiCapability.WRITING,
                        List.of(new AiPromptMessage(AiPromptRole.USER,
                                "请只回复 OK，用于验证模型连接。")), AiResponseMode.TEXT));
        return new ProviderConnectionTestResponse(true, result.provider(), result.model());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete() {
        requireEnabled();
        settings.delete(CurrentUser.id());
    }

    /** 部署未开放 BYOK 时，所有写操作使用统一的稳定错误，不暴露内部装配细节。 */
    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw AiApiException.disabled();
        }
    }

    public record ProviderEnabledRequest(boolean enabled) { }
    public record ProviderConnectionTestResponse(boolean connected, String provider, String model) { }
}
