package cumt.zongzuo.community.ai.provider;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.reactive.AbstractClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** 仅桥接 HTTP 字节，不编码模型协议，不解析 JSON 或 SSE；固定 DNS 由传入的客户端负责。 */
public final class SpringAiHttpTransport implements ClientHttpConnector {
    public record Reply(int status, String body) {}

    @FunctionalInterface
    public interface Exchange {
        Reply post(URI uri, Map<String, String> headers, String body) throws Exception;
    }

    private static final ScheduledExecutorService INTERRUPTS =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread thread = new Thread(r, "ai-http-interrupt");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Function<URI, OkHttpClient> clients;
    private static final Dispatcher DISPATCHER = dispatcher();
    // JDK 21 虚拟线程承载阻塞 socket 读取；共用调度器，不为每轮创建线程池，准入仍由业务执行槽控制。
    private static final Scheduler READERS =
            Schedulers.fromExecutorService(
                    Executors.newThreadPerTaskExecutor(
                            Thread.ofVirtual().name("ai-http-reader-", 0).factory()));

    private static Dispatcher dispatcher() {
        // 并发准入由业务 bulkhead 负责，不能再受 OkHttp 默认每域名 5 个请求的隐式队列影响。
        Dispatcher result = new Dispatcher();
        result.setMaxRequests(Integer.MAX_VALUE);
        result.setMaxRequestsPerHost(Integer.MAX_VALUE);
        return result;
    }

    public static OkHttpClient.Builder clientBuilder() {
        return new OkHttpClient.Builder().dispatcher(DISPATCHER);
    }

    public SpringAiHttpTransport(Function<URI, OkHttpClient> clients) {
        this.clients = clients;
    }

    public static ClientHttpRequestFactory requests(Exchange exchange) {
        return (uri, method) ->
                new org.springframework.http.client.AbstractClientHttpRequest() {
                    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

                    public HttpMethod getMethod() {
                        return method;
                    }

                    public URI getURI() {
                        return uri;
                    }

                    protected OutputStream getBodyInternal(HttpHeaders headers) {
                        return body;
                    }

                    protected org.springframework.http.client.ClientHttpResponse executeInternal(
                            HttpHeaders headers) throws IOException {
                        try {
                            Reply result =
                                    exchange.post(
                                            uri,
                                            headers.toSingleValueMap(),
                                            body.toString(StandardCharsets.UTF_8));
                            return new org.springframework.http.client.ClientHttpResponse() {
                                public HttpStatusCode getStatusCode() {
                                    return HttpStatusCode.valueOf(result.status());
                                }

                                public String getStatusText() {
                                    return "";
                                }

                                public HttpHeaders getHeaders() {
                                    var h = new HttpHeaders();
                                    h.setContentType(
                                            org.springframework.http.MediaType.APPLICATION_JSON);
                                    return h;
                                }

                                public InputStream getBody() {
                                    return new ByteArrayInputStream(
                                            (result.body() == null ? "" : result.body())
                                                    .getBytes(StandardCharsets.UTF_8));
                                }

                                public void close() {}
                            };
                        } catch (RuntimeException error) {
                            throw error;
                        } catch (Exception error) {
                            throw new IOException("AI HTTP transport failed", error);
                        }
                    }
                };
    }

    public static Reply post(OkHttpClient client, URI uri, Map<String, String> headers, String body)
            throws IOException {
        Request.Builder request =
                new Request.Builder()
                        .url(uri.toString())
                        .post(RequestBody.create(body, okhttp3.MediaType.get("application/json")));
        headers.forEach(request::header);
        Call call = client.newCall(request.build());
        Thread owner = Thread.currentThread();
        // RestClient 同步等待也必须响应执行器中断，不能释放槽位后留下一条收费连接。
        ScheduledFuture<?> interrupt =
                INTERRUPTS.scheduleAtFixedRate(
                        () -> {
                            if (owner.isInterrupted()) call.cancel();
                        },
                        50,
                        50,
                        TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            return new Reply(
                    response.code(), response.body() == null ? "" : response.body().string());
        } finally {
            interrupt.cancel(false);
        }
    }

    @Override
    public Mono<ClientHttpResponse> connect(
            HttpMethod method, URI uri, Function<? super ClientHttpRequest, Mono<Void>> callback) {
        return Mono.defer(
                () -> {
                    var request = new Outgoing(method, uri);
                    return callback.apply(request).then(Mono.defer(() -> exchange(request)));
                });
    }

    private Mono<ClientHttpResponse> exchange(Outgoing request) {
        return Mono.<ClientHttpResponse>create(
                        sink -> {
                            Request.Builder builder =
                                    new Request.Builder()
                                            .url(request.getURI().toString())
                                            .method(
                                                    request.getMethod().name(),
                                                    RequestBody.create(
                                                            request.body,
                                                            okhttp3.MediaType.get(
                                                                    "application/json")));
                            request.getHeaders()
                                    .forEach(
                                            (name, values) ->
                                                    values.forEach(
                                                            value ->
                                                                    builder.addHeader(
                                                                            name, value)));
                            Call call = clients.apply(request.getURI()).newCall(builder.build());
                            sink.onCancel(call::cancel);
                            call.enqueue(
                                    new Callback() {
                                        public void onFailure(Call ignored, IOException error) {
                                            sink.error(error);
                                        }

                                        public void onResponse(Call ignored, Response response) {
                                            sink.success(new Incoming(response, call));
                                        }
                                    });
                        })
                .doOnDiscard(Incoming.class, Incoming::close);
    }

    private static final class Outgoing extends AbstractClientHttpRequest {
        private final HttpMethod method;
        private final URI uri;
        private byte[] body = new byte[0];

        Outgoing(HttpMethod method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        public HttpMethod getMethod() {
            return method;
        }

        public URI getURI() {
            return uri;
        }

        @SuppressWarnings("unchecked")
        public <T> T getNativeRequest() {
            return (T) this;
        }

        public DataBufferFactory bufferFactory() {
            return DefaultDataBufferFactory.sharedInstance;
        }

        public Mono<Void> writeWith(Publisher<? extends DataBuffer> buffers) {
            return doCommit(
                    () ->
                            DataBufferUtils.join(Flux.from(buffers))
                                    .doOnNext(
                                            data -> {
                                                body = new byte[data.readableByteCount()];
                                                data.read(body);
                                                DataBufferUtils.release(data);
                                            })
                                    .then());
        }

        public Mono<Void> writeAndFlushWith(
                Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).concatMap(Flux::from));
        }

        public Mono<Void> setComplete() {
            return doCommit();
        }

        protected void applyHeaders() {}

        protected void applyCookies() {}
    }

    private static final class Incoming implements ClientHttpResponse {
        private final Response response;
        private final Call call;
        private final ReentrantLock reading = new ReentrantLock();

        Incoming(Response response, Call call) {
            this.response = response;
            this.call = call;
        }

        public HttpStatusCode getStatusCode() {
            return HttpStatusCode.valueOf(response.code());
        }

        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            response.headers().toMultimap().forEach(headers::put);
            return headers;
        }

        public MultiValueMap<String, ResponseCookie> getCookies() {
            return new LinkedMultiValueMap<>();
        }

        public Flux<DataBuffer> getBody() {
            // 每次读取最多 8 KiB，逐段交给 Spring SSE 解码器；取消直接关闭底层 socket。
            return Flux.<DataBuffer>generate(
                            sink -> {
                                try {
                                    byte[] bytes = new byte[8192];
                                    int count;
                                    // JDK 21 的 monitor 内阻塞会钉住 carrier，进而饿死 SSE
                                    // 虚拟线程。可卸载的锁仍保证 Okio read/close 串行。
                                    reading.lock();
                                    try {
                                        count =
                                                response.body() == null
                                                        ? -1
                                                        : response.body().byteStream().read(bytes);
                                    } finally {
                                        reading.unlock();
                                    }
                                    if (count < 0) sink.complete();
                                    else
                                        sink.next(
                                                DefaultDataBufferFactory.sharedInstance.wrap(
                                                        Arrays.copyOf(bytes, count)));
                                } catch (IOException error) {
                                    sink.error(error);
                                }
                            })
                    .subscribeOn(READERS)
                    .doOnCancel(call::cancel)
                    .doFinally(signal -> close());
        }

        void close() {
            call.cancel();
            // cancel 先唤醒阻塞读取；再等读取退出后关闭，避免 Okio 的并发 close/read 竞态。
            reading.lock();
            try {
                response.close();
            } finally {
                reading.unlock();
            }
        }
    }
}
