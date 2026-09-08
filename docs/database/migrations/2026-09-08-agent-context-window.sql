-- 可重复执行；发布新代码前执行。本迁移不删除消息、摘要或长期记忆。
-- 旧数据无法可靠区分手动清空与自动滚段，因此升级时以当前活动段作为安全起点。
-- 新建后默认从第一段读取；后续自动滚段能跨段连续，只有手动清空才推进边界。
SET @agent_context_missing = (
    SELECT COUNT(*)=0 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='agent_conversation'
      AND column_name='context_start_episode_no'
);
SET @agent_context_ddl = IF(@agent_context_missing,
    'ALTER TABLE agent_conversation ADD COLUMN context_start_episode_no INT NULL DEFAULT NULL',
    'SELECT 1');
PREPARE agent_context_stmt FROM @agent_context_ddl;
EXECUTE agent_context_stmt;
DEALLOCATE PREPARE agent_context_stmt;

-- NULL 是尚未回填的标记，中途失败后重跑也不会把已确认的边界覆盖掉。
UPDATE agent_conversation c
LEFT JOIN agent_episode e ON e.conversation_id=c.id AND e.user_id=c.user_id AND e.state='ACTIVE'
SET c.context_start_episode_no=COALESCE(e.episode_no,1)
WHERE c.context_start_episode_no IS NULL;

ALTER TABLE agent_conversation MODIFY COLUMN context_start_episode_no INT NOT NULL DEFAULT 1
    COMMENT '主动清空后的上下文读取起点，自动滚段不推进';

-- 近期窗口只取某个用户的成功轮次，避免查询随全站 turn 数量增加而退化为大范围扫描。
SET @agent_recent_index_missing = (
    SELECT COUNT(*)=0 FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='agent_turn' AND index_name='idx_agent_turn_recent'
);
SET @agent_recent_index_ddl = IF(@agent_recent_index_missing,
    'ALTER TABLE agent_turn ADD INDEX idx_agent_turn_recent (user_id,state,id)', 'SELECT 1');
PREPARE agent_recent_index_stmt FROM @agent_recent_index_ddl;
EXECUTE agent_recent_index_stmt;
DEALLOCATE PREPARE agent_recent_index_stmt;
