package cumt.zongzuo.community.ai.provider;

import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class OllamaEmbeddingGateway implements EmbeddingGateway {

    public static final int REQUIRED_DIMENSIONS = 1024;
    private static final String PROVIDER = "ollama";

    private final OllamaEmbeddingModel embeddingModel;
    private final String model;

    public OllamaEmbeddingGateway(OllamaEmbeddingModel embeddingModel, String model) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    @Override
    public EmbeddingResult embed(EmbeddingCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.capability() != AiCapability.EMBEDDING) {
            throw new AiProviderException(AiProviderErrorReason.AI_DISABLED,
                    "AI capability is disabled");
        }
        try {
            return toResult(embeddingModel.call(new EmbeddingRequest(command.inputs(), null)), command.inputs().size());
        }
        catch (ProviderHttpStatusException error) {
            throw AiProviderException.fromHttpStatus(error);
        }
        catch (ResourceAccessException error) {
            throw AiProviderException.fromTransport(error);
        }
        catch (RestClientException error) {
            throw new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE,
                    "AI provider returned a malformed response", error);
        }
        catch (AiProviderException error) {
            throw error;
        }
        catch (NullPointerException error) {
            throw new AiProviderException(AiProviderErrorReason.EMPTY_RESPONSE,
                    "AI provider returned no embeddings", error);
        }
        catch (RuntimeException error) {
            throw new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE,
                    "AI provider returned a malformed response", error);
        }
    }

    private EmbeddingResult toResult(EmbeddingResponse response, int expectedCount) {
        if (response == null || response.getMetadata() == null
                || response.getResults() == null || response.getResults().isEmpty()) {
            throw new AiProviderException(AiProviderErrorReason.EMPTY_RESPONSE,
                    "AI provider returned no embeddings");
        }
        if (response.getResults().size() != expectedCount) {
            throw malformed("AI provider returned the wrong embedding count");
        }

        List<float[]> vectors = new ArrayList<>(expectedCount);
        for (int index = 0; index < response.getResults().size(); index++) {
            Embedding embedding = response.getResults().get(index);
            if (embedding == null || embedding.getIndex() == null || embedding.getIndex() != index) {
                throw malformed("AI provider returned embeddings out of order");
            }
            float[] vector = embedding.getOutput();
            if (vector == null || vector.length != REQUIRED_DIMENSIONS) {
                throw malformed("AI provider returned an invalid embedding dimension");
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw malformed("AI provider returned a non-finite embedding value");
                }
            }
            vectors.add(vector.clone());
        }
        return new EmbeddingResult(List.copyOf(vectors), PROVIDER, model);
    }

    private static AiProviderException malformed(String message) {
        return new AiProviderException(AiProviderErrorReason.MALFORMED_RESPONSE, message);
    }
}
