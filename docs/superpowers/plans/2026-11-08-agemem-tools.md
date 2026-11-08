# AgeMem 工具化实施计划

Spec: ../specs/2026-11-08-agemem-tools-design.md

## 全局约束

在 codex/agemem 隔离 worktree 开发；SDKMAN Java 21 与 Maven；不动用户 .DS_Store、deploy/capacity-test；不打印凭据。中文提交并推送已授权的对应分支，不创建 PR 或强推。多个实现任务共享文件系统，Maven 构建串行。

## Task 1：长期记忆工具存储边界

AgentMemoryToolService 复用既有事务存储，服务端身份与来源、epoch/version CAS、去重、PENDING 投影。负责 memory 包除 index；新增针对性测试及必要来源迁移。

## Task 2：异步索引

移除 recall 热路径同步，复用持久投影表实现有界 Worker 和明确状态；验证失败隔离、旧版本和恢复。负责 memory/index 与对应测试；Mapper 新接口由 Task 1 添加。

## Task 3：六工具与上下文集成

Native ReactAgent + beforeModel hook，统一有效工作上下文、完整消息配对、最终流式消费同一视图；工具 schema、权限、预算、进展与本轮 epoch/read-own-writes。负责 runtime、Grounded、ReActEvidence、Assembler 和配置/测试。

## Task 4：旧流水线迁移

回答前压缩只做必要摘要，禁自动事实提取和同步索引，保留旧批次恢复；针对性回归。

## Task 5：验证与交付

独立审查完整 diff，运行受影响测试与真实依赖联调，记录边界；中文提交推送。最后修改简历 JSON、同步 Markdown、编译并确认一页与视觉排版。
