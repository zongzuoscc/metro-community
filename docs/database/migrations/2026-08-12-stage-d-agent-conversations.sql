-- Stage D Agent conversation truth. Forward-only and idempotent.

CREATE TABLE IF NOT EXISTS agent_profile (
    user_id BIGINT NOT NULL, personality_text VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (user_id),
    CONSTRAINT fk_agent_profile_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_run_guard (
    user_id BIGINT NOT NULL, active_run_id BINARY(16) NULL,
    active_run_type VARCHAR(16) NULL, run_fence BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL, lock_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL, PRIMARY KEY (user_id),
    UNIQUE KEY uk_agent_run_guard_active (active_run_id),
    CONSTRAINT fk_agent_run_guard_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT chk_agent_run_guard_type CHECK (
        active_run_type IS NULL OR active_run_type IN ('PERSISTENT','TEMPORARY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    last_message_id BIGINT NULL, memory_epoch BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (id),
    UNIQUE KEY uk_agent_conversation_user (user_id),
    UNIQUE KEY uk_agent_conversation_id_user (id,user_id),
    CONSTRAINT fk_agent_conversation_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_episode (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL, episode_no INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN state='ACTIVE' THEN 1 ELSE NULL END) STORED,
    opened_at DATETIME(6) NOT NULL, sealed_at DATETIME(6) NULL,
    summary_text MEDIUMTEXT NULL,
    summary_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    turn_count INT NOT NULL DEFAULT 0, token_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_episode_number (conversation_id,episode_no),
    UNIQUE KEY uk_agent_episode_active (conversation_id,active_slot),
    UNIQUE KEY uk_agent_episode_id_user (id,user_id),
    CONSTRAINT fk_agent_episode_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversation(id,user_id),
    CONSTRAINT chk_agent_episode_state CHECK (
        state IN ('ACTIVE','SEALED','SUMMARIZING','READY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_turn (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL, episode_id BIGINT NOT NULL,
    run_id BINARY(16) NOT NULL, client_request_id BINARY(16) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_type VARCHAR(32) NOT NULL, page_context_json JSON NOT NULL,
    grounding_mode VARCHAR(24) NOT NULL, state VARCHAR(16) NOT NULL,
    run_fence BIGINT NOT NULL, lease_until DATETIME(6) NULL,
    error_code VARCHAR(64) NULL, created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL, completed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL, PRIMARY KEY (id),
    UNIQUE KEY uk_agent_turn_run (run_id),
    UNIQUE KEY uk_agent_turn_request (conversation_id,client_request_id),
    UNIQUE KEY uk_agent_turn_id_user (id,user_id),
    KEY idx_agent_turn_recovery (state,lease_until,id),
    CONSTRAINT fk_agent_turn_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversation(id,user_id),
    CONSTRAINT fk_agent_turn_episode FOREIGN KEY (episode_id,user_id)
        REFERENCES agent_episode(id,user_id),
    CONSTRAINT chk_agent_turn_state CHECK (
        state IN ('RECEIVED','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_message (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL, conversation_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL, role VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL, content MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL, completed_at DATETIME(6) NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_agent_message_turn_role (turn_id,role),
    UNIQUE KEY uk_agent_message_id_user (id,user_id),
    KEY idx_agent_message_timeline (conversation_id,id DESC),
    CONSTRAINT fk_agent_message_turn FOREIGN KEY (turn_id,user_id)
        REFERENCES agent_turn(id,user_id),
    CONSTRAINT fk_agent_message_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversation(id,user_id),
    CONSTRAINT fk_agent_message_episode FOREIGN KEY (episode_id,user_id)
        REFERENCES agent_episode(id,user_id),
    CONSTRAINT chk_agent_message_role CHECK (role IN ('USER','ASSISTANT')),
    CONSTRAINT chk_agent_message_state CHECK (state IN ('PARTIAL','FINAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_tool_call (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL, ordinal INT NOT NULL, tool_name VARCHAR(64) NOT NULL,
    arguments_json JSON NOT NULL, state VARCHAR(16) NOT NULL,
    result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    duration_ms BIGINT NULL, error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL, completed_at DATETIME(6) NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_agent_tool_call_ordinal (turn_id,ordinal),
    CONSTRAINT fk_agent_tool_call_turn FOREIGN KEY (turn_id,user_id)
        REFERENCES agent_turn(id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_retrieval_hit (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL, source_type VARCHAR(24) NOT NULL,
    source_key VARCHAR(160) NOT NULL, article_id BIGINT NULL,
    revision_id BIGINT NULL, chunk_id BIGINT NULL, memory_id BIGINT NULL,
    bm25_score DOUBLE NULL, dense_score DOUBLE NULL, rrf_score DOUBLE NOT NULL,
    rank_no INT NOT NULL, excerpt_snapshot VARCHAR(1000) NULL,
    metadata_json JSON NOT NULL, expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_retrieval_source (turn_id,source_type,source_key),
    KEY idx_agent_retrieval_expiry (expires_at,id),
    CONSTRAINT fk_agent_retrieval_turn FOREIGN KEY (turn_id,user_id)
        REFERENCES agent_turn(id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_answer_citation (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    assistant_message_id BIGINT NOT NULL, ordinal INT NOT NULL,
    article_id BIGINT NOT NULL, revision_id BIGINT NOT NULL, chunk_id BIGINT NOT NULL,
    title_snapshot VARCHAR(100) NOT NULL, quote_snapshot VARCHAR(1000) NOT NULL,
    quote_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL, created_at DATETIME(6) NOT NULL,
    redacted_at DATETIME(6) NULL, PRIMARY KEY (id),
    UNIQUE KEY uk_agent_citation_ordinal (assistant_message_id,ordinal),
    CONSTRAINT fk_agent_citation_message FOREIGN KEY (assistant_message_id,user_id)
        REFERENCES agent_message(id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @agent_last_message_fk_count = (
    SELECT COUNT(*) FROM information_schema.referential_constraints
    WHERE constraint_schema=DATABASE()
      AND constraint_name='fk_agent_conversation_last_message'
);
SET @agent_last_message_fk_sql = IF(@agent_last_message_fk_count=0,
    'ALTER TABLE agent_conversation ADD CONSTRAINT fk_agent_conversation_last_message FOREIGN KEY (last_message_id,user_id) REFERENCES agent_message(id,user_id)',
    'SELECT 1');
PREPARE agent_last_message_fk_stmt FROM @agent_last_message_fk_sql;
EXECUTE agent_last_message_fk_stmt;
DEALLOCATE PREPARE agent_last_message_fk_stmt;
