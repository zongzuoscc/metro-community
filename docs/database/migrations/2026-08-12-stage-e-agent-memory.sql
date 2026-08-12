-- Stage E Agent long-term memory truth. Forward-only and idempotent.

CREATE TABLE IF NOT EXISTS agent_memory_setting (
    user_id BIGINT NOT NULL, enabled TINYINT(1) NOT NULL DEFAULT 1,
    sensitive_projection_enabled TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (user_id),
    CONSTRAINT fk_agent_memory_setting_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_memory_item (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    current_version_id BIGINT NULL, category VARCHAR(24) NOT NULL,
    sensitivity VARCHAR(16) NOT NULL, state VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NULL, created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (id),
    UNIQUE KEY uk_agent_memory_item_id_user (id,user_id),
    KEY idx_agent_memory_owner_state (user_id,state,updated_at,id),
    CONSTRAINT fk_agent_memory_item_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT chk_agent_memory_category CHECK (category IN ('PREFERENCE','GOAL','PROFILE')),
    CONSTRAINT chk_agent_memory_sensitivity CHECK (sensitivity IN ('LOW','SENSITIVE')),
    CONSTRAINT chk_agent_memory_state CHECK (state IN ('ACTIVE','PAUSED','DELETED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_memory_version (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    memory_id BIGINT NOT NULL, version_no BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL, normalized_content VARCHAR(1000) NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL, created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_agent_memory_version_no (memory_id,version_no),
    UNIQUE KEY uk_agent_memory_version_id_user (id,user_id),
    UNIQUE KEY uk_agent_memory_version_owner (id,memory_id,user_id),
    UNIQUE KEY uk_agent_memory_owner_hash (user_id,content_hash),
    CONSTRAINT fk_agent_memory_version_item FOREIGN KEY (memory_id,user_id)
        REFERENCES agent_memory_item(id,user_id),
    CONSTRAINT chk_agent_memory_version_state CHECK (state IN ('ACTIVE','SUPERSEDED','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_memory_source (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    memory_id BIGINT NOT NULL, memory_version_id BIGINT NOT NULL,
    source_turn_id BIGINT NOT NULL, source_message_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL, PRIMARY KEY (id),
    UNIQUE KEY uk_agent_memory_source_message (memory_id,source_message_id),
    CONSTRAINT fk_agent_memory_source_item FOREIGN KEY (memory_id,user_id)
        REFERENCES agent_memory_item(id,user_id),
    CONSTRAINT fk_agent_memory_source_version FOREIGN KEY (memory_version_id,memory_id,user_id)
        REFERENCES agent_memory_version(id,memory_id,user_id),
    CONSTRAINT fk_agent_memory_source_turn FOREIGN KEY (source_turn_id,user_id)
        REFERENCES agent_turn(id,user_id),
    CONSTRAINT fk_agent_memory_source_message FOREIGN KEY (source_message_id,user_id)
        REFERENCES agent_message(id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_memory_projection (
    memory_version_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL, embedding_model VARCHAR(64) NULL,
    projected_at DATETIME(6) NULL, last_error_code VARCHAR(64) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (memory_version_id),
    KEY idx_agent_memory_projection_recovery (state,memory_version_id),
    CONSTRAINT fk_agent_memory_projection_version FOREIGN KEY (memory_version_id,user_id)
        REFERENCES agent_memory_version(id,user_id),
    CONSTRAINT chk_agent_memory_projection_state CHECK (
        state IN ('PENDING','PROJECTED','DELETING','DELETED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @agent_memory_current_fk_count = (
    SELECT COUNT(*) FROM information_schema.referential_constraints
    WHERE constraint_schema=DATABASE() AND constraint_name='fk_agent_memory_current_version'
);
SET @agent_memory_current_fk_sql = IF(@agent_memory_current_fk_count=0,
    'ALTER TABLE agent_memory_item ADD CONSTRAINT fk_agent_memory_current_version FOREIGN KEY (current_version_id,id,user_id) REFERENCES agent_memory_version(id,memory_id,user_id)',
    'SELECT 1');
PREPARE agent_memory_current_fk_stmt FROM @agent_memory_current_fk_sql;
EXECUTE agent_memory_current_fk_stmt;
DEALLOCATE PREPARE agent_memory_current_fk_stmt;
