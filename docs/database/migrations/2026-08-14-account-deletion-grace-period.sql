-- 账号注销采用七天反悔期；账号主键最终保留，只做逻辑删除与隐私脱敏。
-- 每一列都先查询 information_schema，使脚本可在中断后安全重跑。
SET @schema_name = DATABASE();

SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
                      WHERE table_schema=@schema_name AND table_name='sys_user'
                        AND column_name='account_state');
SET @ddl = IF(@column_exists=0,
    "ALTER TABLE sys_user ADD COLUMN account_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PENDING_DELETE/DELETED' AFTER ban_time",
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
                      WHERE table_schema=@schema_name AND table_name='sys_user'
                        AND column_name='deletion_requested_at');
SET @ddl = IF(@column_exists=0,
    'ALTER TABLE sys_user ADD COLUMN deletion_requested_at DATETIME(6) NULL AFTER account_state',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
                      WHERE table_schema=@schema_name AND table_name='sys_user'
                        AND column_name='purge_after');
SET @ddl = IF(@column_exists=0,
    'ALTER TABLE sys_user ADD COLUMN purge_after DATETIME(6) NULL AFTER deletion_requested_at',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.columns
                      WHERE table_schema=@schema_name AND table_name='sys_user'
                        AND column_name='deletion_version');
SET @ddl = IF(@column_exists=0,
    'ALTER TABLE sys_user ADD COLUMN deletion_version BIGINT NOT NULL DEFAULT 0 AFTER purge_after',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.statistics
                     WHERE table_schema=@schema_name AND table_name='sys_user'
                       AND index_name='idx_sys_user_deletion_due');
SET @ddl = IF(@index_exists=0,
    'CREATE INDEX idx_sys_user_deletion_due ON sys_user(account_state,purge_after,id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @check_exists = (SELECT COUNT(*) FROM information_schema.table_constraints
                     WHERE constraint_schema=@schema_name AND table_name='sys_user'
                       AND constraint_name='chk_sys_user_account_state'
                       AND constraint_type='CHECK');
SET @ddl = IF(@check_exists=0,
    "ALTER TABLE sys_user ADD CONSTRAINT chk_sys_user_account_state CHECK (account_state IN ('ACTIVE','PENDING_DELETE','DELETED'))",
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
