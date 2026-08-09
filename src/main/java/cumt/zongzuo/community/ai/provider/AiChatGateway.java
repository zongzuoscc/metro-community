package cumt.zongzuo.community.ai.provider;

public interface AiChatGateway {

    AiChatResult generate(AiChatCommand command);
}
