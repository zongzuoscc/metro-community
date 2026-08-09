package cumt.zongzuo.community.ai.config;

import cumt.zongzuo.community.ai.provider.AiCapability;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
    private CapabilityProperties moderation = capability(false, 100_000, 0, 0, 0,
            Duration.ofMinutes(1), Duration.ofSeconds(20), Duration.ofSeconds(90), 2);
    private CapabilityProperties memory = capability(false, 20_000, 0, 0, 0,
            Duration.ofMinutes(1), Duration.ofSeconds(20), Duration.ofSeconds(20), 2);
    private CapabilityProperties embedding = capability(false, 100_000, 0, 0, 0,
            Duration.ofMinutes(1), Duration.ofSeconds(45), Duration.ofSeconds(45), 4);
    private DeepSeekProperties deepSeek = new DeepSeekProperties();
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

    public CapabilityProperties getModeration() {
        return moderation;
    }

    public void setModeration(CapabilityProperties moderation) {
        this.moderation = moderation;
    }

    public CapabilityProperties getMemory() {
        return memory;
    }

    public void setMemory(CapabilityProperties memory) {
        this.memory = memory;
    }

    public CapabilityProperties getEmbedding() {
        return embedding;
    }

    public void setEmbedding(CapabilityProperties embedding) {
        this.embedding = embedding;
    }

    public DeepSeekProperties getDeepSeek() {
        return deepSeek;
    }

    public void setDeepSeek(DeepSeekProperties deepSeek) {
        this.deepSeek = deepSeek;
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

    public static class DeepSeekProperties {

        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-v4-flash";

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
