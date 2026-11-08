-- AgeMem 工具可在同一轮为同一记忆创建多个不可变版本，来源唯一键须绑定版本。
-- 先增加新约束，再移除旧约束；不改来源内容、外键或历史记忆，重复执行安全。
SET @agemem_source_version_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='agent_memory_source'
      AND index_name='uk_agent_memory_source_version_message'
);
SET @agemem_source_version_index_sql = IF(@agemem_source_version_index_exists=0,
    'ALTER TABLE agent_memory_source ADD UNIQUE KEY uk_agent_memory_source_version_message (memory_version_id,source_message_id)',
    'SELECT 1');
PREPARE agemem_source_version_index_stmt FROM @agemem_source_version_index_sql;
EXECUTE agemem_source_version_index_stmt;
DEALLOCATE PREPARE agemem_source_version_index_stmt;

SET @agemem_source_old_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='agent_memory_source'
      AND index_name='uk_agent_memory_source_message'
);
SET @agemem_source_old_index_sql = IF(@agemem_source_old_index_exists>0,
    'ALTER TABLE agent_memory_source DROP INDEX uk_agent_memory_source_message',
    'SELECT 1');
PREPARE agemem_source_old_index_stmt FROM @agemem_source_old_index_sql;
EXECUTE agemem_source_old_index_stmt;
DEALLOCATE PREPARE agemem_source_old_index_stmt;
