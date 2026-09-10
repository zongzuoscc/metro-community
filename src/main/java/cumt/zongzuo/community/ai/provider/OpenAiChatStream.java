package cumt.zongzuo.community.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 平台与 BYOK 共用的真实 SSE 读取器。客户端由调用方提供，保留其 DNS 固定、TLS、
 * 代理及重定向策略。读到完整 SSE 事件就回调，不等待整个 HTTP body 完成。
 */
public final class OpenAiChatStream {
    private static final int MAX_RESPONSE = 131072;
    private OpenAiChatStream() { }

    public static AiChatResult read(OkHttpClient client, URI uri, Map<String, String> headers,
                                    String body, String provider, String model,
                                    AiStreamObserver observer) throws Exception {
        observer.checkActive();
        var builder = new Request.Builder().url(uri.toString())
                .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .header("Accept", "text/event-stream");
        headers.forEach(builder::header);
        Call call = client.newCall(builder.build());
        Thread worker = Thread.currentThread();
        var stopped = new AtomicReference<RuntimeException>();
        // 数量受现有能力并发上限约束；每次请求关闭自己的监视器，避免连接结束后泄漏线程。
        var watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ai-stream-cancel");
            thread.setDaemon(true);
            return thread;
        });
        var monitor = watchdog.scheduleWithFixedDelay(() -> {
            try {
                if (worker.isInterrupted()) throw new CancellationException("Stream interrupted");
                observer.checkActive();
            } catch (RuntimeException error) {
                stopped.compareAndSet(null, error);
                call.cancel();
            }
        }, 250, 250, TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw AiProviderException.fromHttpStatus(new ProviderHttpStatusException(response.code()));
            if (response.body() == null || !response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                throw invalid("Provider did not return an SSE stream");
            }
            var state = new Accumulator(provider, model, observer);
            var source = response.body().source();
            var data = new StringBuilder();
            while (!source.exhausted()) {
                observer.checkActive();
                String line = source.readUtf8LineStrict(MAX_RESPONSE);
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        if (state.event(data.toString())) return state.result();
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    String value = line.substring(5);
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                    if (data.length() > MAX_RESPONSE) throw invalid("SSE event too large");
                }
            }
            // 断开的响应不能伪装成生成成功，必须收到协议终止标记和模型完成原因。
            throw invalid("Provider stream ended without DONE");
        } catch (Exception error) {
            if (stopped.get() != null) throw stopped.get();
            throw error;
        } finally {
            monitor.cancel(true);
            watchdog.shutdownNow();
            call.cancel();
        }
    }

    /** 按事件增量解析；总文本有硬上限，空 delta 与 usage-only 事件不会产生假正文。 */
    private static final class Accumulator {
        private final ObjectMapper mapper = new ObjectMapper();
        private final String provider, model;
        private final AiStreamObserver observer;
        private final StringBuilder text = new StringBuilder();
        private String finish;
        private long input, output;
        Accumulator(String provider, String model, AiStreamObserver observer) {
            this.provider = provider; this.model = model; this.observer = observer;
        }
        boolean event(String data) throws Exception {
            if ("[DONE]".equals(data)) return true;
            JsonNode root = mapper.readTree(data);
            if (root == null || !root.isObject() || root.has("error")) throw invalid("Invalid provider SSE event");
            if (root.hasNonNull("model") && !model.equals(root.path("model").asText())) throw invalid("Stream model mismatch");
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                input = usage.path("prompt_tokens").asLong(0);
                output = usage.path("completion_tokens").asLong(0);
                if (input < 0 || output < 0) throw invalid("Invalid stream usage");
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() > 1) throw invalid("Invalid stream choices");
            if (choices.isEmpty()) return false;
            JsonNode choice = choices.get(0), delta = choice.path("delta");
            if (choice.path("index").asInt(0) != 0 || delta.hasNonNull("tool_calls") || delta.hasNonNull("function_call"))
                throw invalid("Unexpected tool call in final answer");
            JsonNode content = delta.get("content");
            if (content != null && !content.isNull()) {
                if (!content.isTextual() || finish != null) throw invalid("Invalid content delta");
                String piece = content.textValue();
                if (text.length() + piece.length() > MAX_RESPONSE) throw invalid("Stream response too large");
                text.append(piece);
                if (!piece.isEmpty()) { observer.checkActive(); observer.onDelta(piece); }
            }
            if (choice.hasNonNull("finish_reason")) finish = choice.path("finish_reason").asText();
            return false;
        }
        AiChatResult result() {
            observer.checkActive();
            if (!"stop".equals(finish) || text.isEmpty()) throw invalid("Stream did not complete successfully");
            return new AiChatResult(text.toString(), finish, input, output, provider, model);
        }
    }

    private static AiProviderException invalid(String message) {
        return new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE, message);
    }
}
