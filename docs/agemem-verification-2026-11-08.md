# AgeMem 工具化改造验收记录（2026-11-08）

## 已交付范围

沿用 Spring AI Alibaba 1.1.2.0 ReactAgent，通过官方 MessagesModelHook 接入统一有效工作上下文，提供 ADD、UPDATE、DELETE、RETRIEVE、SUMMARY、FILTER。模型自主选择，允许零次记忆操作。

MySQL 正文、版本、真实 USER 原文来源和待投影状态短事务提交；Worker 后台完成 Embedding/Milvus 投影。调用者不等待新记忆索引完成。持久会话保留运行权、用户隔离、版本与精确 epoch；临时会话不访问持久记忆。旧预算压缩只做摘要，原聊天不删除。

本次未进行 AgeMem 强化学习训练或论文指标复现。采用论文思路的工具与调度机制，不代表模型已学得论文训练后的策略。

## 验证结果

| 范围 | 结果 | 实际验证内容 |
| --- | --- | --- |
| 45 类 Agent 与角色拓扑定向回归 | 298 项通过，0 失败、0 跳过 | 工具参数、有效视图、权限、版本、epoch、流式与取消、预算、检索及向量协议 |
| AgentMemoryToolMysqlTest | 5 项通过 | 真实 MySQL/MyBatis，来源索引幂等迁移、同轮多版本、运行 fence、外改 epoch、来源失败整体回滚、待投影 SQL |
| AgentMemoryMilvusLiveTest | 1 项通过 | 真实 Milvus SDK，集合 schema、强可见写入/删除、用户和模型隔离 |
| AgentMemoryProjectionLiveTest | 1 项通过 | 真实 MySQL + Milvus + Worker 的新增/更新/删除生命周期；embedding 使用固定测试向量 |
| AgeMemLiveModelTest | 1 项通过 | 真实 Qwen-plus 工具调用和流式回答、MySQL 来源落库；回答完成时索引仍 PENDING；真实 text-embedding-v4 后台投影及不同问法语义召回 |
| 三角色打包 | 通过 | backend、agent、worker 可执行 JAR；SDKMAN Java 21.0.11-amzn、Maven 3.9.16 |
| 独立静态审查 | 通过 | 来源/权限/事务边界及下述发现的修复均已复核 |

测试复现并修复了这些问题：

- FILTER 掉本轮新记忆后，无关检索把其重新加入有效上下文。
- 48 条检索证据满额时，本轮刚提交的记忆未被保留。
- 工具 schema 和历史片段序列化开销未计入准备预算，导致 SUMMARY/FILTER 尚未执行就超限。
- 多工具请求中的后续写操作因预算跳过，却未在最终输入保留 LIMIT 回执。
- 预览接口加载完整历史页后超限，以及最终组装重新补回被裁减历史。
- 过滤后的公开文章仍被二次联网查询生成节点从旧 observations 读取。

SUMMARY/FILTER 的断言捕获下一次真实框架模型请求及最终回答请求；包括完整问答/工具配对保护与 contextReduced 标志。流式回归验证上游未完成时已有正文增量，成功终态仍由原业务落库链路负责。

## 实测边界

真实模型用例通过生产 GroundedAnswerService、NativeAgentRuntime、记忆服务和向量 Worker 执行；站内文章检索使用测试替身，配额执行器使用测试直接执行器。它不是完整 HTTP 鉴权、SSE 重连或浏览器端到端验收，也不是模型成功率或并发容量基准。没有借本次测试更新历史压测结论。

实测复用社区项目自己的 MySQL 与 Milvus 容器。MySQL 使用独立 agemem_verify_* schema，Milvus 使用随机专用测试 collection 并在 finally 中删除；未在业务库执行本次迁移，未读取或改写业务记忆。

## 复现与部署

先加载本机 SDKMAN，当前命令使用已安装 Java 21，不改变全局默认：

```sh
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 21.0.11-amzn
mvn -Dtest=AgentMemoryToolServiceTest,AgentMemoryManagementLockTest,AgentMemoryRecallServiceTest,AgeMemAgentIntegrationTest,AgentWorkingContextTest,NativeAgentRuntimeTest,NativeServiceBoundaryTest,AgentCompactionGraphTest,AgentContextAssemblerTest,JdbcAgentCompactionStoreTest,AgentMemoryVectorServiceTest,AgentMemoryVectorProjectionTaskTest,AgentMemoryVectorCleanupTaskTest,AgentMemoryVectorContractTest,SdkMemoryVectorRepositoryTest package
```

真实测试默认不启用。调用者需创建专用测试 schema，并通过进程环境传入 AGEMEM_MYSQL_URL/USER/PASSWORD、AGEMEM_LIVE_MILVUS_URI/USER/PASSWORD。真实模型另设 AGEMEM_LIVE_MODEL=true，并使用现有 METRO_AI_PLATFORM_*、METRO_AI_EMBEDDING_MODEL 配置。不要把凭据写进命令参数、提交或测试报告。

已有部署须先完成 [来源唯一键迁移](database/migrations/2026-11-08-agemem-memory-tools.sql)，再启动新 Agent 与 Worker。只更新 Agent 不会自动运行后台索引；本次交付的是代码与验证结果，没有替换正在运行的业务服务。详细升级步骤见 [记忆与上下文说明](agent-memory-compaction.md)。
