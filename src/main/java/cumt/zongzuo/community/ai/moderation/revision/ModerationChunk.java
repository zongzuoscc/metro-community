package cumt.zongzuo.community.ai.moderation.revision;

public record ModerationChunk(int index, String headingPath, String content,
                              int sourceStart, int sourceEnd, int estimatedTokens) {
}
