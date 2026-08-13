-- Agent 主对话联网偏好与单次 turn 冻结事实。可在旧 Stage D 数据库上重复执行。

SET @conversation_web_search_count = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='agent_conversation'
      AND column_name='web_search_enabled'
);
SET @conversation_web_search_sql = IF(@conversation_web_search_count=0,
    'ALTER TABLE agent_conversation ADD COLUMN web_search_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER memory_epoch',
    'SELECT 1');
PREPARE conversation_web_search_stmt FROM @conversation_web_search_sql;
EXECUTE conversation_web_search_stmt;
DEALLOCATE PREPARE conversation_web_search_stmt;

SET @turn_web_search_count = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='agent_turn'
      AND column_name='web_search_enabled'
);
SET @turn_web_search_sql = IF(@turn_web_search_count=0,
    'ALTER TABLE agent_turn ADD COLUMN web_search_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER grounding_mode',
    'SELECT 1');
PREPARE turn_web_search_stmt FROM @turn_web_search_sql;
EXECUTE turn_web_search_stmt;
DEALLOCATE PREPARE turn_web_search_stmt;

SET @conversation_web_search_valid = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='agent_conversation'
      AND column_name='web_search_enabled' AND column_type='tinyint(1)'
      AND is_nullable='NO' AND column_default='1'
);
SET @turn_web_search_valid = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='agent_turn'
      AND column_name='web_search_enabled' AND column_type='tinyint(1)'
      AND is_nullable='NO' AND column_default='1'
);
SET @web_search_manifest_sql = IF(
    @conversation_web_search_valid=1 AND @turn_web_search_valid=1,
    'SELECT 1',
    'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT=''Agent web search schema drift''');
PREPARE web_search_manifest_stmt FROM @web_search_manifest_sql;
EXECUTE web_search_manifest_stmt;
DEALLOCATE PREPARE web_search_manifest_stmt;
