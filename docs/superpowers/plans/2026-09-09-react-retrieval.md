# ReAct 检索改造实施记录

目标：执行用户已确认的完整 ReAct 决策循环，并在测试、审查后合并至 master、推送 origin；不改动无关文档。

设计依据：本次对话已确认的“实际观察→动态参数→重复检索→完成”设计。Graph 上下文压缩和持久化保留，ReAct 不获得写权限。使用已有模型路由，整轮冻结 BYOK 配置。无需新增服务、表或依赖。

## Task 1：决策协议与模型适配

- 新建 react 包：AgentToolCall(tool,query)、AgentToolObservation(call,status,content)、AgentReactDecision(call；null 表示完成)、AgentReactDecisionProvider、GatewayReActDecisionProvider。
- 接口 decide(long userId,String requestId,String question,List<AiPromptMessage> recentContext,boolean persistentAllowed,boolean webEnabled,int step,int remaining,List<AgentToolObservation> observations,PreparedUserAiChat route,Instant deadline)，以及 maxRounds()/maxToolCalls()。
- 使用结构化 JSON 动作，不要求或保存内部思维链。仅四类既有工具；查询非空、至多 2000 code point。输入观察是不可信资料而非指令。不得接受任意 URL/SQL/用户身份参数。
- 模型适配使用已有 executor 与已冻结 route；实际序列化提示词以 AgentPromptBudget 核算，裁减旧观察和历史，保留当前问题、最新观察，放不下时明确失败。输出上限 512 token。取消/超时不转为成功。
- TDD 验证 JSON 参数/完成解析、注入额外字段拒绝、实际观察与上下文进入提示词、冻结路由、输入预算和权限。

## Task 2：回答链执行与累积

- GroundedAnswerService 改为新接口。先遵守站内优先及联网开启必须搜索的现有产品规则，随后执行模型决策与观察循环。
- 默认最多 6 次决策、8 次工具尝试，配置硬上限 16/24；这是异常保护，不是固定必须执行的步数。准确重复的工具+规范化查询不再调用；连续两次无新依据停止。错误观察允许模型选择另一工具。
- 每次检查截止时间和线程中断；给最终回答预留时间。模型参数只能影响搜索文字，不能指定用户身份。
- 观察包含真实内容、来源标识和状态，内容限长。重复检索结果按稳定 ID 合并；联网引用统一重新编号，避免多次 W1 冲突。
- 保留近期上下文、临时会话、记忆关闭、用户联网开关、最终引用校验、记忆故障不词法兜底。
- TDD：结果驱动改写/重复工具、权限、错误与重复循环终止、多次引用合并、预算/取消、历史上下文。

## Task 3：接线、验证与交付

- 接线切至新 provider，删除旧 planner 实现/协议及旧特性测试；保留工具枚举及旧 planner 配置键以兼容部署，更新默认值与说明。
- 验证命令：JDK 21 下 `./mvnw -q clean -Dtest='*Test,!*IntegrationTest,!*IT,!RecommendationMetricsTaskTest' test package`，外部服务测试另行说明，不把 Mock 当真实联调。
- 审查完整待提交变更，特别是权限、来源合并、预算、原有记忆改造。
- 只提交本次与先前记忆改造文件；两个原有规划文档及旧未跟踪设计文档不混入。检查 origin/master，再非破坏性合并至 master、复测、普通 push，核对远端提交。
