-- Run against an existing metro_community MySQL 8 schema after a backup.
-- Fresh installations use the synchronized definitions in script.sql.
SET @schema_name = DATABASE();
CREATE TABLE IF NOT EXISTS user_article_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  article_id BIGINT NULL,
  target_author_id BIGINT NULL,
  event_type VARCHAR(32) NOT NULL,
  occurred_at DATETIME NOT NULL,
  dedupe_key VARCHAR(160) NOT NULL,
  source VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_user_article_event_dedupe UNIQUE (dedupe_key),
  INDEX idx_user_event_time (user_id, occurred_at DESC),
  INDEX idx_article_event_time (article_id, occurred_at DESC),
  INDEX idx_event_occurred_at (occurred_at, id),
  INDEX idx_user_article_event_at (user_id, article_id, occurred_at DESC, id DESC),
  INDEX idx_user_author_event_at (user_id, target_author_id, occurred_at DESC, id DESC)
) COMMENT='个性化推荐行为事实' CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recommendation_profile_checkpoint (
  user_id BIGINT PRIMARY KEY,
  requested_event_id BIGINT NOT NULL,
  rebuilt_event_id BIGINT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error VARCHAR(500) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_profile_checkpoint_repair (next_attempt_at, user_id)
) COMMENT='推荐画像持久化重建检查点' CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recommendation_event_outbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  article_id BIGINT NULL,
  target_author_id BIGINT NULL,
  event_type VARCHAR(32) NOT NULL,
  occurred_at DATETIME NOT NULL,
  dedupe_key VARCHAR(160) NOT NULL,
  source VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error VARCHAR(500) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  sent_time DATETIME NULL,
  UNIQUE KEY uk_recommendation_outbox_dedupe (dedupe_key),
  INDEX idx_recommendation_outbox_dispatch (status, next_attempt_at, id)
) COMMENT='推荐事件事务 Outbox' CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recommendation_exposure (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  article_id BIGINT NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  source VARCHAR(32) NOT NULL,
  tag_affinity DOUBLE NOT NULL,
  author_affinity DOUBLE NOT NULL,
  similar_score DOUBLE NOT NULL,
  heat_score DOUBLE NOT NULL,
  freshness_score DOUBLE NOT NULL,
  source_follow TINYINT NOT NULL DEFAULT 0,
  source_tag TINYINT NOT NULL DEFAULT 0,
  source_similar TINYINT NOT NULL DEFAULT 0,
  source_explore TINYINT NOT NULL DEFAULT 0,
  baseline_score DOUBLE NULL,
  exposed_at DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_recommendation_exposure (user_id, article_id, session_id),
  INDEX idx_exposure_user_time (user_id, exposed_at DESC),
  INDEX idx_exposure_article_time (article_id, exposed_at DESC),
  INDEX idx_exposure_user_article_at (user_id, article_id, exposed_at DESC, id DESC),
  INDEX idx_exposure_training (exposed_at DESC, id DESC)
) COMMENT='推荐真实曝光和训练特征快照' CHARSET=utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='recommendation_exposure' AND column_name='source_follow') = 0,
  'ALTER TABLE recommendation_exposure ADD COLUMN source_follow TINYINT NOT NULL DEFAULT 0 AFTER freshness_score', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='recommendation_exposure' AND column_name='source_tag') = 0,
  'ALTER TABLE recommendation_exposure ADD COLUMN source_tag TINYINT NOT NULL DEFAULT 0 AFTER source_follow', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='recommendation_exposure' AND column_name='source_similar') = 0,
  'ALTER TABLE recommendation_exposure ADD COLUMN source_similar TINYINT NOT NULL DEFAULT 0 AFTER source_tag', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='recommendation_exposure' AND column_name='source_explore') = 0,
  'ALTER TABLE recommendation_exposure ADD COLUMN source_explore TINYINT NOT NULL DEFAULT 0 AFTER source_similar', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@schema_name AND table_name='recommendation_exposure' AND column_name='baseline_score') = 0,
  'ALTER TABLE recommendation_exposure ADD COLUMN baseline_score DOUBLE NULL AFTER source_explore', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='article' AND index_name='idx_article_recommendation_feed') = 0,
  'CREATE INDEX idx_article_recommendation_feed ON article (status, is_deleted, create_time DESC, id DESC)', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='recommendation_exposure' AND index_name='idx_exposure_training') = 0,
  'CREATE INDEX idx_exposure_training ON recommendation_exposure (exposed_at DESC, id DESC)', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='recommendation_exposure' AND index_name='idx_exposure_user_article_at') = 0,
  'CREATE INDEX idx_exposure_user_article_at ON recommendation_exposure (user_id, article_id, exposed_at DESC, id DESC)', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='user_article_event' AND index_name='idx_event_occurred_at') = 0,
  'CREATE INDEX idx_event_occurred_at ON user_article_event (occurred_at, id)', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='user_article_event' AND index_name='idx_user_article_event_at') = 0,
  'CREATE INDEX idx_user_article_event_at ON user_article_event (user_id, article_id, occurred_at DESC, id DESC)', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='user_article_event' AND index_name='idx_user_author_event_at') = 0,
  'CREATE INDEX idx_user_author_event_at ON user_article_event (user_id, target_author_id, occurred_at DESC, id DESC)', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@schema_name AND table_name='recommendation_profile_checkpoint' AND index_name='idx_profile_checkpoint_repair') = 0,
  'CREATE INDEX idx_profile_checkpoint_repair ON recommendation_profile_checkpoint (next_attempt_at, user_id)', 'SELECT 1');
PREPARE migration_statement FROM @sql; EXECUTE migration_statement; DEALLOCATE PREPARE migration_statement;

INSERT INTO recommendation_profile_checkpoint
  (user_id,requested_event_id,rebuilt_event_id,retry_count,next_attempt_at,last_error,create_time,update_time)
SELECT user_id,MAX(id),0,0,NOW(),NULL,NOW(),NOW()
FROM user_article_event GROUP BY user_id
ON DUPLICATE KEY UPDATE
  next_attempt_at=IF(VALUES(requested_event_id)>requested_event_id,
                     LEAST(next_attempt_at,VALUES(next_attempt_at)),next_attempt_at),
  requested_event_id=GREATEST(requested_event_id,VALUES(requested_event_id)),
  update_time=VALUES(update_time);
