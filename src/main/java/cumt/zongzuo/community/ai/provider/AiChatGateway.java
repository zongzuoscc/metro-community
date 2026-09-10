package cumt.zongzuo.community.ai.provider;

public interface AiChatGateway {

    AiChatResult generate(AiChatCommand command);

    /** 不支持增量的网关必须明确失败，禁止调用 generate 后切片冒充流式。 */
    default AiChatResult stream(AiChatCommand command, AiStreamObserver observer) {
        throw new UnsupportedOperationException("Provider does not support streaming");
    }
}
