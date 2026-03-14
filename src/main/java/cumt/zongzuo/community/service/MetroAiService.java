package cumt.zongzuo.community.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class MetroAiService {

    private final ChatClient chatClient;

    public MetroAiService(ChatClient.Builder chatClientBuilder) {
        // 在这里设定 AI 的“系统人设” (System Prompt)
        this.chatClient = chatClientBuilder
                .defaultSystem("你叫“Metro AI”，是 Metro 社区的官方智能助手。你说话幽默风趣、热情且专业。\n" +
                        "【核心规则】：当用户向你提问寻找相关技术文章或资料时，你必须调用内置的 'searchArticlesTool' 工具去搜索社区文章数据，并结合搜索结果，用你的话总结后推荐给用户（务必附上文章链接）。\n" +
                        "回答尽量精简，排版清晰，不要长篇大论。")
                // 【绝杀代码】：将我们在 AiToolConfig 中定义的 Bean 绑定给当前 AI
                .defaultFunctions("searchArticlesTool")
                .build();
    }

    /**
     * 和 Metro AI 对话的核心方法
     * @param userMessage 用户的提问
     * @return AI 的回答
     */
    public String chatWithMetro(String userMessage) {
        try {
            return chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            e.printStackTrace();
            return "呜呜呜，Metro AI 大脑短路了（API调用失败），请稍后再试呀~";
        }
    }

    public String summarizeArticle(String content) {
        try {
            // 为避免极个别超长文章把 AI 的 Token 撑爆，我们截取前 2500 个字符进行核心提炼
            String safeContent = content.length() > 2500 ? content.substring(0, 2500) : content;

            String prompt = "你是一个专业的AI技术阅读助手。请为以下文章内容生成一段精炼、结构清晰的摘要（建议100字左右），直接输出摘要正文，可以使用Markdown的加粗或列表，但不要任何多余的废话和寒暄。\n\n文章内容：\n" + safeContent;

            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            e.printStackTrace();
            return "抱歉，Metro AI 总结暂时开小差了，请稍后再试呀~";
        }
    }
}