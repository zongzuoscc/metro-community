CREATE TABLE IF NOT EXISTS projection_consumer_registry (
    consumer_name VARCHAR(96) NOT NULL, aggregate_type VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL, proof_mode VARCHAR(24) NOT NULL,
    required_for_retention TINYINT(1) NOT NULL, retirement_high_water_id BIGINT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, updated_by VARCHAR(96) NOT NULL,
    updated_at DATETIME(6) NOT NULL, PRIMARY KEY (consumer_name),
    CONSTRAINT chk_projection_consumer_state CHECK (state IN ('ACTIVE','DRAINING','DISABLED')),
    CONSTRAINT chk_projection_consumer_proof CHECK (proof_mode IN ('WATERMARK','TARGET_MANIFEST')),
    CONSTRAINT chk_projection_consumer_required_state CHECK (
      (state IN ('ACTIVE','DRAINING') AND required_for_retention=1)
      OR (state='DISABLED' AND required_for_retention=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS projection_consumer_event_type (
    consumer_name VARCHAR(96) NOT NULL, event_type VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL, PRIMARY KEY (consumer_name,event_type),
    CONSTRAINT fk_projection_consumer_event_consumer FOREIGN KEY (consumer_name)
      REFERENCES projection_consumer_registry(consumer_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS projection_target_registry (
    id BIGINT NOT NULL, kind VARCHAR(32) NOT NULL, consumer_name VARCHAR(96) NULL,
    physical_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    read_alias VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    schema_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_name VARCHAR(64) NULL, model_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dimension INT NULL, generation BIGINT NOT NULL, target_role VARCHAR(32) NOT NULL,
    state VARCHAR(16) NOT NULL, required_for_retention TINYINT(1) NOT NULL DEFAULT 0,
    rebuild_job_id BIGINT NULL, rollback_deadline DATETIME(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, operator_identity VARCHAR(96) NOT NULL,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_projection_target_physical (kind,physical_name),
    KEY idx_projection_target_consumer_state (consumer_name,state),
    CONSTRAINT fk_projection_target_consumer FOREIGN KEY (consumer_name)
      REFERENCES projection_consumer_registry(consumer_name),
    CONSTRAINT chk_projection_target_state CHECK (
      state IN ('SCHEMA_ONLY','BUILDING','VERIFYING','ACTIVE','DRAINING','RETIRED','FAILED')),
    CONSTRAINT chk_projection_target_consumer_binding CHECK (
      (state='SCHEMA_ONLY' AND consumer_name IS NULL AND required_for_retention=0)
      OR (state<>'SCHEMA_ONLY' AND consumer_name IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS projection_entity_manifest (
    target_id BIGINT NOT NULL, entity_kind VARCHAR(32) NOT NULL, entity_id BIGINT NOT NULL,
    desired_lifecycle_epoch BIGINT NOT NULL, desired_aggregate_version BIGINT NOT NULL,
    desired_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    applied_lifecycle_epoch BIGINT NULL, applied_aggregate_version BIGINT NULL,
    applied_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    tombstone TINYINT(1) NOT NULL DEFAULT 0, effect_state VARCHAR(16) NOT NULL,
    repair_required TINYINT(1) NOT NULL DEFAULT 0, repair_next_attempt_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL, lock_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL, PRIMARY KEY (target_id,entity_kind,entity_id),
    KEY idx_projection_manifest_repair (repair_required,repair_next_attempt_at,target_id),
    CONSTRAINT fk_projection_manifest_target FOREIGN KEY (target_id)
      REFERENCES projection_target_registry(id),
    CONSTRAINT chk_projection_manifest_effect CHECK (
      effect_state IN ('PENDING','APPLIED','TOMBSTONE','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS projection_rebuild_job (
    id BIGINT NOT NULL, job_kind VARCHAR(24) NOT NULL, target_id BIGINT NULL,
    target_parser_generation BIGINT NULL, status VARCHAR(24) NOT NULL,
    snapshot_high_water_id BIGINT NOT NULL, last_replayed_outbox_id BIGINT NOT NULL DEFAULT 0,
    source_cursor VARCHAR(256) NULL, lease_owner VARCHAR(96) NULL, lease_until DATETIME(6) NULL,
    recovery_not_before DATETIME(6) NULL, hard_deadline DATETIME(6) NULL,
    verification_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verified_count BIGINT NULL, alias_proof_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, operator_identity VARCHAR(96) NOT NULL,
    created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id), KEY idx_projection_rebuild_status (status,lease_until,id),
    CONSTRAINT fk_projection_rebuild_target FOREIGN KEY (target_id)
      REFERENCES projection_target_registry(id),
    CONSTRAINT chk_projection_rebuild_kind CHECK (
      job_kind IN ('CHUNK_FACT','ES_TARGET','MILVUS_TARGET')),
    CONSTRAINT chk_projection_rebuild_status CHECK (
      status IN ('PENDING','RUNNING','RECOVERY_REQUIRED','VERIFYING','COMPLETE','FAILED','CANCELLED')),
    CONSTRAINT chk_projection_rebuild_binding CHECK (
      (job_kind='CHUNK_FACT' AND target_id IS NULL AND target_parser_generation IS NOT NULL)
      OR (job_kind<>'CHUNK_FACT' AND target_id IS NOT NULL AND target_parser_generation IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS projection_rebuild_item (
    job_id BIGINT NOT NULL, entity_kind VARCHAR(32) NOT NULL, entity_id BIGINT NOT NULL,
    source_lifecycle_epoch BIGINT NOT NULL, source_aggregate_version BIGINT NOT NULL,
    source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL, lease_owner VARCHAR(96) NULL, lease_until DATETIME(6) NULL,
    hard_deadline DATETIME(6) NULL, result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_error_code VARCHAR(64) NULL, lock_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL, PRIMARY KEY (job_id,entity_kind,entity_id),
    KEY idx_projection_rebuild_item_state (job_id,state,lease_until,entity_id),
    CONSTRAINT fk_projection_rebuild_item_job FOREIGN KEY (job_id)
      REFERENCES projection_rebuild_job(id),
    CONSTRAINT chk_projection_rebuild_item_state CHECK (
      state IN ('PENDING','RUNNING','APPLIED','STALE','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS projection_switch_fence (
    kind VARCHAR(32) NOT NULL, state VARCHAR(16) NOT NULL, generation BIGINT NOT NULL,
    owner VARCHAR(96) NULL, lease_until DATETIME(6) NULL, fence_high_water_id BIGINT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0, updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (kind), CONSTRAINT chk_projection_switch_fence_state
      CHECK (state IN ('OPEN','FENCING','FENCED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO projection_consumer_registry
  (consumer_name,aggregate_type,state,proof_mode,required_for_retention,
   retirement_high_water_id,lock_version,updated_by,updated_at)
VALUES
  ('article-search-current-pointer','ARTICLE','ACTIVE','WATERMARK',1,NULL,0,'stage-c-migration',CURRENT_TIMESTAMP(6)),
  ('article-chunk-current-pointer','ARTICLE','DISABLED','WATERMARK',0,NULL,0,'stage-c-migration',CURRENT_TIMESTAMP(6)),
  ('article-chunk-elasticsearch','ARTICLE_CHUNK_SET','DISABLED','TARGET_MANIFEST',0,NULL,0,'stage-c-migration',CURRENT_TIMESTAMP(6)),
  ('article-chunk-milvus','ARTICLE_CHUNK_SET','DISABLED','TARGET_MANIFEST',0,NULL,0,'stage-c-migration',CURRENT_TIMESTAMP(6));

INSERT IGNORE INTO projection_consumer_event_type (consumer_name,event_type,created_at) VALUES
  ('article-search-current-pointer','ARTICLE_REVISION_PUBLISHED',CURRENT_TIMESTAMP(6)),
  ('article-search-current-pointer','ARTICLE_REVISION_REJECTED',CURRENT_TIMESTAMP(6)),
  ('article-search-current-pointer','ARTICLE_REVISION_SUPERSEDED',CURRENT_TIMESTAMP(6)),
  ('article-search-current-pointer','ARTICLE_UNPUBLISHED',CURRENT_TIMESTAMP(6)),
  ('article-search-current-pointer','ARTICLE_DELETED',CURRENT_TIMESTAMP(6)),
  ('article-chunk-current-pointer','ARTICLE_REVISION_PUBLISHED',CURRENT_TIMESTAMP(6)),
  ('article-chunk-current-pointer','ARTICLE_REVISION_REJECTED',CURRENT_TIMESTAMP(6)),
  ('article-chunk-current-pointer','ARTICLE_REVISION_SUPERSEDED',CURRENT_TIMESTAMP(6)),
  ('article-chunk-current-pointer','ARTICLE_UNPUBLISHED',CURRENT_TIMESTAMP(6)),
  ('article-chunk-current-pointer','ARTICLE_DELETED',CURRENT_TIMESTAMP(6)),
  ('article-chunk-elasticsearch','ARTICLE_CHUNK_REINDEX_REQUESTED',CURRENT_TIMESTAMP(6)),
  ('article-chunk-milvus','ARTICLE_CHUNK_REINDEX_REQUESTED',CURRENT_TIMESTAMP(6));

-- Every new table is additive, but a near-compatible pre-existing shape is unsafe. Reject
-- extra/missing columns and wrong table engines/collations instead of silently adopting drift.
SET @stage_c_schema = DATABASE();
SET @stage_c_shape_valid =
  (SELECT COUNT(*)=9 FROM information_schema.columns
    WHERE table_schema=@stage_c_schema AND table_name='projection_consumer_registry')
  AND (SELECT COUNT(*)=3 FROM information_schema.columns
    WHERE table_schema=@stage_c_schema AND table_name='projection_consumer_event_type')
  AND (SELECT COUNT(*)=19 FROM information_schema.columns
    WHERE table_schema=@stage_c_schema AND table_name='projection_target_registry')
  AND (SELECT COUNT(*)=16 FROM information_schema.columns
    WHERE table_schema=@stage_c_schema AND table_name='projection_entity_manifest')
  AND (SELECT COUNT(*)=19 FROM information_schema.columns
    WHERE table_schema=@stage_c_schema AND table_name='projection_rebuild_job')
  AND (SELECT COUNT(*)=14 FROM information_schema.columns
    WHERE table_schema=@stage_c_schema AND table_name='projection_rebuild_item')
  AND (SELECT COUNT(*)=8 FROM information_schema.columns
    WHERE table_schema=@stage_c_schema AND table_name='projection_switch_fence')
  AND (SELECT COUNT(*)=7 FROM information_schema.tables
    WHERE table_schema=@stage_c_schema
      AND table_name IN ('projection_consumer_registry','projection_consumer_event_type',
        'projection_target_registry','projection_entity_manifest','projection_rebuild_job',
        'projection_rebuild_item','projection_switch_fence')
      AND table_type='BASE TABLE' AND LOWER(engine)='innodb'
      AND LOWER(table_collation)='utf8mb4_unicode_ci');
SET @stage_c_ddl = IF(@stage_c_shape_valid, 'SELECT 1',
  'SELECT SCHEMA_DRIFT_stage_c_projection_columns');
PREPARE stage_c_stmt FROM @stage_c_ddl;
EXECUTE stage_c_stmt;
DEALLOCATE PREPARE stage_c_stmt;
