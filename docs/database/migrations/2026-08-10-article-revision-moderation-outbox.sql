-- Stage B additive article revision, moderation and durable event schema
-- MySQL 8; forward-only; safe to execute repeatedly. Every ALTER object is guarded.
-- Use a dedicated migration connection: lock_wait_timeout is session-scoped and a failed run closes it.
SET @stage_b_previous_lock_wait_timeout = @@SESSION.lock_wait_timeout;
SET SESSION lock_wait_timeout = 3;
SET @schema_name = DATABASE();

-- Fail closed when the legacy prerequisites cannot support the required composite ownership key.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='article' AND table_type='BASE TABLE' AND LOWER(engine)='innodb')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_prerequisite_article_table');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='message' AND table_type='BASE TABLE' AND LOWER(engine)='innodb')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_prerequisite_message_table');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='tag' AND table_type='BASE TABLE' AND LOWER(engine)='innodb')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_prerequisite_tag_table');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND LOWER(extra)='auto_increment' AND character_set_name IS NULL AND collation_name IS NULL)=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_prerequisite_article_id');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='author_id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_prerequisite_article_author_id');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- Widen the compatibility mirror before any revision can freeze content that TEXT cannot represent.
-- Only the exact legacy and exact target definitions are accepted; every other shape is drift.
SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='content');
SET @object_legacy_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='content' AND LOWER(column_type)='text' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci' AND column_comment='内容');
SET @object_target_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='content' AND LOWER(column_type)='mediumtext' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci' AND column_comment='内容');
SET @ddl = IF(@object_count=1 AND @object_target_valid=1, 'SELECT 1', IF(@object_count=1 AND @object_legacy_valid=1, 'ALTER TABLE article MODIFY COLUMN content MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT ''内容''', 'SELECT SCHEMA_DRIFT_article_content'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- Canonical frozen tags use exact Unicode code-point identity. MySQL 8 0900_bin is NO PAD,
-- so case, accents and trailing spaces cannot silently collapse to a different immutable tag.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.collations WHERE collation_name='utf8mb4_0900_bin' AND character_set_name='utf8mb4' AND pad_attribute='NO PAD')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_required_utf8mb4_0900_bin_no_pad');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='tag' AND column_name='name');
SET @object_legacy_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='tag' AND column_name='name' AND LOWER(column_type)='varchar(50)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci' AND column_comment='标签名');
SET @object_target_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='tag' AND column_name='name' AND LOWER(column_type)='varchar(50)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_0900_bin' AND column_comment='标签名');
SET @ddl = IF(@object_count=1 AND @object_target_valid=1, 'SELECT 1', IF(@object_count=1 AND @object_legacy_valid=1, 'ALTER TABLE tag MODIFY COLUMN name VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT ''标签名''', 'SELECT SCHEMA_DRIFT_tag_name'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- The historical name constraint must remain a visible, full-width, one-column unique BTREE.
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='tag' AND index_name='uk_name');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:name' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='tag' AND index_name='uk_name');
SET @ddl = IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_tag_uk_name');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- Existing-table additive columns.
-- guarded column article.latest_revision_id
SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='latest_revision_id');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='latest_revision_id' AND LOWER(column_type)='bigint' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article ADD COLUMN latest_revision_id BIGINT NULL', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_latest_revision_id'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded column article.pending_revision_id
SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='pending_revision_id');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='pending_revision_id' AND LOWER(column_type)='bigint' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article ADD COLUMN pending_revision_id BIGINT NULL', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_pending_revision_id'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded column article.published_revision_id
SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='published_revision_id');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='published_revision_id' AND LOWER(column_type)='bigint' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article ADD COLUMN published_revision_id BIGINT NULL', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_published_revision_id'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- TEST_CHECKPOINT_AFTER_THIRD_ARTICLE_COLUMN

-- guarded column article.visibility_state
SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='visibility_state');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='visibility_state' AND LOWER(column_type)='varchar(24)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci');
SET @ddl = IF(@object_count=0, 'ALTER TABLE article ADD COLUMN visibility_state VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_visibility_state'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded column article.review_state
SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='review_state');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='review_state' AND LOWER(column_type)='varchar(24)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci');
SET @ddl = IF(@object_count=0, 'ALTER TABLE article ADD COLUMN review_state VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_review_state'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded column article.lifecycle_epoch
SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='lifecycle_epoch');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='lifecycle_epoch' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND CAST(column_default AS CHAR)='1' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article ADD COLUMN lifecycle_epoch BIGINT NOT NULL DEFAULT 1', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_lifecycle_epoch'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded column article.lock_version
SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='lock_version');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article' AND column_name='lock_version' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND CAST(column_default AS CHAR)='0' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_lock_version'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded column message.source_event_id
SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='message' AND column_name='source_event_id');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='message' AND column_name='source_event_id' AND LOWER(column_type)='binary(16)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL);
SET @ddl = IF(@object_count=0, 'ALTER TABLE message ADD COLUMN source_event_id BINARY(16) NULL', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_message_source_event_id'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- Existing-table indexes required before child table foreign keys.
-- guarded index article.uk_article_id_author
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article' AND index_name='uk_article_id_author');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:id,2:author_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article' AND index_name='uk_article_id_author');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_article_id_author ON article(id,author_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_uk_article_id_author'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- Duplicate non-null legacy notification event ids cannot be made unique safely.
SET @ddl = IF((SELECT COUNT(*) FROM (SELECT source_event_id FROM message WHERE source_event_id IS NOT NULL GROUP BY source_event_id HAVING COUNT(*)>1) AS duplicate_source_events)=0, 'SELECT 1', 'SELECT SCHEMA_DRIFT_message_source_event_duplicates');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index message.uk_message_source_event
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='message' AND index_name='uk_message_source_event');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:source_event_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='message' AND index_name='uk_message_source_event');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_message_source_event ON message(source_event_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_message_uk_message_source_event'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- New tables are atomic CREATE IF NOT EXISTS operations; exact metadata validation follows each create.
-- atomic table create followed by exact column/engine/collation validation: article_draft
CREATE TABLE IF NOT EXISTS article_draft (
  article_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  draft_version BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL,
  summary VARCHAR(255) NULL,
  body_markdown MEDIUMTEXT NULL,
  body_plain MEDIUMTEXT NULL,
  cover VARCHAR(255) NULL,
  tags_json JSON NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SET @ddl = IF((SELECT IF(COUNT(*)=13 AND SUM(column_name='article_id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='user_id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='draft_version' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='title' AND LOWER(column_type)='varchar(100)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='summary' AND LOWER(column_type)='varchar(255)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='body_markdown' AND LOWER(column_type)='mediumtext' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='body_plain' AND LOWER(column_type)='mediumtext' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='cover' AND LOWER(column_type)='varchar(255)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='tags_json' AND LOWER(column_type)='json' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='content_hash' AND LOWER(column_type)='char(64)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1 AND SUM(column_name='created_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='updated_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='lock_version' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND CAST(column_default AS CHAR)='0' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1,1,0) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article_draft')=1 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='article_draft' AND LOWER(engine)='innodb' AND LOWER(table_collation)='utf8mb4_unicode_ci')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_draft_columns' );
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- atomic table create followed by exact column/engine/collation validation: article_revision
CREATE TABLE IF NOT EXISTS article_revision (
  id BIGINT AUTO_INCREMENT,
  article_id BIGINT NOT NULL,
  revision_no BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL,
  summary VARCHAR(255) NULL,
  body_markdown MEDIUMTEXT NULL,
  body_plain MEDIUMTEXT NULL,
  cover VARCHAR(255) NULL,
  tags_json JSON NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_draft_version BIGINT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SET @ddl = IF((SELECT IF(COUNT(*)=13 AND SUM(column_name='id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND LOWER(extra)='auto_increment' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='article_id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='revision_no' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='title' AND LOWER(column_type)='varchar(100)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='summary' AND LOWER(column_type)='varchar(255)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='body_markdown' AND LOWER(column_type)='mediumtext' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='body_plain' AND LOWER(column_type)='mediumtext' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='cover' AND LOWER(column_type)='varchar(255)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='tags_json' AND LOWER(column_type)='json' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='content_hash' AND LOWER(column_type)='char(64)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1 AND SUM(column_name='source_draft_version' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='created_by' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='created_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1,1,0) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article_revision')=1 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='article_revision' AND LOWER(engine)='innodb' AND LOWER(table_collation)='utf8mb4_unicode_ci')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_columns' );
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- atomic table create followed by exact column/engine/collation validation: article_moderation_job
CREATE TABLE IF NOT EXISTS article_moderation_job (
  id BIGINT AUTO_INCREMENT,
  article_id BIGINT NOT NULL,
  revision_id BIGINT NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(24) NOT NULL,
  model_decision VARCHAR(16) NULL,
  risk_score DECIMAL(6,5) NULL,
  policy_hits_json JSON NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NULL,
  lease_owner VARCHAR(96) NULL,
  lease_until DATETIME(6) NULL,
  last_error VARCHAR(500) NULL,
  reviewer_id BIGINT NULL,
  review_reason VARCHAR(500) NULL,
  reviewed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SET @ddl = IF((SELECT IF(COUNT(*)=19 AND SUM(column_name='id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND LOWER(extra)='auto_increment' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='article_id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='revision_id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='content_hash' AND LOWER(column_type)='char(64)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1 AND SUM(column_name='state' AND LOWER(column_type)='varchar(24)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='model_decision' AND LOWER(column_type)='varchar(16)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='risk_score' AND LOWER(column_type)='decimal(6,5)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='policy_hits_json' AND LOWER(column_type)='json' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='attempt_count' AND LOWER(column_type)='int' AND is_nullable='NO' AND CAST(column_default AS CHAR)='0' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='next_attempt_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='lease_owner' AND LOWER(column_type)='varchar(96)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='lease_until' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='last_error' AND LOWER(column_type)='varchar(500)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='reviewer_id' AND LOWER(column_type)='bigint' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='review_reason' AND LOWER(column_type)='varchar(500)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='reviewed_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='created_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='updated_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='lock_version' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND CAST(column_default AS CHAR)='0' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1,1,0) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article_moderation_job')=1 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND LOWER(engine)='innodb' AND LOWER(table_collation)='utf8mb4_unicode_ci')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_moderation_job_columns' );
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- atomic table create followed by exact column/engine/collation validation: article_moderation_attempt
CREATE TABLE IF NOT EXISTS article_moderation_attempt (
  id BIGINT AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  provider VARCHAR(32) NULL,
  model VARCHAR(96) NULL,
  prompt_version VARCHAR(32) NOT NULL,
  input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  structured_output_json JSON NULL,
  latency_ms BIGINT NOT NULL,
  token_usage_json JSON NULL,
  finish_reason VARCHAR(32) NULL,
  error_code VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SET @ddl = IF((SELECT IF(COUNT(*)=13 AND SUM(column_name='id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND LOWER(extra)='auto_increment' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='job_id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='attempt_no' AND LOWER(column_type)='int' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='provider' AND LOWER(column_type)='varchar(32)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='model' AND LOWER(column_type)='varchar(96)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='prompt_version' AND LOWER(column_type)='varchar(32)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='input_hash' AND LOWER(column_type)='char(64)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1 AND SUM(column_name='structured_output_json' AND LOWER(column_type)='json' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='latency_ms' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='token_usage_json' AND LOWER(column_type)='json' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='finish_reason' AND LOWER(column_type)='varchar(32)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='error_code' AND LOWER(column_type)='varchar(64)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='created_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1,1,0) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article_moderation_attempt')=1 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='article_moderation_attempt' AND LOWER(engine)='innodb' AND LOWER(table_collation)='utf8mb4_unicode_ci')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_moderation_attempt_columns' );
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- atomic table create followed by exact column/engine/collation validation: article_revision_migration_issue
CREATE TABLE IF NOT EXISTS article_revision_migration_issue (
  id BIGINT AUTO_INCREMENT,
  article_id BIGINT NOT NULL,
  issue_code VARCHAR(64) NOT NULL,
  observed_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  details_json JSON NOT NULL,
  detected_at DATETIME(6) NOT NULL,
  resolved_at DATETIME(6) NULL,
  resolution_note VARCHAR(500) NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SET @ddl = IF((SELECT IF(COUNT(*)=8 AND SUM(column_name='id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND LOWER(extra)='auto_increment' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='article_id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='issue_code' AND LOWER(column_type)='varchar(64)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='observed_hash' AND LOWER(column_type)='char(64)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1 AND SUM(column_name='details_json' AND LOWER(column_type)='json' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='detected_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='resolved_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='resolution_note' AND LOWER(column_type)='varchar(500)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1,1,0) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='article_revision_migration_issue')=1 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='article_revision_migration_issue' AND LOWER(engine)='innodb' AND LOWER(table_collation)='utf8mb4_unicode_ci')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_migration_issue_columns' );
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- atomic table create followed by exact column/engine/collation validation: domain_event_outbox
CREATE TABLE IF NOT EXISTS domain_event_outbox (
  id BIGINT AUTO_INCREMENT,
  event_id BINARY(16) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  aggregate_version BIGINT NOT NULL,
  lifecycle_epoch BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_version INT NOT NULL,
  payload_json JSON NOT NULL,
  dedupe_key VARCHAR(190) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  lease_owner VARCHAR(96) NULL,
  lease_until DATETIME(6) NULL,
  last_error VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL,
  published_at DATETIME(6) NULL,
  failed_at DATETIME(6) NULL,
  dead_resolved_at DATETIME(6) NULL,
  dead_resolved_by VARCHAR(96) NULL,
  dead_resolution VARCHAR(32) NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Upgrade the historical 20-column table before validating the 23-column target manifest.
SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND column_name='dead_resolved_at');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND column_name='dead_resolved_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL);
SET @ddl = IF(@object_count=0, 'ALTER TABLE domain_event_outbox ADD COLUMN dead_resolved_at DATETIME(6) NULL AFTER failed_at', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_dead_resolved_at'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND column_name='dead_resolved_by');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND column_name='dead_resolved_by' AND LOWER(column_type)='varchar(96)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci');
SET @ddl = IF(@object_count=0, 'ALTER TABLE domain_event_outbox ADD COLUMN dead_resolved_by VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL AFTER dead_resolved_at', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_dead_resolved_by'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @object_count = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND column_name='dead_resolution');
SET @object_valid = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND column_name='dead_resolution' AND LOWER(column_type)='varchar(32)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci');
SET @ddl = IF(@object_count=0, 'ALTER TABLE domain_event_outbox ADD COLUMN dead_resolution VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL AFTER dead_resolved_by', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_dead_resolution'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;
SET @ddl = IF((SELECT IF(COUNT(*)=23 AND SUM(column_name='id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND LOWER(extra)='auto_increment' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='event_id' AND LOWER(column_type)='binary(16)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='aggregate_type' AND LOWER(column_type)='varchar(64)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='aggregate_id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='aggregate_version' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='lifecycle_epoch' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='event_type' AND LOWER(column_type)='varchar(64)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='payload_version' AND LOWER(column_type)='int' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='payload_json' AND LOWER(column_type)='json' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='dedupe_key' AND LOWER(column_type)='varchar(190)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='occurred_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='state' AND LOWER(column_type)='varchar(16)' AND is_nullable='NO' AND CAST(column_default AS CHAR)='PENDING' AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='retry_count' AND LOWER(column_type)='int' AND is_nullable='NO' AND CAST(column_default AS CHAR)='0' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='next_attempt_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='lease_owner' AND LOWER(column_type)='varchar(96)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='lease_until' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='last_error' AND LOWER(column_type)='varchar(500)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='created_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='published_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='failed_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='dead_resolved_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='dead_resolved_by' AND LOWER(column_type)='varchar(96)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='dead_resolution' AND LOWER(column_type)='varchar(32)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1,1,0) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='domain_event_outbox')=1 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND LOWER(engine)='innodb' AND LOWER(table_collation)='utf8mb4_unicode_ci')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_columns' );
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- atomic table create followed by exact column/engine/collation validation: consumer_inbox
CREATE TABLE IF NOT EXISTS consumer_inbox (
  consumer_name VARCHAR(96) NOT NULL,
  event_id BINARY(16) NOT NULL,
  processed_at DATETIME(6) NOT NULL,
  result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (consumer_name,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SET @ddl = IF((SELECT IF(COUNT(*)=4 AND SUM(column_name='consumer_name' AND LOWER(column_type)='varchar(96)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='event_id' AND LOWER(column_type)='binary(16)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='processed_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='result_hash' AND LOWER(column_type)='char(64)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1,1,0) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='consumer_inbox')=1 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='consumer_inbox' AND LOWER(engine)='innodb' AND LOWER(table_collation)='utf8mb4_unicode_ci')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_consumer_inbox_columns' );
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- atomic table create followed by exact column/engine/collation validation: projection_watermark
CREATE TABLE IF NOT EXISTS projection_watermark (
  consumer_name VARCHAR(96) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  last_applied_version BIGINT NOT NULL DEFAULT 0,
  lifecycle_epoch BIGINT NOT NULL DEFAULT 0,
  tombstone TINYINT(1) NOT NULL DEFAULT 0,
  lease_owner VARCHAR(96) NULL,
  lease_until DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (consumer_name,aggregate_type,aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SET @ddl = IF((SELECT IF(COUNT(*)=9 AND SUM(column_name='consumer_name' AND LOWER(column_type)='varchar(96)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='aggregate_type' AND LOWER(column_type)='varchar(64)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='aggregate_id' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='last_applied_version' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND CAST(column_default AS CHAR)='0' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='lifecycle_epoch' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND CAST(column_default AS CHAR)='0' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='tombstone' AND LOWER(column_type)='tinyint(1)' AND is_nullable='NO' AND CAST(column_default AS CHAR)='0' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='lease_owner' AND LOWER(column_type)='varchar(96)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1 AND SUM(column_name='lease_until' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1 AND SUM(column_name='updated_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1,1,0) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='projection_watermark')=1 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=@schema_name AND table_name='projection_watermark' AND LOWER(engine)='innodb' AND LOWER(table_collation)='utf8mb4_unicode_ci')=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_projection_watermark_columns' );
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- Durable rollout promotion checkpoint. Creation never seeds or advances rollout state.
CREATE TABLE IF NOT EXISTS article_revision_rollout_checkpoint (
  checkpoint_id TINYINT NOT NULL,
  mode VARCHAR(24) NOT NULL,
  schema_generation BIGINT NOT NULL,
  minimum_binary_generation BIGINT NOT NULL,
  required_build_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  backfill_started_at DATETIME(6) NULL,
  verified_build_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  verified_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  verify_report_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  verified_at DATETIME(6) NULL,
  sentinel_build_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  sentinel_report_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  sentinel_verified_at DATETIME(6) NULL,
  cutover_epoch BIGINT NOT NULL DEFAULT 0,
  updated_by VARCHAR(96) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (checkpoint_id),
  CONSTRAINT chk_article_revision_rollout_singleton CHECK (checkpoint_id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- TEST_CHECKPOINT_AFTER_ROLLOUT_CHECKPOINT_CREATE

SET @ddl = IF(
  (SELECT IF(COUNT(*)=17
    AND SUM(column_name='checkpoint_id' AND LOWER(column_type)='tinyint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1
    AND SUM(column_name='mode' AND LOWER(column_type)='varchar(24)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1
    AND SUM(column_name='schema_generation' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1
    AND SUM(column_name='minimum_binary_generation' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1
    AND SUM(column_name='required_build_digest' AND LOWER(column_type)='char(64)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1
    AND SUM(column_name='backfill_started_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1
    AND SUM(column_name='verified_build_digest' AND LOWER(column_type)='char(64)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1
    AND SUM(column_name='verified_fingerprint' AND LOWER(column_type)='char(64)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1
    AND SUM(column_name='verify_report_hash' AND LOWER(column_type)='char(64)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1
    AND SUM(column_name='verified_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1
    AND SUM(column_name='sentinel_build_digest' AND LOWER(column_type)='char(64)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1
    AND SUM(column_name='sentinel_report_hash' AND LOWER(column_type)='char(64)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='ascii' AND LOWER(collation_name)='ascii_bin')=1
    AND SUM(column_name='sentinel_verified_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='YES' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1
    AND SUM(column_name='cutover_epoch' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND CAST(column_default AS CHAR)='0' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1
    AND SUM(column_name='updated_by' AND LOWER(column_type)='varchar(96)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND LOWER(character_set_name)='utf8mb4' AND LOWER(collation_name)='utf8mb4_unicode_ci')=1
    AND SUM(column_name='updated_at' AND LOWER(column_type)='datetime(6)' AND is_nullable='NO' AND column_default IS NULL AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1
    AND SUM(column_name='lock_version' AND LOWER(column_type)='bigint' AND is_nullable='NO' AND CAST(column_default AS CHAR)='0' AND COALESCE(LOWER(extra),'')='' AND character_set_name IS NULL AND collation_name IS NULL)=1,
    1,0)
   FROM information_schema.columns
   WHERE table_schema=@schema_name AND table_name='article_revision_rollout_checkpoint')=1
  AND (SELECT COUNT(*) FROM information_schema.tables
       WHERE table_schema=@schema_name AND table_name='article_revision_rollout_checkpoint'
         AND table_type='BASE TABLE' AND LOWER(engine)='innodb'
         AND LOWER(table_collation)='utf8mb4_unicode_ci')=1,
  'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_rollout_checkpoint_columns');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision_rollout_checkpoint' AND index_name='PRIMARY');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:checkpoint_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision_rollout_checkpoint' AND index_name='PRIMARY');
SET @ddl = IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_rollout_checkpoint_PRIMARY');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

SET @object_valid = (
  SELECT IF(COUNT(*)=1 AND SUM(
    tc.constraint_name='chk_article_revision_rollout_singleton'
    AND tc.enforced='YES'
    AND REPLACE(REPLACE(REPLACE(REPLACE(LOWER(cc.check_clause),'`',''),' ',''),'(',''),')','')='checkpoint_id=1'
  )=1,1,0)
  FROM information_schema.table_constraints tc
  JOIN information_schema.check_constraints cc
    ON cc.constraint_schema=tc.constraint_schema
   AND cc.constraint_name=tc.constraint_name
  WHERE tc.constraint_schema=@schema_name
    AND tc.table_name='article_revision_rollout_checkpoint'
    AND tc.constraint_type='CHECK'
);
SET @ddl = IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_rollout_checkpoint_singleton');
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- All named indexes, including explicit child-FK support indexes.
-- guarded index article_draft.PRIMARY
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_draft' AND index_name='PRIMARY');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:article_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_draft' AND index_name='PRIMARY');
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_draft ADD PRIMARY KEY (article_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_draft_PRIMARY'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_draft.uk_article_draft_owner
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_draft' AND index_name='uk_article_draft_owner');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:article_id,2:user_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_draft' AND index_name='uk_article_draft_owner');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_article_draft_owner ON article_draft(article_id,user_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_draft_uk_article_draft_owner'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_revision.PRIMARY
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision' AND index_name='PRIMARY');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision' AND index_name='PRIMARY');
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_revision ADD PRIMARY KEY (id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_PRIMARY'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_revision.uk_article_revision_no
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision' AND index_name='uk_article_revision_no');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:article_id,2:revision_no' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision' AND index_name='uk_article_revision_no');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_article_revision_no ON article_revision(article_id,revision_no)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_uk_article_revision_no'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_revision.uk_article_revision_identity
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision' AND index_name='uk_article_revision_identity');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:id,2:article_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision' AND index_name='uk_article_revision_identity');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_article_revision_identity ON article_revision(id,article_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_uk_article_revision_identity'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_revision.idx_article_revision_creator
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision' AND index_name='idx_article_revision_creator');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:article_id,2:created_by' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision' AND index_name='idx_article_revision_creator');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_article_revision_creator ON article_revision(article_id,created_by)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_idx_article_revision_creator'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_moderation_job.PRIMARY
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND index_name='PRIMARY');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND index_name='PRIMARY');
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_moderation_job ADD PRIMARY KEY (id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_moderation_job_PRIMARY'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_moderation_job.uk_article_moderation_revision
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND index_name='uk_article_moderation_revision');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:article_id,2:revision_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND index_name='uk_article_moderation_revision');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_article_moderation_revision ON article_moderation_job(article_id,revision_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_moderation_job_uk_article_moderation_revision'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_moderation_job.uk_article_moderation_identity
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND index_name='uk_article_moderation_identity');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:id,2:article_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND index_name='uk_article_moderation_identity');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_article_moderation_identity ON article_moderation_job(id,article_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_moderation_job_uk_article_moderation_identity'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_moderation_job.idx_moderation_revision_fk
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND index_name='idx_moderation_revision_fk');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:revision_id,2:article_id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND index_name='idx_moderation_revision_fk');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_moderation_revision_fk ON article_moderation_job(revision_id,article_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_moderation_job_idx_moderation_revision_fk'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_moderation_job.idx_moderation_queue
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND index_name='idx_moderation_queue');
SET @object_valid = (SELECT IF(COUNT(*)=3 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:state,2:next_attempt_at,3:id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=3,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_job' AND index_name='idx_moderation_queue');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_moderation_queue ON article_moderation_job(state,next_attempt_at,id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_moderation_job_idx_moderation_queue'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_moderation_attempt.PRIMARY
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_attempt' AND index_name='PRIMARY');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_attempt' AND index_name='PRIMARY');
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_moderation_attempt ADD PRIMARY KEY (id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_moderation_attempt_PRIMARY'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_moderation_attempt.uk_moderation_attempt
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_attempt' AND index_name='uk_moderation_attempt');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:job_id,2:attempt_no' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_moderation_attempt' AND index_name='uk_moderation_attempt');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_moderation_attempt ON article_moderation_attempt(job_id,attempt_no)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_moderation_attempt_uk_moderation_attempt'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_revision_migration_issue.PRIMARY
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision_migration_issue' AND index_name='PRIMARY');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision_migration_issue' AND index_name='PRIMARY');
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_revision_migration_issue ADD PRIMARY KEY (id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_migration_issue_PRIMARY'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_revision_migration_issue.uk_revision_migration_issue
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision_migration_issue' AND index_name='uk_revision_migration_issue');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:article_id,2:issue_code' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision_migration_issue' AND index_name='uk_revision_migration_issue');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_revision_migration_issue ON article_revision_migration_issue(article_id,issue_code)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_migration_issue_uk_revision_migration_issue'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_revision_migration_issue.idx_revision_migration_unresolved
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision_migration_issue' AND index_name='idx_revision_migration_unresolved');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:resolved_at,2:article_id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision_migration_issue' AND index_name='idx_revision_migration_unresolved');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_revision_migration_unresolved ON article_revision_migration_issue(resolved_at,article_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_migration_issue_idx_revision_migration_unresolved'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article_revision_migration_issue.idx_revision_migration_retention
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision_migration_issue' AND index_name='idx_revision_migration_retention');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:resolved_at,2:id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article_revision_migration_issue' AND index_name='idx_revision_migration_retention');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_revision_migration_retention ON article_revision_migration_issue(resolved_at,id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_revision_migration_issue_idx_revision_migration_retention'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index domain_event_outbox.PRIMARY
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='PRIMARY');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='PRIMARY');
SET @ddl = IF(@object_count=0, 'ALTER TABLE domain_event_outbox ADD PRIMARY KEY (id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_PRIMARY'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index domain_event_outbox.uk_domain_event_id
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='uk_domain_event_id');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:event_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='uk_domain_event_id');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_domain_event_id ON domain_event_outbox(event_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_uk_domain_event_id'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index domain_event_outbox.uk_domain_event_dedupe
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='uk_domain_event_dedupe');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:dedupe_key' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='uk_domain_event_dedupe');
SET @ddl = IF(@object_count=0, 'CREATE UNIQUE INDEX uk_domain_event_dedupe ON domain_event_outbox(dedupe_key)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_uk_domain_event_dedupe'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index domain_event_outbox.idx_domain_outbox_dispatch
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='idx_domain_outbox_dispatch');
SET @object_valid = (SELECT IF(COUNT(*)=3 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:state,2:next_attempt_at,3:id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=3,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='idx_domain_outbox_dispatch');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_domain_outbox_dispatch ON domain_event_outbox(state,next_attempt_at,id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_idx_domain_outbox_dispatch'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index domain_event_outbox.idx_domain_outbox_recovery
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='idx_domain_outbox_recovery');
SET @object_valid = (SELECT IF(COUNT(*)=3 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:state,2:lease_until,3:id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=3,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='idx_domain_outbox_recovery');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_domain_outbox_recovery ON domain_event_outbox(state,lease_until,id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_idx_domain_outbox_recovery'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index domain_event_outbox.idx_domain_outbox_published_retention
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='idx_domain_outbox_published_retention');
SET @object_valid = (SELECT IF(COUNT(*)=3 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:state,2:published_at,3:id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=3,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='idx_domain_outbox_published_retention');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_domain_outbox_published_retention ON domain_event_outbox(state,published_at,id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_idx_domain_outbox_published_retention'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index domain_event_outbox.idx_domain_outbox_dead_retention
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='idx_domain_outbox_dead_retention');
SET @object_valid = (SELECT IF(COUNT(*)=3 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:state,2:dead_resolved_at,3:id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=3,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='domain_event_outbox' AND index_name='idx_domain_outbox_dead_retention');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_domain_outbox_dead_retention ON domain_event_outbox(state,dead_resolved_at,id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_domain_event_outbox_idx_domain_outbox_dead_retention'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index consumer_inbox.PRIMARY
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='consumer_inbox' AND index_name='PRIMARY');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:consumer_name,2:event_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='consumer_inbox' AND index_name='PRIMARY');
SET @ddl = IF(@object_count=0, 'ALTER TABLE consumer_inbox ADD PRIMARY KEY (consumer_name,event_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_consumer_inbox_PRIMARY'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index consumer_inbox.idx_consumer_inbox_retention
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='consumer_inbox' AND index_name='idx_consumer_inbox_retention');
SET @object_valid = (SELECT IF(COUNT(*)=3 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:processed_at,2:consumer_name,3:event_id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=3,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='consumer_inbox' AND index_name='idx_consumer_inbox_retention');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_consumer_inbox_retention ON consumer_inbox(processed_at,consumer_name,event_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_consumer_inbox_idx_consumer_inbox_retention'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index projection_watermark.PRIMARY
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='projection_watermark' AND index_name='PRIMARY');
SET @object_valid = (SELECT IF(COUNT(*)=3 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:consumer_name,2:aggregate_type,3:aggregate_id' AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=3,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='projection_watermark' AND index_name='PRIMARY');
SET @ddl = IF(@object_count=0, 'ALTER TABLE projection_watermark ADD PRIMARY KEY (consumer_name,aggregate_type,aggregate_id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_projection_watermark_PRIMARY'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index projection_watermark.idx_projection_lease
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='projection_watermark' AND index_name='idx_projection_lease');
SET @object_valid = (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:lease_until' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=1,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='projection_watermark' AND index_name='idx_projection_lease');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_projection_lease ON projection_watermark(lease_until)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_projection_watermark_idx_projection_lease'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- Child foreign keys are installed only after their ordered supporting indexes exist.
-- guarded foreign key fk_article_draft_owner
SET @object_count = (SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_draft_owner');
SET @object_valid = IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_draft_owner' AND table_name='article_draft' AND referenced_table_name='article' AND update_rule='NO ACTION' AND delete_rule='RESTRICT')=1 AND (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(ordinal_position,':',column_name,'>',referenced_column_name) ORDER BY ordinal_position SEPARATOR ','),'')='1:article_id>id,2:user_id>author_id' AND SUM(table_schema=@schema_name AND referenced_table_schema=@schema_name AND table_name='article_draft' AND referenced_table_name='article')=2,1,0) FROM information_schema.key_column_usage WHERE constraint_schema=@schema_name AND constraint_name='fk_article_draft_owner')=1,1,0);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_draft ADD CONSTRAINT fk_article_draft_owner FOREIGN KEY(article_id,user_id) REFERENCES article(id,author_id) ON DELETE RESTRICT', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_fk_article_draft_owner'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded foreign key fk_article_revision_article
SET @object_count = (SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_revision_article');
SET @object_valid = IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_revision_article' AND table_name='article_revision' AND referenced_table_name='article' AND update_rule='NO ACTION' AND delete_rule='RESTRICT')=1 AND (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(ordinal_position,':',column_name,'>',referenced_column_name) ORDER BY ordinal_position SEPARATOR ','),'')='1:article_id>id' AND SUM(table_schema=@schema_name AND referenced_table_schema=@schema_name AND table_name='article_revision' AND referenced_table_name='article')=1,1,0) FROM information_schema.key_column_usage WHERE constraint_schema=@schema_name AND constraint_name='fk_article_revision_article')=1,1,0);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_revision ADD CONSTRAINT fk_article_revision_article FOREIGN KEY(article_id) REFERENCES article(id) ON DELETE RESTRICT', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_fk_article_revision_article'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded foreign key fk_article_revision_creator
SET @object_count = (SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_revision_creator');
SET @object_valid = IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_revision_creator' AND table_name='article_revision' AND referenced_table_name='article' AND update_rule='NO ACTION' AND delete_rule='RESTRICT')=1 AND (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(ordinal_position,':',column_name,'>',referenced_column_name) ORDER BY ordinal_position SEPARATOR ','),'')='1:article_id>id,2:created_by>author_id' AND SUM(table_schema=@schema_name AND referenced_table_schema=@schema_name AND table_name='article_revision' AND referenced_table_name='article')=2,1,0) FROM information_schema.key_column_usage WHERE constraint_schema=@schema_name AND constraint_name='fk_article_revision_creator')=1,1,0);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_revision ADD CONSTRAINT fk_article_revision_creator FOREIGN KEY(article_id,created_by) REFERENCES article(id,author_id) ON DELETE RESTRICT', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_fk_article_revision_creator'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded foreign key fk_moderation_revision
SET @object_count = (SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_moderation_revision');
SET @object_valid = IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_moderation_revision' AND table_name='article_moderation_job' AND referenced_table_name='article_revision' AND update_rule='NO ACTION' AND delete_rule='RESTRICT')=1 AND (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(ordinal_position,':',column_name,'>',referenced_column_name) ORDER BY ordinal_position SEPARATOR ','),'')='1:revision_id>id,2:article_id>article_id' AND SUM(table_schema=@schema_name AND referenced_table_schema=@schema_name AND table_name='article_moderation_job' AND referenced_table_name='article_revision')=2,1,0) FROM information_schema.key_column_usage WHERE constraint_schema=@schema_name AND constraint_name='fk_moderation_revision')=1,1,0);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_moderation_job ADD CONSTRAINT fk_moderation_revision FOREIGN KEY(revision_id,article_id) REFERENCES article_revision(id,article_id) ON DELETE RESTRICT', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_fk_moderation_revision'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded foreign key fk_attempt_job
SET @object_count = (SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_attempt_job');
SET @object_valid = IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_attempt_job' AND table_name='article_moderation_attempt' AND referenced_table_name='article_moderation_job' AND update_rule='NO ACTION' AND delete_rule='RESTRICT')=1 AND (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(ordinal_position,':',column_name,'>',referenced_column_name) ORDER BY ordinal_position SEPARATOR ','),'')='1:job_id>id' AND SUM(table_schema=@schema_name AND referenced_table_schema=@schema_name AND table_name='article_moderation_attempt' AND referenced_table_name='article_moderation_job')=1,1,0) FROM information_schema.key_column_usage WHERE constraint_schema=@schema_name AND constraint_name='fk_attempt_job')=1,1,0);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_moderation_attempt ADD CONSTRAINT fk_attempt_job FOREIGN KEY(job_id) REFERENCES article_moderation_job(id) ON DELETE RESTRICT', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_fk_attempt_job'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded foreign key fk_revision_migration_article
SET @object_count = (SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_revision_migration_article');
SET @object_valid = IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_revision_migration_article' AND table_name='article_revision_migration_issue' AND referenced_table_name='article' AND update_rule='NO ACTION' AND delete_rule='RESTRICT')=1 AND (SELECT IF(COUNT(*)=1 AND COALESCE(GROUP_CONCAT(CONCAT(ordinal_position,':',column_name,'>',referenced_column_name) ORDER BY ordinal_position SEPARATOR ','),'')='1:article_id>id' AND SUM(table_schema=@schema_name AND referenced_table_schema=@schema_name AND table_name='article_revision_migration_issue' AND referenced_table_name='article')=1,1,0) FROM information_schema.key_column_usage WHERE constraint_schema=@schema_name AND constraint_name='fk_revision_migration_article')=1,1,0);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article_revision_migration_issue ADD CONSTRAINT fk_revision_migration_article FOREIGN KEY(article_id) REFERENCES article(id) ON DELETE RESTRICT', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_fk_revision_migration_article'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- TEST_CHECKPOINT_BEFORE_ARTICLE_POINTERS

-- Pointer indexes are named explicitly so InnoDB never creates path-dependent implicit indexes.
-- guarded index article.idx_article_latest_pointer
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article' AND index_name='idx_article_latest_pointer');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:latest_revision_id,2:id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article' AND index_name='idx_article_latest_pointer');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_article_latest_pointer ON article(latest_revision_id,id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_idx_article_latest_pointer'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article.idx_article_pending_pointer
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article' AND index_name='idx_article_pending_pointer');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:pending_revision_id,2:id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article' AND index_name='idx_article_pending_pointer');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_article_pending_pointer ON article(pending_revision_id,id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_idx_article_pending_pointer'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded index article.idx_article_published_pointer
SET @object_count = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article' AND index_name='idx_article_published_pointer');
SET @object_valid = (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(seq_in_index,':',column_name) ORDER BY seq_in_index SEPARATOR ','),'')='1:published_revision_id,2:id' AND SUM(non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND is_visible='YES')=2,1,0) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article' AND index_name='idx_article_published_pointer');
SET @ddl = IF(@object_count=0, 'CREATE INDEX idx_article_published_pointer ON article(published_revision_id,id)', IF(@object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_article_idx_article_published_pointer'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- Same-article pointer constraints are deliberately last.
-- guarded foreign key fk_article_latest_revision
SET @object_count = (SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_latest_revision');
SET @object_valid = IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_latest_revision' AND table_name='article' AND referenced_table_name='article_revision' AND update_rule='NO ACTION' AND delete_rule='RESTRICT')=1 AND (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(ordinal_position,':',column_name,'>',referenced_column_name) ORDER BY ordinal_position SEPARATOR ','),'')='1:latest_revision_id>id,2:id>article_id' AND SUM(table_schema=@schema_name AND referenced_table_schema=@schema_name AND table_name='article' AND referenced_table_name='article_revision')=2,1,0) FROM information_schema.key_column_usage WHERE constraint_schema=@schema_name AND constraint_name='fk_article_latest_revision')=1,1,0);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article ADD CONSTRAINT fk_article_latest_revision FOREIGN KEY(latest_revision_id,id) REFERENCES article_revision(id,article_id) ON DELETE RESTRICT', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_fk_article_latest_revision'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded foreign key fk_article_pending_revision
SET @object_count = (SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_pending_revision');
SET @object_valid = IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_pending_revision' AND table_name='article' AND referenced_table_name='article_revision' AND update_rule='NO ACTION' AND delete_rule='RESTRICT')=1 AND (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(ordinal_position,':',column_name,'>',referenced_column_name) ORDER BY ordinal_position SEPARATOR ','),'')='1:pending_revision_id>id,2:id>article_id' AND SUM(table_schema=@schema_name AND referenced_table_schema=@schema_name AND table_name='article' AND referenced_table_name='article_revision')=2,1,0) FROM information_schema.key_column_usage WHERE constraint_schema=@schema_name AND constraint_name='fk_article_pending_revision')=1,1,0);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article ADD CONSTRAINT fk_article_pending_revision FOREIGN KEY(pending_revision_id,id) REFERENCES article_revision(id,article_id) ON DELETE RESTRICT', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_fk_article_pending_revision'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- guarded foreign key fk_article_published_revision
SET @object_count = (SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_published_revision');
SET @object_valid = IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=@schema_name AND constraint_name='fk_article_published_revision' AND table_name='article' AND referenced_table_name='article_revision' AND update_rule='NO ACTION' AND delete_rule='RESTRICT')=1 AND (SELECT IF(COUNT(*)=2 AND COALESCE(GROUP_CONCAT(CONCAT(ordinal_position,':',column_name,'>',referenced_column_name) ORDER BY ordinal_position SEPARATOR ','),'')='1:published_revision_id>id,2:id>article_id' AND SUM(table_schema=@schema_name AND referenced_table_schema=@schema_name AND table_name='article' AND referenced_table_name='article_revision')=2,1,0) FROM information_schema.key_column_usage WHERE constraint_schema=@schema_name AND constraint_name='fk_article_published_revision')=1,1,0);
SET @ddl = IF(@object_count=0, 'ALTER TABLE article ADD CONSTRAINT fk_article_published_revision FOREIGN KEY(published_revision_id,id) REFERENCES article_revision(id,article_id) ON DELETE RESTRICT', IF(@object_count=1 AND @object_valid=1, 'SELECT 1', 'SELECT SCHEMA_DRIFT_fk_article_published_revision'));
PREPARE stage_b_stmt FROM @ddl;
EXECUTE stage_b_stmt;
DEALLOCATE PREPARE stage_b_stmt;

-- Restore the caller's session setting after a successful run. A failed run must close the dedicated session.
SET SESSION lock_wait_timeout = @stage_b_previous_lock_wait_timeout;

-- End of Stage B Task 1 additive migration.
