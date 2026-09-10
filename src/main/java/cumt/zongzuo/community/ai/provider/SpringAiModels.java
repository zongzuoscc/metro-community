package cumt.zongzuo.community.ai.provider;

import com.fasterxml.jackson.databind.MapperFeature;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** 业务门面与标准模型转换；完整工具消息始终使用 Prompt/ChatResponse。 */
public final class SpringAiModels {
    private static final int MAX_STREAM_RESPONSE_CHARACTERS = 131072;
    // Reactor 3.7.7 的 concatMap 在 monitor 内同步转发 onNext；业务回调会查库/写 Redis，
    // 必须在完整官方模型 Flux 之后切出该调用栈。共享虚拟调度器不增加隐藏 CPU×10 上限，
    // publishOn 的 prefetch=1 仅允许一个待处理响应；取消仍沿订阅链关闭真实 HTTP Call。
    private static final Scheduler OBSERVERS =
            Schedulers.fromExecutorService(
                    Executors.newThreadPerTaskExecutor(
                            Thread.ofVirtual().name("ai-model-observer-", 0).factory()));

    private SpringAiModels() {}

    public static OpenAiApi api(
            String baseUrl,
            String key,
            ClientHttpRequestFactory requests,
            ClientHttpConnector connector) {
        var jsonMapper =
                Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        .build();
        // 标准解码器默认把字符串数字强转为向量；关闭该隐式转换，保持原有向量索引输入边界。
        var rest =
                RestClient.builder()
                        .requestFactory(requests)
                        .messageConverters(
                                converters ->
                                        converters.replaceAll(
                                                converter ->
                                                        converter
                                                                        instanceof
                                                                        MappingJackson2HttpMessageConverter
                                                                ? new MappingJackson2HttpMessageConverter(
                                                                        jsonMapper)
                                                                : converter));
        var web =
                WebClient.builder()
                        .defaultStatusHandler(
                                status -> !status.is2xxSuccessful(),
                                response ->
                                        Mono.just(
                                                AiProviderException.fromHttpStatus(
                                                        new ProviderHttpStatusException(
                                                                response.statusCode().value()))));
        if (connector != null) web.clientConnector(connector);
        else
            web.clientConnector(
                    (method, uri, callback) ->
                            Mono.error(
                                    new UnsupportedOperationException(
                                            "Streaming transport unavailable")));
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .completionsPath("/chat/completions")
                .embeddingsPath("/embeddings")
                .restClientBuilder(rest)
                .webClientBuilder(web)
                .responseErrorHandler(
                        new ResponseErrorHandler() {
                            public boolean hasError(ClientHttpResponse response)
                                    throws IOException {
                                return !response.getStatusCode().is2xxSuccessful();
                            }

                            public void handleError(
                                    java.net.URI uri,
                                    org.springframework.http.HttpMethod method,
                                    ClientHttpResponse response)
                                    throws IOException {
                                throw AiProviderException.fromHttpStatus(
                                        new ProviderHttpStatusException(
                                                response.getStatusCode().value()));
                            }
                        })
                .build();
    }

    public static OpenAiChatModel chat(OpenAiApi api, String model) {
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(
                        OpenAiChatOptions.builder()
                                .model(model)
                                .temperature(null)
                                .internalToolExecutionEnabled(false)
                                .build())
                .build();
    }

    public static Prompt prompt(AiChatCommand command, int moderationLimit) {
        List<Message> messages =
                command.messages().stream()
                        .map(
                                value ->
                                        (Message)
                                                switch (value.role()) {
                                                    case SYSTEM -> new SystemMessage(value.text());
                                                    case ASSISTANT ->
                                                            new AssistantMessage(value.text());
                                                    case USER -> new UserMessage(value.text());
                                                })
                        .toList();
        var options = OpenAiChatOptions.builder().internalToolExecutionEnabled(false);
        if (command.responseMode() == AiResponseMode.JSON_OBJECT)
            options.responseFormat(
                    new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, (String) null));
        Integer max = command.maxOutputTokens();
        if (command.capability() == AiCapability.MODERATION && moderationLimit > 0) {
            options.temperature(0.0);
            max = max == null ? moderationLimit : Math.min(max, moderationLimit);
        }
        if (max != null) options.maxTokens(max);
        return new Prompt(messages, options.build());
    }

    public static AiChatResult result(
            ChatResponse response, String provider, String model, boolean strictModel) {
        validate(response, model, strictModel);
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank())
            throw new AiProviderException(
                    AiProviderErrorReason.EMPTY_RESPONSE, "AI provider returned no chat result");
        return new AiChatResult(
                text,
                response.getResult().getMetadata().getFinishReason().toLowerCase(Locale.ROOT),
                response.getMetadata().getUsage().getPromptTokens(),
                response.getMetadata().getUsage().getCompletionTokens(),
                provider,
                model);
    }

    public static ChatResponse validate(ChatResponse response, String model, boolean strictModel) {
        if (response == null || response.getResult() == null)
            throw new AiProviderException(
                    AiProviderErrorReason.EMPTY_RESPONSE, "AI provider returned no chat result");
        var usage = response.getMetadata().getUsage();
        String finish = response.getResult().getMetadata().getFinishReason();
        if (finish == null
                || finish.isBlank()
                || usage.getPromptTokens() < 0
                || usage.getCompletionTokens() < 0
                || strictModel && !model.equals(response.getMetadata().getModel()))
            throw new AiProviderException(
                    AiProviderErrorReason.MALFORMED_RESPONSE,
                    "AI provider returned an incompatible response");
        return response;
    }

    public static AiChatResult stream(
            Flux<ChatResponse> stream,
            String provider,
            String model,
            boolean strictModel,
            AiStreamObserver observer) {
        StringBuilder text = new StringBuilder();
        AtomicReference<ChatResponse> last = new AtomicReference<>();
        AtomicReference<Usage> usage = new AtomicReference<>(new EmptyUsage());
        CompletableFuture<Void> finished = new CompletableFuture<>();
        var subscription =
                stream.publishOn(OBSERVERS, 1)
                        .doOnNext(
                                response -> {
                                    observer.checkActive();
                                    if (strictModel
                                            && response.getMetadata().getModel() != null
                                            && !response.getMetadata().getModel().isBlank()
                                            && !model.equals(response.getMetadata().getModel()))
                                        throw new AiProviderException(
                                                AiProviderErrorReason.MALFORMED_RESPONSE,
                                                "AI provider model mismatch");
                                    if (response.getMetadata().getUsage().getTotalTokens() != 0)
                                        usage.set(response.getMetadata().getUsage());
                                    if (response.getResult() != null) {
                                        String delta = response.getResult().getOutput().getText();
                                        if (delta != null && !delta.isEmpty()) {
                                            // 限制完整模型响应，而不只是 answer 字段；空白/引用同样计入。
                                            // 在追加与通知前抛错，由 Reactor 立即取消上游，避免快速源继续生成。
                                            if (delta.length()
                                                    > MAX_STREAM_RESPONSE_CHARACTERS
                                                            - text.length())
                                                throw new AiProviderException(
                                                        AiProviderErrorReason.MALFORMED_RESPONSE,
                                                        "AI stream response too large");
                                            text.append(delta);
                                            observer.onDelta(delta);
                                        }
                                        last.set(response);
                                    }
                                })
                        .subscribe(
                                ignored -> {},
                                finished::completeExceptionally,
                                () -> finished.complete(null));
        try {
            while (true) {
                observer.checkActive();
                try {
                    finished.get(100, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException waiting) {
                    /* 无数据时仍检查取消。 */
                }
            }
            ChatResponse response = last.get();
            // 已逐帧拒绝明确的模型错配；兼容原契约允许上游省略 model 元数据。
            validate(response, model, false);
            if (usage.get().getPromptTokens() < 0 || usage.get().getCompletionTokens() < 0)
                throw new AiProviderException(
                        AiProviderErrorReason.MALFORMED_RESPONSE, "Negative usage");
            if (text.isEmpty())
                throw new AiProviderException(
                        AiProviderErrorReason.EMPTY_RESPONSE,
                        "AI provider returned no chat result");
            return new AiChatResult(
                    text.toString(),
                    response.getResult().getMetadata().getFinishReason().toLowerCase(Locale.ROOT),
                    usage.get().getPromptTokens(),
                    usage.get().getCompletionTokens(),
                    provider,
                    model);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("AI stream interrupted");
        } catch (ExecutionException error) {
            throw failure(error.getCause());
        } finally {
            subscription.dispose();
        }
    }

    public static Prompt streamingPrompt(AiChatCommand command, int moderationLimit) {
        Prompt prompt = prompt(command, moderationLimit);
        OpenAiChatOptions options =
                OpenAiChatOptions.fromOptions((OpenAiChatOptions) prompt.getOptions());
        options.setStreamUsage(true);
        return new Prompt(prompt.getInstructions(), options);
    }

    public static RuntimeException failure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AiProviderException provider) return provider;
            if (cause instanceof CancellationException cancelled) return cancelled;
            if (cause
                    instanceof
                    org.springframework.web.reactive.function.client.WebClientResponseException
                                    http)
                return AiProviderException.fromHttpStatus(
                        new ProviderHttpStatusException(http.getStatusCode().value()));
        }
        return AiProviderException.fromTransport(error);
    }
}
