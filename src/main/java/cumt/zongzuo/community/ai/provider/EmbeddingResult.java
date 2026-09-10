package cumt.zongzuo.community.ai.provider;

import java.util.List;

public record EmbeddingResult(
        List<float[]> vectors, String provider, String model, long totalTokens) {

    public EmbeddingResult(List<float[]> vectors, String provider, String model) {
        this(vectors, provider, model, 0);
    }

    public EmbeddingResult {
        if (totalTokens < 0) throw new IllegalArgumentException("Negative embedding usage");
        vectors = vectors.stream().map(float[]::clone).toList();
    }

    @Override
    public List<float[]> vectors() {
        return vectors.stream().map(float[]::clone).toList();
    }
}
