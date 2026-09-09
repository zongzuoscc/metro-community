-- 回答前压缩批次：PENDING 表示摘要/记忆已在 MySQL 提交，尚未确认向量可见。
-- 只有 READY 摘要可替代工作上下文；agent_message 原文不受影响。
CREATE TABLE IF NOT EXISTS agent_context_compaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    context_boundary INT NOT NULL,
    previous_turn_id BIGINT NOT NULL,
    through_turn_id BIGINT NOT NULL,
    summary_text TEXT NOT NULL,
    memory_enabled TINYINT(1) NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    ready_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_compaction_prefix (user_id,context_boundary,previous_turn_id),
    KEY idx_agent_compaction_checkpoint (user_id,context_boundary,state,through_turn_id),
    CONSTRAINT fk_agent_compaction_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_agent_compaction_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversation(id,user_id),
    CONSTRAINT chk_agent_compaction_state CHECK (state IN ('PENDING','READY','REDACTED')),
    CONSTRAINT chk_agent_compaction_range CHECK (through_turn_id>previous_turn_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
