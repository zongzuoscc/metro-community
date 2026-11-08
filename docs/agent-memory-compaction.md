# AgeMem 工具化记忆与工作上下文

## 接入范围

本实现借鉴 AgeMem 的长短期记忆统一管理机制，使用项目既有 Spring AI Alibaba 1.1.2.0 `ReactAgent` 和官方消息 hook。没有训练强化学习策略，也不宣称复现论文结果。依赖继续使用 Spring Boot 3.5.16、Spring AI 1.1.8、Java 21。

持久对话的流程：

```text
接纳 turn、持有用户运行租约
  → 必要预算压缩（只生成工作摘要，不自动提取长期事实）
  → 先检索站内文章；开启联网时先执行一次公开搜索
  → ReactAgent 自主选择记忆工具和检索工具
      ADD/UPDATE/DELETE → MySQL 正文、不可变版本、来源与 PENDING 投影短事务提交
      RETRIEVE → 查询向量、Milvus 用户隔离召回、MySQL 当前版本复核
      SUMMARY/FILTER → 修改本轮有效工作上下文
  → 最终模型从同一有效视图流式回答
  → 引用校验、回答落库、成功终态

独立 Worker：持久 PENDING/FAILED 投影 → Embedding → Milvus upsert/可见性确认 → PROJECTED
独立删除 Worker：DELETING → Milvus 删除确认 → DELETED
```

正文提交与向量索引完成是两个状态。回答不等待新记忆索引完成；查询本身所需的 embedding 和 Milvus 检索仍是同步操作，失败会明确报错，不退回词法记忆。

## 工具与选择策略

| 工具 | 作用 | 后端边界 |
| --- | --- | --- |
| ADD | 新增未来有用的用户事实 | 原文证据、敏感信息策略、所有者、运行权、记忆开关、内容哈希去重 |
| UPDATE | 用新事实更新已有记忆 | 同上并要求 expectedVersion，生成新不可变版本 |
| DELETE | 删除不再适用的记忆 | 版本与运行权校验，数据库立即失效，向量后台删除 |
| RETRIEVE | 语义召回长期记忆 | owner、ACTIVE、到期时间、当前版本、记忆开关，明确索引状态 |
| SUMMARY | 摘要可压缩的历史片段 | 摘要替换选中工作上下文，保留原始聊天 |
| FILTER | 移除当前任务不需要的片段 | 只改变工作上下文，不删除数据库消息或推进持久历史水位 |

系统提示词说明何时操作，工具 schema 说明参数；最终权限和一致性由服务端执行。模型可以完全不调用记忆工具，不强制每轮“先查、再更新或新增”。相同参数的重复调用不重复执行；没有有效上下文变化或新证据的重复动作不会无限继续。

每次 Agent 写操作返回该事务准确的 beforeEpoch/afterEpoch。当前回答只接纳自己提交的这一次变化；不通过重新读取最新 epoch 吞掉其他页面的人工修改。外部编辑、删除、暂停、关闭记忆继续使使用旧数据的回答取消。

本轮已提交的新内容可直接用于当前任务，不必等待向量索引。更新或删除的旧版本从当前有效视图移除，历史工具回执不能把旧正文重新送入后续请求。

## 上下文与权限

- 官方 `MessagesModelHook.beforeModel` 在工具完整回执形成后替换下一次模型输入；保留工具调用与返回的配对。最终回答和引用验证也使用有效视图，不能重新装回 `prepared` 原集合。
- 当前完整问题、系统规则和近期完整问答受保护。SUMMARY/FILTER 对原始聊天存储无删除作用。
- 预算压缩仍按实际输入预算分批处理，默认约 70% 水位，只覆盖成功的旧完整问答，保留最近三个完整轮次。原文、角色、ID、时间与摘要的序列化开销均计入预算。必选内容仍超限时明确失败。
- `agent_context_compaction` 的 PENDING 批次由后续持久请求恢复，启用摘要不再等待向量索引；旧批次已有记忆投影交给 Worker 恢复。
- 关闭长期记忆后不能自动读写长期事实，短期上下文管理仍可使用。临时对话不访问持久历史或记忆。
- 删除长期记忆不等于抹除授权聊天原文中的全部事实。FILTER 是本轮输入选择，显式历史检索可以再次取回原文。
- 联网参数隔离、模型配额、运行租约、取消、SSE 回放与正文持久化沿用现有业务实现，见 [ReAct 执行说明](agent-react.md)。

## 后台投影与恢复

复用 `agent_memory_projection` 持久状态，无新增通用命令队列。Worker 默认每 5 秒扫描至多 32 条当前有效版本，一批最多 30 秒。按版本游标前进并回绕，失败用户不会永久阻塞后续用户；进程重启从持久状态重新扫描。

Milvus 使用 `memory_version_id` 幂等主键，STRONG 确认后再做数据库 CAS。更新/删除通过 MySQL 当前版本校验立即阻止旧向量召回；迟到向量写入沿用墓碑重入队和删除确认机制。模型名称变更需要停旧写者并重投影，不能宣称跨模型滚动部署的跨库原子 fencing。

完全相同的有效 ADD 可返回 ALREADY_EXISTS；已删除内容不会因重放 ADD 自动复活。内容哈希不等于语义冲突自动解决；turn 恢复会从原问题重新运行，不宣称跨崩溃的任意工具步骤 exactly-once。

## 升级已有数据库

1. 停止旧 Agent/Worker 写者并备份数据库，避免新旧自动写入路径同时运行。
2. 已有版本需具备 `2026-09-09-agent-context-compaction.sql` 的表，再执行 `docs/database/migrations/2026-11-08-agemem-memory-tools.sql`，来源唯一键改为版本维度。新安装使用更新后的 `script.sql`。
3. 配置 `METRO_AI_MEMORY_ENABLED=true`、`METRO_AI_EMBEDDING_ENABLED=true`，部署 Agent 与 Worker。只启动 Agent 不会自动完成后台索引。
4. Milvus 连接沿用 `metro.projection.article-chunk-milvus.*`；记忆使用独立 collection，默认 `metro_user_memories_v1`，1024 维。初次建表可受控开启 `METRO_AI_MEMORY_MILVUS_INITIALIZE_SCHEMA=true`，建好后关闭。
5. 替换 collection 或同名模型权重时显式重建，不能继续信任原 PROJECTED 标记。历史重入队脚本见 `docs/database/operations/2026-09-09-requeue-memory-vectors.sql`。
6. 用测试账号验证新增、更新、删除、跨会话召回、关闭记忆、临时模式和取消。模型必须支持原生 tools 与流式输出，实际成功率和成本需要按部署模型评估。

构建使用本机 SDKMAN Java 21 与 Maven。验证记录见 [本次验收记录](agemem-verification-2026-11-08.md)。
