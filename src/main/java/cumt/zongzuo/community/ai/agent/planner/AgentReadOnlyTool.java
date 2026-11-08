package cumt.zongzuo.community.ai.agent.planner;

/**
 * ReAct 的封闭工具白名单；保留既有类型名以兼容检索调用方。
 *
 * <p>这里故意使用封闭枚举，而不是让模型返回类名、URL 或脚本。这样即使模型输出了
 * {@code DELETE_ARTICLE} 一类越权指令，也只会被拒绝，不可能进入业务执行层。</p>
 */
public enum AgentReadOnlyTool {
    /** 站内公开文章的 BM25、Dense 与 HyDE 混合检索。 */
    COMMUNITY_ARTICLES,
    /** 当前用户主动授权且仍为 ACTIVE 的长期记忆。 */
    LONG_TERM_MEMORY,
    /** 当前用户唯一主对话的历史消息与滚动摘要。 */
    CONVERSATION_HISTORY,
    /** 用户已开启联网时，由后端统一网关执行的外部搜索。 */
    WEB_SEARCH,
    /** AgeMem 长期记忆新增、版本化修改、删除与语义召回。 */
    ADD, UPDATE, DELETE, RETRIEVE,
    /** 只改变本轮有效工作上下文，不删除聊天原文。 */
    SUMMARY, FILTER
}
