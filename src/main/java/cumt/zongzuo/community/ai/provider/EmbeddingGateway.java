package cumt.zongzuo.community.ai.provider;

public interface EmbeddingGateway {

    EmbeddingResult embed(EmbeddingCommand command);
}
