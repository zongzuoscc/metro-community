package cumt.zongzuo.community.ai.agent.websearch;

/**
 * 一条经过后端协议与 URL 校验的联网来源。
 *
 * <p>index 与百炼返回的引用编号保持一致；浏览器只能展示这里的标题和链接，不能直接
 * 使用模型正文中可能伪造的 URL。</p>
 */
public record AgentWebSource(int index, String title, String url, String siteName) {
}
