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

    /**
     * 【新增】AI 内容安全审核 (支持超长文本分块检测，防止绕过)
     */
    public String auditContent(String title, String content) {
        try {
            int chunkSize = 2000;

            // 1. 如果文章总长度小于 2000，直接一次性审核完毕
            if (content.length() <= chunkSize) {
                return doAudit(title, content);
            }

            // 2. 如果文章超长，启动“分块熔断”检测
            for (int i = 0; i < content.length(); i += chunkSize) {
                // 计算当前块的结束位置
                int end = Math.min(i + chunkSize, content.length());
                String chunk = content.substring(i, end);

                String contextTitle = (i == 0) ? title : "接上文段落"; // 第一段带上原标题，后续带上提示

                // 审核当前块
                String chunkResult = doAudit(contextTitle, chunk);

                // 【熔断机制】：只要有一段被判违规，立刻返回 REJECT，不再浪费资源审核后面的内容
                if (chunkResult.startsWith("REJECT")) {
                    return chunkResult;
                }
            }

            // 3. 所有分块都安全通过
            return "PASS";

        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR";
        }
    }

    /**
     * 抽取出的底层调用大模型方法
     */
    private String doAudit(String title, String textChunk) {
        String prompt = String.format(
                "你现在是Metro社区的首席内容安全审核员。请审查以下内容的标题/上下文和正文段落。\n" +
                        "【审核标准】：绝不能包含色情、暴力、恶意谩骂、政治敏感违规、或明显的垃圾广告引流内容。\n" +
                        "【严格指令】：你只能回复特定格式的文本，不要有任何多余的废话。\n" +
                        "1. 如果内容完全合规，请严格回复：PASS\n" +
                        "2. 如果存在违规嫌疑，请严格回复：REJECT: [一句话说明违规原因]\n\n" +
                        "标题/上下文：%s\n正文段落：%s", title, textChunk);

        return chatClient.prompt().user(prompt).call().content();
    }
}