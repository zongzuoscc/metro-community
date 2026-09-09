# 回答前压缩与持久向量记忆

## 本次实际接入的范围

引入 Spring AI Alibaba Graph 1.1.2.0，Spring AI 依赖仍由现有 1.1.8 BOM 统一管理。
持久主对话现在实际执行：

```text
接纳 turn、获得用户运行租约
  → Graph 加载当前主对话边界和最近完整轮次
  → 未压缩原文接近模型输入预算：模型同时生成内部摘要与带原文证据的记忆
  → MySQL 短事务保存 PENDING 压缩批次、记忆版本、来源、待投影记录
  → Embedding → Milvus 幂等 upsert → STRONG 查询确认可见
  → MySQL 再校验运行租约/主对话边界，批次置为 READY
  → 重新检查剩余上下文 → ReAct 动作/观察循环 → 最终回答 → 原有回答落库事务
```

检索部分已替换为完整 ReAct 动作/观察循环，详见 [ReAct 执行说明](agent-react.md)。
上下文压缩仍由确定性的 Graph 流程负责，不让模型自行决定是否跳过持久化检查。
Graph 管执行顺序，不拥有数据库写权限或用户 API Key；没有使用进程内 MemorySaver 代替聊天数据库。
模型 HTTP 传输仍通过项目的 `PreparedUserAiChat`，保留用户自定义地址的 DNS 固定、SSRF 校验、配额、超时与密钥加密。

## 上下文与记忆规则

- 模型输入预算扣除输出和安全余量后，默认在约 70% 水位触发。不是每轮压缩，也不是固定每 20 轮。
- 每次压缩只覆盖成功的完整旧问答，至少保留最近三个完整轮次；最终提示词也保护这三轮与有效摘要，先裁减检索补充资料。必选内容仍超过所选模型容量时明确失败，不静默退回一轮。当前问题不进入本次历史压缩。
- 压缩批次以实际完整 JSON 提示词检查容量，包括系统词、消息 ID、角色、时间及旧摘要；不能只统计正文 token，否则大量短消息也可能超过模型窗口。
- 旧上下文分批处理，每个请求最多推进 8 个批次，并受整轮 deadline 约束。超大单轮超过当前模型可接受输入时明确失败，不偷偷截断后宣称完整处理。
- 摘要只替换传给模型的工作上下文，`agent_message` 不删除、不改写；前端聊天历史仍展示原文。
- 记忆不再依赖“我喜欢”“我偏好”等词法规则。模型输出必须带当前批次中 USER 消息 ID 和可匹配的连续原文；助手建议不能作为用户事实。
- “日常喜欢蓝色”和“外套偏向黑色”要求保留对象/范围，作为两条事实。当前去重是规范化内容哈希，不宣称已实现可靠的语义冲突自动合并，也不能保证模型不会漏提取。
- 每条有效记忆在 MySQL 保存事实、在独立 Milvus collection 保存向量。查询直接进行用户隔离的向量召回，不再先挑最近 100 条再临时向量化。
- Milvus 使用余弦相似度，默认最低分 0.35，只是可调阈值，需根据实际 Embedding 模型和测试问题校准。
- 向量命中后回 MySQL 校验所有者、当前版本、ACTIVE 状态、到期时间与记忆开关。修改/删除不会只依赖索引刷新。
- 关闭长期记忆：不提取/检索长期事实，但仍能压缩工作上下文。临时对话完全不进入该持久流水线。
- 删除/暂停记忆不等于删除原始聊天，用户事实可能仍存在于授权的聊天原文与工作摘要中。删除记忆不是“彻底忘记全部历史”。

## 失败与一致性

MySQL 和 Milvus **不在同一事务中**。MySQL 先提交可恢复的批次和待投影记录；Milvus 以 `memory_version_id` 为幂等主键写入。
写入成功但数据库状态更新失败时，重试同一版本即可；压缩批次未 READY 前不推进工作上下文水位，也不调用最终回答模型。
重试优先恢复已保存的 PENDING 批次，不重新生成整批记忆。当前恢复由下一次主对话请求驱动，待删除向量另有 Worker 定时清理。
若恢复时长期记忆已关闭，只启用批次中的工作摘要，不要求向量服务；此前保存的事实和待投影记录保留，重新开启记忆后的召回同步会继续处理。

Embedding/Milvus 失败会显式终止本轮，不回退长期记忆词法召回。文章 BM25 与旧历史检索不受这次删除影响。
网络调用不持有数据库连接或行锁；记忆管理与压缩事务统一会话优先的锁顺序，事务/语句有超时。
注销账号时会擦除新摘要文本，关闭记忆后待删除向量也能由 Worker 清理。

## 升级已有数据库

1. 停止旧 Agent/Worker 写者，备份数据库。不能让新旧记忆写入路径同时运行。
2. 执行 `docs/database/migrations/2026-09-09-agent-context-compaction.sql`。Backend 的账号注销逻辑也用到新表，因此应先迁移再启动三个角色。
3. 使用 `.env.example` 中的新配置：

   ```dotenv
   METRO_AI_MEMORY_ENABLED=true
   METRO_AI_EMBEDDING_ENABLED=true
   METRO_AI_MEMORY_MILVUS_COLLECTION=metro_user_memories_v1
   METRO_AI_MEMORY_MILVUS_INITIALIZE_SCHEMA=false
   METRO_AI_MEMORY_MILVUS_PAGE_SIZE=32
   ```

   连接地址/凭据复用 `metro.projection.article-chunk-milvus.*`，不会展示到前端。
   现有百炼 Embedding 配置可以继续使用，但必须输出 1024 维；不同维度不能写入此 collection。
4. 首次没有 collection 时，可在具有相应 Milvus 建表权限的受控环境临时设置 `METRO_AI_MEMORY_MILVUS_INITIALIZE_SCHEMA=true`。首次索引操作会创建并校验 schema，随后改回 false。运行账号还需拥有此记忆 collection 的查询、写入、删除权限，不是只拥有文章 collection 权限。
5. 若曾将记忆投影到另一 collection，切换前执行 `docs/database/operations/2026-09-09-requeue-memory-vectors.sql`，重新入队，而不是沿用旧 collection 的 PROJECTED 标记。旧 collection 的留存/清理需要另行处理，不能把切换视为自动删除旧数据。
6. 启动 Backend、Agent、Worker，先用测试账号验证主对话、压缩、同义改写召回、删除与临时会话隔离。不要用真实密钥/敏感身份作为测试记忆。

同名、同维但不同模型的向量不能混用。模型名变化会触发重投影；切换模型须先停旧写者，不能在异模型滚动部署时声称拥有跨库原子 fencing。升级模型权重但保持模型名不变时也应切 collection 并重建。

旧 `METRO_AI_MEMORY_SEMANTIC_ENABLED`、`METRO_AI_MEMORY_SUMMARY_ENABLED`、`METRO_AI_MEMORY_SUMMARY_TURN_THRESHOLD` 已不再控制新流程；可从个人 `.env` 移除。项目不会读取或改写 API Key 明文来完成此次迁移。

## 验证边界

单元测试覆盖真实 Graph 节点顺序、预算与近期保留、压缩批次恢复、来源校验、向量可见性协议、失败不降级、版本/用户隔离、记忆管理锁序和临时模式。
Mock SDK/数据库测试不等同于真实 MySQL/Milvus 联调。实际服务验证需要本地 Docker 及配置的模型服务可用。

2026-09-09 与 ReAct 合并验证：Java 21 下执行 `./mvnw -q clean -Dtest='*Test,!*IntegrationTest,!*IT,!RecommendationMetricsTaskTest' test package`，68 个测试类、370 项测试通过，三个角色 Jar 构建成功。集成测试及依赖外部环境的 RecommendationMetricsTaskTest 未运行；本机 Docker daemon 当时不可用，未执行数据库迁移或真实模型联调。
