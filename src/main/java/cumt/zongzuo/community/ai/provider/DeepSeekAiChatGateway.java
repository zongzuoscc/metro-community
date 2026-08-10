package cumt.zongzuo.community.ai.provider;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DeepSeekAiChatGateway implements AiChatGateway {

    private static final String PROVIDER = "deepseek";

    private final Map<AiCapability, DeepSeekChatModel> chatModels;
    private final String model;
    private final int moderationMaxOutputTokens;

    public DeepSeekAiChatGateway(Map<AiCapability, DeepSeekChatModel> chatModels, String model,
                                 int moderationMaxOutputTokens) {
        Objects.requireNonNull(chatModels, "chatModels must not be null");
        EnumMap<AiCapability, DeepSeekChatModel> models = new EnumMap<>(AiCapability.class);
        models.putAll(chatModels);
        this.chatModels = Collections.unmodifiableMap(models);
        this.model = Objects.requireNonNull(model, "model must not be null");
        if (moderationMaxOutputTokens <= 0) {
            throw new IllegalArgumentException("moderationMaxOutputTokens must be positive");
        }
        this.moderationMaxOutputTokens = moderationMaxOutputTokens;
    }

    @Override
    public AiChatResult generate(AiChatCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        DeepSeekChatModel chatModel = chatModels.get(command.capability());
        if (chatModel == null) {
            throw new AiProviderException(AiProviderErrorReason.AI_DISABLED,
                    "AI capability is disabled");
        }
        Prompt prompt = new Prompt(command.messages().stream().map(this::toSpringMessage).toList(),
                requestOptions(command));

        try {
            return toResult(chatModel.call(prompt));
        }
        catch (ProviderHttpStatusException error) {
            throw AiProviderException.fromHttpStatus(error);
        }
        catch (ResourceAccessException error) {
            throw AiProviderException.fromTransport(error);
        }
        catch (RestClientException error) {
            throw new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE,
                    "AI provider returned a malformed response", error);
        }
        catch (AiProviderException error) {
            throw error;
        }
        catch (RuntimeException error) {
            throw new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE,
                    "AI provider returned a malformed response", error);
        }
    }

    private DeepSeekChatOptions requestOptions(AiChatCommand command) {
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder().model(model);
        if (command.responseMode() == AiResponseMode.JSON_OBJECT) {
            builder.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        }
        if (command.capability() == AiCapability.MODERATION) {
            builder.temperature(0.0).maxTokens(moderationMaxOutputTokens);
        }
        return builder.build();
    }

    private Message toSpringMessage(AiPromptMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.text());
            case USER -> new UserMessage(message.text());
            case ASSISTANT -> new AssistantMessage(message.text());
        };
    }

    private AiChatResult toResult(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            throw emptyResponse();
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            throw emptyResponse();
        }
        String text = generation.getOutput().getText();
        if (text == null || text.isBlank()) {
            throw emptyResponse();
        }
        String finishReason = normalizeFinishReason(generation.getMetadata().getFinishReason());
        if (finishReason.isEmpty()) {
            throw new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE,
                    "AI provider finish reason was missing");
        }
        Usage usage = response.getMetadata().getUsage();
        long inputTokens = tokenCount(usage == null ? null : usage.getPromptTokens());
        long outputTokens = tokenCount(usage == null ? null : usage.getCompletionTokens());
        return new AiChatResult(text, finishReason, inputTokens, outputTokens, PROVIDER, model);
    }

    private static String normalizeFinishReason(String finishReason) {
        return finishReason == null ? "" : finishReason.trim().toLowerCase(Locale.ROOT);
    }

    private static long tokenCount(Integer count) {
        if (count == null) {
            return 0;
        }
        if (count < 0) {
            throw new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE,
                    "AI provider returned a negative token count");
        }
        return count.longValue();
    }

    private static AiProviderException emptyResponse() {
        return new AiProviderException(AiProviderErrorReason.EMPTY_RESPONSE,
                "AI provider returned no chat result");
    }
}
