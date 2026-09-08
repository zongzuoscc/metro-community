package cumt.zongzuo.community.ai.agent.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * 对话工作预算，不宣称等于供应商最大窗口。平台容量由部署者确认；未知 BYOK 模型使用
 * 较小的回退值，可按精确模型名覆盖。预留输出和估算余量后，剩余空间才允许放入资料。
 */
@ConfigurationProperties("metro.ai.context")
@org.springframework.stereotype.Component
public class AgentContextProperties {
    private int workingWindowTokens = 262144;
    private int platformWindowTokens = 32768;
    private int unknownModelWindowTokens = 8192;
    private int maxOutputTokens = 4096;
    private int safetyMarginTokens = 1024;
    private int historyPageTurns = 24;
    private java.time.Duration historyLoadTimeout = java.time.Duration.ofSeconds(2);
    private Map<String, Integer> modelWindows = new HashMap<>();

    public void validate() {
        if (workingWindowTokens < 2048 || workingWindowTokens > 262144
                || platformWindowTokens < 2048 || unknownModelWindowTokens < 2048
                || maxOutputTokens < 256 || safetyMarginTokens < 256
                || historyPageTurns < 1 || historyPageTurns > 64
                || historyLoadTimeout == null || historyLoadTimeout.isNegative()
                || historyLoadTimeout.isZero() || historyLoadTimeout.compareTo(java.time.Duration.ofSeconds(10)) > 0
                || modelWindows == null || modelWindows.values().stream()
                        .anyMatch(value -> value == null || value < 2048)) {
            throw new IllegalArgumentException("Invalid Agent context budget configuration");
        }
    }

    public int getWorkingWindowTokens() { return workingWindowTokens; }
    public void setWorkingWindowTokens(int value) { workingWindowTokens = value; }
    public int getPlatformWindowTokens() { return platformWindowTokens; }
    public void setPlatformWindowTokens(int value) { platformWindowTokens = value; }
    public int getUnknownModelWindowTokens() { return unknownModelWindowTokens; }
    public void setUnknownModelWindowTokens(int value) { unknownModelWindowTokens = value; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens = value; }
    public int getSafetyMarginTokens() { return safetyMarginTokens; }
    public void setSafetyMarginTokens(int value) { safetyMarginTokens = value; }
    public int getHistoryPageTurns() { return historyPageTurns; }
    public void setHistoryPageTurns(int value) { historyPageTurns = value; }
    public java.time.Duration getHistoryLoadTimeout() { return historyLoadTimeout; }
    public void setHistoryLoadTimeout(java.time.Duration value) { historyLoadTimeout = value; }
    public Map<String, Integer> getModelWindows() { return modelWindows; }
    public void setModelWindows(Map<String, Integer> value) { modelWindows = value; }
}
