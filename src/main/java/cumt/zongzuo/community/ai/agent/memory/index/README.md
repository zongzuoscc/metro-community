# 长期记忆异步投影与删除收敛

记忆正文和不可变版本在 MySQL 短事务中提交，同一事务写入 `PENDING` 投影。
用户回答不会同步补齐积压索引；`recall` 仅为当前查询生成向量并检索已投影版本。
`indexStatus` 返回 `PENDING` 时，检索未命中不等于没有记忆；当前 turn 刚写入的正文
由调用方直接纳入工作上下文，不要求向量立即可见。Embedding 或 Milvus 查询失败
仍明确报错，不暗中降级为词法检索。

Worker 的 `AgentMemoryVectorProjectionTask` 默认每 5 秒读取至多 32 条当前有效、
未过期、记忆已开启的待投影版本（包含 `FAILED` 重试和模型变化后的重建）。
每轮共享 30 秒预算，网络操作不持有 SQL 事务；向量写入并确认可见后，再沿用
所有者、当前版本、启用设置和 `lock_version` CAS 标记 `PROJECTED`。
扫描游标在每批尝试前推进，失败后继续其他用户，扫描到尾回绕；进程重启从零重扫
仍持久化的待办，不依赖内存消息。多 Worker 可能重复计算，向量按版本 ID 幂等写入，
不承诺跨崩溃 exactly-once。大量积压或 Provider 持续失败会延长可检索时延。

可配置 `metro.ai.memory.milvus.projection-delay-ms`、`projection-timeout` 与 `page-size`。
分离部署时只有 Worker 启用定时任务，必须运行 Worker 才会处理投影。

## 删除收敛

MySQL 与 Milvus 之间没有分布式原子提交。旧写者可能在另一实例完成删除后才写回向量，
甚至在写入后崩溃、来不及执行异常补偿。因此删除同时使用两条恢复路径：

- Upsert 尝试后的失败会按当前 MySQL 生命周期将该退役版本重新置为 `DELETING`。
  每次重入队均递增 `lock_version`，使其他实例此前取得的删除确认快照失效。
- 后台任务每轮按 `memory_version_id` 游标读取至多 32 个 `DELETED` 退役墓碑，
  通过状态、版本锁及退役条件 CAS 重新入队，再复用强一致删除确认流程。扫描到尾后回绕，
  使没有执行 catch 的崩溃写者及数据库短暂不可用也能在后续周期得到修复。

扫描与 CAS 都绑定事项/版本的当前归属和退役状态；当前有效版本及可恢复的暂停版本不会
仅因旧快照而进入删除队列。巡检不依赖用户的记忆启用设置，也不读取删除正文。

默认每分钟运行一次，单轮共享 30 秒预算。墓碑巡检会重复删除已经不存在的向量，这是
幂等操作，但会增加 MySQL 写入和 Milvus RPC；完整回绕时间随墓碑总量增长，不提供固定
清除时延承诺。最终收敛依赖后台任务持续运行、数据服务恢复可用及墓碑保留，不能在所有
记忆后台进程关闭时保证完成。召回始终额外使用 MySQL 当前版本/状态校验，防止残留被使用。

## 验证

常规测试覆盖异步召回边界、后台批次大小、失败用户公平续扫、截止时间和进程重启恢复。
可选真实服务测试使用以下显式环境变量，默认不连接外部服务：

- `AGEMEM_LIVE_MILVUS_URI` / `AGEMEM_LIVE_MILVUS_USER` / `AGEMEM_LIVE_MILVUS_PASSWORD`
- `AGEMEM_MYSQL_URL` / `AGEMEM_MYSQL_USER` / `AGEMEM_MYSQL_PASSWORD`

`AgentMemoryMilvusLiveTest` 验证真实 SDK 协议、所有者/模型隔离与强可见删除；
`AgentMemoryProjectionLiveTest` 进一步使用真实 MySQL/MyBatis、写入服务和后台投影任务，
验证提交、待索引、版本切换和删除的完整链路。测试每次创建随机专用 Milvus Collection
并在结束时删除；MySQL 必须为本项目专用的 `agemem_test_*` 或 `agemem_verify_*` schema，
测试会清空该 schema 的测试表。不要指向业务数据库，也不要把凭据写入命令历史或报告。
这两项使用固定测试向量，属于数据库与索引链路验证，不代表真实模型的语义召回质量。
