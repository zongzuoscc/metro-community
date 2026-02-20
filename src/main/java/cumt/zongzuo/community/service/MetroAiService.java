package cumt.zongzuo.community.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class MetroAiService {

    private final ChatClient chatClient;

    // 构造器注入 ChatClient.Builder 并初始化
    public MetroAiService(ChatClient.Builder chatClientBuilder) {
        // 在这里设定 AI 的“系统人设” (System Prompt)
        this.chatClient = chatClientBuilder
                .defaultSystem("你叫“Metro AI”，是 Metro 社区的官方智能助手。你说话幽默风趣、热情且专业。你的任务是陪伴社区用户聊天、解答编程问题、以及活跃社区氛围。当用户问你关于 Metro 社区的问题时，你要表现得很自豪。回答尽量精简，不要长篇大论。")
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
}