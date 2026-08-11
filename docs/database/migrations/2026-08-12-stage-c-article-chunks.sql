-- Stage C deterministic article chunk facts and parser-generation admission.
-- Forward-only and safe to rerun; exact-shape validation follows the atomic creates.
SET @stage_c_chunk_schema = DATABASE();


CREATE TABLE IF NOT EXISTS article_chunk_parser_generation (
    generation                  BIGINT      NOT NULL,
    parser_version              VARCHAR(32) NOT NULL,
    token_estimator_version     VARCHAR(32) NOT NULL,
    dependency_fingerprint      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    required_build_digest       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state                       VARCHAR(16) NOT NULL,
    rollback_deadline           DATETIME(6) NULL,
    operator_identity           VARCHAR(96) NOT NULL,
    created_at                  DATETIME(6) NOT NULL,
    updated_at                  DATETIME(6) NOT NULL,
    lock_version                BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (generation),
    CONSTRAINT chk_chunk_parser_state
        CHECK (state IN ('BUILDING','ACTIVE','DRAINING','RETIRED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS article_chunk_parser_checkpoint (
    checkpoint_id      TINYINT     NOT NULL,
    active_generation  BIGINT      NOT NULL,
    lock_version       BIGINT      NOT NULL DEFAULT 0,
    updated_by         VARCHAR(96) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (checkpoint_id),
    CONSTRAINT chk_chunk_parser_checkpoint_singleton CHECK (checkpoint_id=1),
    CONSTRAINT fk_chunk_parser_checkpoint_generation
        FOREIGN KEY (active_generation) REFERENCES article_chunk_parser_generation(generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS article_chunk_set (
    article_id                BIGINT      NOT NULL,
    published_revision_id     BIGINT      NULL,
    parser_generation         BIGINT      NOT NULL,
    parser_version            VARCHAR(32) NOT NULL,
    chunk_set_version         BIGINT      NOT NULL,
    source_lifecycle_epoch    BIGINT      NOT NULL,
    source_aggregate_version  BIGINT      NOT NULL,
    chunk_set_hash            CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_chunk_count        INT         NOT NULL,
    published_at              DATETIME(6) NULL,
    lock_version              BIGINT      NOT NULL DEFAULT 0,
    updated_at                DATETIME(6) NOT NULL,
    PRIMARY KEY (article_id),
    CONSTRAINT fk_chunk_set_article FOREIGN KEY (article_id) REFERENCES article(id),
    CONSTRAINT fk_chunk_set_revision FOREIGN KEY (published_revision_id,article_id)
        REFERENCES article_revision(id,article_id),
    CONSTRAINT fk_chunk_set_parser FOREIGN KEY (parser_generation)
        REFERENCES article_chunk_parser_generation(generation),
    CONSTRAINT chk_chunk_set_active_count CHECK (active_chunk_count>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS article_chunk (
    id                       BIGINT       NOT NULL,
    article_id               BIGINT       NOT NULL,
    revision_id              BIGINT       NOT NULL,
    chunk_no                 INT          NOT NULL,
    parser_generation        BIGINT       NOT NULL,
    parser_version           VARCHAR(32)  NOT NULL,
    title                    VARCHAR(100) NOT NULL,
    heading_path_json        VARCHAR(2000) NOT NULL,
    body_text                MEDIUMTEXT   NOT NULL,
    start_codepoint          INT          NOT NULL,
    end_codepoint            INT          NOT NULL,
    estimated_tokens         INT          NOT NULL,
    revision_content_hash    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_hash               CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_input_hash     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    language                 VARCHAR(16)  NOT NULL,
    is_active                TINYINT(1)   NOT NULL,
    published_at             DATETIME(6)  NOT NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_article_chunk_identity (revision_id,parser_generation,chunk_no),
    KEY idx_article_chunk_current (article_id,is_active,parser_generation,chunk_no),
    CONSTRAINT fk_article_chunk_article FOREIGN KEY (article_id) REFERENCES article(id),
    CONSTRAINT fk_article_chunk_revision FOREIGN KEY (revision_id,article_id)
        REFERENCES article_revision(id,article_id),
    CONSTRAINT fk_article_chunk_parser FOREIGN KEY (parser_generation)
        REFERENCES article_chunk_parser_generation(generation),
    CONSTRAINT chk_article_chunk_offsets CHECK (
        start_codepoint>=0 AND end_codepoint>=start_codepoint AND estimated_tokens>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


SET @stage_c_chunk_shape_valid =
  (SELECT COUNT(*)=11 FROM information_schema.columns
    WHERE table_schema=@stage_c_chunk_schema AND table_name='article_chunk_parser_generation')
  AND (SELECT COUNT(*)=5 FROM information_schema.columns
    WHERE table_schema=@stage_c_chunk_schema AND table_name='article_chunk_parser_checkpoint')
  AND (SELECT COUNT(*)=12 FROM information_schema.columns
    WHERE table_schema=@stage_c_chunk_schema AND table_name='article_chunk_set')
  AND (SELECT COUNT(*)=20 FROM information_schema.columns
    WHERE table_schema=@stage_c_chunk_schema AND table_name='article_chunk')
  AND (SELECT COUNT(*)=4 FROM information_schema.tables
    WHERE table_schema=@stage_c_chunk_schema
      AND table_name IN ('article_chunk_parser_generation','article_chunk_parser_checkpoint',
        'article_chunk_set','article_chunk')
      AND table_type='BASE TABLE' AND LOWER(engine)='innodb'
      AND LOWER(table_collation)='utf8mb4_unicode_ci');
SET @stage_c_chunk_ddl = IF(@stage_c_chunk_shape_valid, 'SELECT 1',
  'SELECT SCHEMA_DRIFT_stage_c_article_chunk_columns');
PREPARE stage_c_chunk_stmt FROM @stage_c_chunk_ddl;
EXECUTE stage_c_chunk_stmt;
DEALLOCATE PREPARE stage_c_chunk_stmt;
