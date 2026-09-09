package cumt.zongzuo.community.ai.agent.memory.index;

import java.time.LocalDateTime;

/** 不可变版本及其投影 CAS 快照，禁止用于直接构造用户召回结果。 */
public record MemoryProjectionRow(long memoryVersionId, long userId, long memoryId,
                                  String category, String sensitivity, String content,
                                  String contentHash, LocalDateTime expiresAt,
                                  String state, long lockVersion) {}
