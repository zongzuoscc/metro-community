package cumt.zongzuo.community.ai.config;

import cumt.zongzuo.community.ai.provider.AiCapability;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "metro.ai")
public class MetroAiProperties {

    private boolean enabled;
    private CapabilityProperties agent = capability(false, 4_000, 600, 8, 100,
            Duration.ofMinutes(1), Duration.ofSeconds(45), Duration.ofSeconds(45), 8);
    private CapabilityProperties articleSummary = capability(false, 100_000, 0, 5, 30,
            Duration.ofMinutes(1), Duration.ofSeconds(60), Duration.ofSeconds(60), 4);
    private CapabilityProperties writing = capability(false, 20_000, 0, 10, 60,
            Duration.ofMinutes(10), Duration.ofSeconds(60), Duration.ofSeconds(60), 4);
    private CapabilityProperties hyde = capability(false, 4_000, 600, 0, 0,
            Duration.ofMinutes(1), Duration.ofSeconds(8), Duration.ofSeconds(8), 8);
    private ModerationProperties moderation = moderation();
    private CapabilityProperties memory = capability(false, 20_000, 0, 0, 0,
            Duration.ofMinutes(1), Duration.ofSeconds(20), Duration.ofSeconds(20), 2);
    private EmbeddingProperties embedding = embedding();
    private RuntimeProperties runtime = new RuntimeProperties();
    private PlatformProperties platform = new PlatformProperties();
    private WebSearchProperties webSearch = new WebSearchProperties();
    private OllamaProperties ollama = new OllamaProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public CapabilityProperties getAgent() {
        return agent;
    }

    public void setAgent(CapabilityProperties agent) {
        this.agent = agent;
    }

    public CapabilityProperties getArticleSummary() {
        return articleSummary;
    }

    public void setArticleSummary(CapabilityProperties articleSummary) {
        this.articleSummary = articleSummary;
    }

    public CapabilityProperties getWriting() {
        return writing;
    }

    public void setWriting(CapabilityProperties writing) {
        this.writing = writing;
    }

    public CapabilityProperties getHyde() {
        return hyde;
    }

    public void setHyde(CapabilityProperties hyde) {
        this.hyde = hyde;
    }

    public ModerationProperties getModeration() {
        return moderation;
    }

    public void setModeration(ModerationProperties moderation) {
        this.moderation = moderation;
    }

    public CapabilityProperties getMemory() {
        return memory;
    }

    public void setMemory(CapabilityProperties memory) {
        this.memory = memory;
    }

    public EmbeddingProperties getEmbedding() {
        return embedding;
    }

    public void setEmbedding(EmbeddingProperties embedding) {
        this.embedding = embedding;
    }

    public RuntimeProperties getRuntime() {
        return runtime;
    }

    public void setRuntime(RuntimeProperties runtime) {
        this.runtime = runtime;
    }

    public PlatformProperties getPlatform() {
        return platform;
    }

    public WebSearchProperties getWebSearch() {
        return webSearch;
    }

    public void setWebSearch(WebSearchProperties webSearch) {
        this.webSearch = webSearch;
    }

    public void setPlatform(PlatformProperties platform) {
        this.platform = platform;
    }

    public OllamaProperties getOllama() {
        return ollama;
    }

    public void setOllama(OllamaProperties ollama) {
        this.ollama = ollama;
    }

    public boolean anyChatCapabilityEnabled() {
        return isCapabilityEnabled(AiCapability.AGENT)
                || isCapabilityEnabled(AiCapability.WRITING)
                || isCapabilityEnabled(AiCapability.MODERATION)
                || isCapabilityEnabled(AiCapability.MEMORY_EXTRACTION);
    }

    public boolean isCapabilityEnabled(AiCapability capability) {
        if (!enabled) {
            return false;
        }
        return switch (capability) {
            case AGENT, ARTICLE_SUMMARY, HYDE -> agent.isEnabled();
            case WRITING -> writing.isEnabled();
            case MODERATION -> moderation.isEnabled();
            case MEMORY_EXTRACTION -> memory.isEnabled();
            case EMBEDDING -> embedding.isEnabled();
        };
    }

    public void validateModeration() {
        ModerationProperties value = moderation;
        if (value == null || value.getTimeout() == null || value.getTimeout().isZero()
                || value.getTimeout().isNegative()
                || value.getTimeout().compareTo(Duration.ofSeconds(20)) > 0
                || value.getTaskTimeout() == null || value.getTaskTimeout().isZero()
                || value.getTaskTimeout().isNegative()
                || value.getTaskTimeout().compareTo(Duration.ofSeconds(90)) > 0
                || value.getTaskTimeout().compareTo(value.getTimeout()) < 0
                || value.getLeaseDuration() == null
                || value.getLeaseDuration().compareTo(value.getTaskTimeout().plusSeconds(1)) < 0
                || value.getMaxOutputTokens() <= 0 || value.getMaxChunkTokens() <= 0
                || value.getOverlapTokens() < 0
                || value.getOverlapTokens() >= value.getMaxChunkTokens()
                || value.getMaxChunks() <= 0 || value.getMaxEstimatedTokens() <= 0
                || value.getMaxEstimatedCostMicros() <= 0
                || value.getInputCostMicrosPerMillionTokens() <= 0
                || value.getOutputCostMicrosPerMillionTokens() <= 0
                || value.getMinimumConfidence() == null
                || value.getMinimumConfidence().compareTo(BigDecimal.ZERO) <= 0
                || value.getMinimumConfidence().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException("Invalid metro.ai.moderation safety configuration");
        }
        try {
            int reservedOutput = Math.multiplyExact(
                    Math.multiplyExact(value.getMaxChunks(), value.getMaxOutputTokens()),
                    runtime.getBackgroundMaxAttempts());
            if (reservedOutput >= value.getMaxEstimatedTokens()) {
                throw new ArithmeticException("moderation output reserve consumes whole task budget");
            }
            long reservedOutputCost = ceilingCost(reservedOutput,
                    value.getOutputCostMicrosPerMillionTokens());
            if (reservedOutputCost >= value.getMaxEstimatedCostMicros()) {
                throw new ArithmeticException("moderation output reserve consumes whole cost budget");
            }
        }
        catch (ArithmeticException error) {
            throw new IllegalStateException("Invalid metro.ai.moderation token budget", error);
        }
    }

    private static long ceilingCost(long tokens, long microsPerMillionTokens) {
        return Math.addExact(Math.multiplyExact(tokens, microsPerMillionTokens),
                999_999L) / 1_000_000L;
    }

    private static CapabilityProperties capability(boolean enabled, int maxInputCharacters,
                                                   int maxOutputCharacters, int perMinute, int perDay,
                                                   Duration quotaWindow, Duration timeout,
                                                   Duration taskTimeout, int bulkhead) {
        CapabilityProperties properties = new CapabilityProperties();
        properties.setEnabled(enabled);
        properties.setMaxInputCharacters(maxInputCharacters);
        properties.setMaxOutputCharacters(maxOutputCharacters);
        properties.setPerMinute(perMinute);
        properties.setPerDay(perDay);
        properties.setQuotaWindow(quotaWindow);
        properties.setTimeout(timeout);
        properties.setTaskTimeout(taskTimeout);
        properties.setBulkhead(bulkhead);
        return properties;
    }

    private static ModerationProperties moderation() {
        ModerationProperties properties = new ModerationProperties();
        properties.setEnabled(false);
        properties.setMaxInputCharacters(100_000);
        properties.setMaxOutputCharacters(0);
        properties.setPerMinute(0);
        properties.setPerDay(0);
        properties.setQuotaWindow(Duration.ofMinutes(1));
        properties.setTimeout(Duration.ofSeconds(20));
        properties.setTaskTimeout(Duration.ofSeconds(90));
        properties.setBulkhead(2);
        return properties;
    }

    private static EmbeddingProperties embedding() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(false);
        properties.setMaxInputCharacters(100_000);
        properties.setMaxOutputCharacters(0);
        properties.setPerMinute(0);
        properties.setPerDay(0);
        properties.setQuotaWindow(Duration.ofMinutes(1));
        properties.setTimeout(Duration.ofSeconds(45));
        properties.setTaskTimeout(Duration.ofSeconds(45));
        properties.setBulkhead(4);
        return properties;
    }

    public static class CapabilityProperties {

        private boolean enabled;
        private int maxInputCharacters;
        private int maxOutputCharacters;
        private int perMinute;
        private int perDay;
        private Duration quotaWindow;
        private Duration timeout;
        private Duration taskTimeout;
        private int bulkhead;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxInputCharacters() {
            return maxInputCharacters;
        }

        public void setMaxInputCharacters(int maxInputCharacters) {
            this.maxInputCharacters = maxInputCharacters;
        }

        public int getMaxOutputCharacters() {
            return maxOutputCharacters;
        }

        public void setMaxOutputCharacters(int maxOutputCharacters) {
            this.maxOutputCharacters = maxOutputCharacters;
        }

        public int getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(int perMinute) {
            this.perMinute = perMinute;
        }

        public int getPerDay() {
            return perDay;
        }

        public void setPerDay(int perDay) {
            this.perDay = perDay;
        }

        public Duration getQuotaWindow() {
            return quotaWindow;
        }

        public void setQuotaWindow(Duration quotaWindow) {
            this.quotaWindow = quotaWindow;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getTaskTimeout() {
            return taskTimeout;
        }

        public void setTaskTimeout(Duration taskTimeout) {
            this.taskTimeout = taskTimeout;
        }

        public int getBulkhead() {
            return bulkhead;
        }

        public void setBulkhead(int bulkhead) {
            this.bulkhead = bulkhead;
        }
    }

    /**
     * 站点为普通用户提供的默认大模型配置。
     *
     * <p>这一组值只能由后端环境变量或本地 {@code .env} 注入，绝不能通过用户设置接口
     * 返回给浏览器。用户自带 API 使用独立的加密凭据表，不会覆盖这里的平台兜底配置。</p>
     */
    public static class PlatformProperties {

        private String provider = "qwen";
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "qwen-plus";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    /**
     * 向量模型可以使用本机 Ollama，也可以复用后端统一配置的百炼平台。
     * provider 只接受 ollama/platform；维度固定写入请求并在网关响应处再次校验。
     */
    public static class EmbeddingProperties extends CapabilityProperties {

        private String provider = "ollama";
        private String model = "bge-m3";
        private int dimensions = 1_024;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    }

    /**
     * 平台提供的百炼联网搜索配置。
     *
     * <p>搜索与用户自带的生成模型分开：用户可以让自己的模型承担生成 Token，站点仍能
     * 用统一、可审计的搜索服务返回来源。密钥继续只由后端环境变量注入。</p>
     */
    public static class WebSearchProperties {

        private boolean enabled;
        private String baseUrl = "";
        private String model = "qwen-plus";
        private String strategy = "turbo";
        private int maxSources = 8;
        private Duration timeout = Duration.ofSeconds(35);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public int getMaxSources() { return maxSources; }
        public void setMaxSources(int maxSources) { this.maxSources = maxSources; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class ModerationProperties extends CapabilityProperties {

        private int maxOutputTokens = 800;
        private int maxChunkTokens = 3_000;
        private int overlapTokens = 200;
        private int maxChunks = 16;
        private int maxEstimatedTokens = 48_000;
        private long maxEstimatedCostMicros = 100_000L;
        private long inputCostMicrosPerMillionTokens = 500_000L;
        private long outputCostMicrosPerMillionTokens = 2_000_000L;
        private BigDecimal minimumConfidence = new BigDecimal("0.80");
        private Duration leaseDuration = Duration.ofSeconds(120);

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public int getMaxChunkTokens() {
            return maxChunkTokens;
        }

        public void setMaxChunkTokens(int maxChunkTokens) {
            this.maxChunkTokens = maxChunkTokens;
        }

        public int getOverlapTokens() {
            return overlapTokens;
        }

        public void setOverlapTokens(int overlapTokens) {
            this.overlapTokens = overlapTokens;
        }

        public int getMaxChunks() {
            return maxChunks;
        }

        public void setMaxChunks(int maxChunks) {
            this.maxChunks = maxChunks;
        }

        public int getMaxEstimatedTokens() {
            return maxEstimatedTokens;
        }

        public void setMaxEstimatedTokens(int maxEstimatedTokens) {
            this.maxEstimatedTokens = maxEstimatedTokens;
        }

        public long getMaxEstimatedCostMicros() {
            return maxEstimatedCostMicros;
        }

        public void setMaxEstimatedCostMicros(long maxEstimatedCostMicros) {
            this.maxEstimatedCostMicros = maxEstimatedCostMicros;
        }

        public long getInputCostMicrosPerMillionTokens() {
            return inputCostMicrosPerMillionTokens;
        }

        public void setInputCostMicrosPerMillionTokens(long value) {
            this.inputCostMicrosPerMillionTokens = value;
        }

        public long getOutputCostMicrosPerMillionTokens() {
            return outputCostMicrosPerMillionTokens;
        }

        public void setOutputCostMicrosPerMillionTokens(long value) {
            this.outputCostMicrosPerMillionTokens = value;
        }

        public BigDecimal getMinimumConfidence() {
            return minimumConfidence;
        }

        public void setMinimumConfidence(BigDecimal minimumConfidence) {
            this.minimumConfidence = minimumConfidence;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }
    }

    public static class RuntimeProperties {

        private String quotaNamespace = "metro:ai:quota";
        private Duration retryDelay = Duration.ofMillis(100);
        private int interactiveMaxAttempts = 2;
        private int backgroundMaxAttempts = 3;
        private int circuitSlidingWindowSize = 20;
        private int circuitMinimumCalls = 10;
        private float circuitFailureRateThreshold = 50.0f;
        private Duration circuitOpenStateWaitDuration = Duration.ofSeconds(30);
        private int circuitPermittedCallsInHalfOpen = 2;
        private Duration shutdownTimeout = Duration.ofSeconds(5);
        private Duration providerConnectTimeout = Duration.ofSeconds(2);
        private Duration providerTimeoutMargin = Duration.ofSeconds(1);

        public String getQuotaNamespace() {
            return quotaNamespace;
        }

        public void setQuotaNamespace(String quotaNamespace) {
            this.quotaNamespace = quotaNamespace;
        }

        public Duration getRetryDelay() {
            return retryDelay;
        }

        public void setRetryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
        }

        public int getInteractiveMaxAttempts() {
            return interactiveMaxAttempts;
        }

        public void setInteractiveMaxAttempts(int interactiveMaxAttempts) {
            this.interactiveMaxAttempts = interactiveMaxAttempts;
        }

        public int getBackgroundMaxAttempts() {
            return backgroundMaxAttempts;
        }

        public void setBackgroundMaxAttempts(int backgroundMaxAttempts) {
            this.backgroundMaxAttempts = backgroundMaxAttempts;
        }

        public int getCircuitSlidingWindowSize() {
            return circuitSlidingWindowSize;
        }

        public void setCircuitSlidingWindowSize(int circuitSlidingWindowSize) {
            this.circuitSlidingWindowSize = circuitSlidingWindowSize;
        }

        public int getCircuitMinimumCalls() {
            return circuitMinimumCalls;
        }

        public void setCircuitMinimumCalls(int circuitMinimumCalls) {
            this.circuitMinimumCalls = circuitMinimumCalls;
        }

        public float getCircuitFailureRateThreshold() {
            return circuitFailureRateThreshold;
        }

        public void setCircuitFailureRateThreshold(float circuitFailureRateThreshold) {
            this.circuitFailureRateThreshold = circuitFailureRateThreshold;
        }

        public Duration getCircuitOpenStateWaitDuration() {
            return circuitOpenStateWaitDuration;
        }

        public void setCircuitOpenStateWaitDuration(Duration circuitOpenStateWaitDuration) {
            this.circuitOpenStateWaitDuration = circuitOpenStateWaitDuration;
        }

        public int getCircuitPermittedCallsInHalfOpen() {
            return circuitPermittedCallsInHalfOpen;
        }

        public void setCircuitPermittedCallsInHalfOpen(int circuitPermittedCallsInHalfOpen) {
            this.circuitPermittedCallsInHalfOpen = circuitPermittedCallsInHalfOpen;
        }

        public Duration getShutdownTimeout() {
            return shutdownTimeout;
        }

        public void setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
        }

        public Duration getProviderConnectTimeout() {
            return providerConnectTimeout;
        }

        public void setProviderConnectTimeout(Duration providerConnectTimeout) {
            this.providerConnectTimeout = providerConnectTimeout;
        }

        public Duration getProviderTimeoutMargin() {
            return providerTimeoutMargin;
        }

        public void setProviderTimeoutMargin(Duration providerTimeoutMargin) {
            this.providerTimeoutMargin = providerTimeoutMargin;
        }
    }

    public static class OllamaProperties {

        private String baseUrl = "http://127.0.0.1:11434";
        private String model = "bge-m3";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
