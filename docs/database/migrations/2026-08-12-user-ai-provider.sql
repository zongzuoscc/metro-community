-- 用户自带 OpenAI 兼容模型配置。
-- 该迁移只保存服务端 AES-256-GCM 密文，部署必须另外注入独立主密钥。
CREATE TABLE IF NOT EXISTS user_ai_provider_setting (
    user_id            BIGINT       NOT NULL,
    provider           VARCHAR(24)  NOT NULL,
    base_url           VARCHAR(512) NOT NULL,
    model              VARCHAR(128) NOT NULL,
    encrypted_api_key  TEXT         NOT NULL,
    key_hint           VARCHAR(16)  NOT NULL,
    enabled            TINYINT(1)   NOT NULL DEFAULT 1,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    lock_version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_ai_provider_owner FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT chk_user_ai_provider_type CHECK (
        provider IN ('OPENAI','DEEPSEEK','QWEN','CUSTOM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
