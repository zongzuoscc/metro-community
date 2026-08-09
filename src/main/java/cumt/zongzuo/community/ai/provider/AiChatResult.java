package cumt.zongzuo.community.ai.provider;

public record AiChatResult(String text, String finishReason, long inputTokens,
                           long outputTokens, String provider, String model) {
}
