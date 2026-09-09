-- 仅在停止全部旧/新向量写者、明确切换记忆 collection 后执行。
-- 不删除记忆正文；撤销旧 collection 的投影认证，让下一次同步重建到新 collection。
-- 不改 DELETING/DELETED，避免让已删除记忆重新激活。
UPDATE agent_memory_projection
SET state='PENDING',embedding_model=NULL,projected_at=NULL,last_error_code=NULL,
    lock_version=lock_version+1
WHERE state IN ('PROJECTED','FAILED');
