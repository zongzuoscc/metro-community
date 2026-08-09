package cumt.zongzuo.community.ai.provider;

import java.util.List;

public record EmbeddingResult(List<float[]> vectors, String provider, String model) {

    public EmbeddingResult {
        vectors = vectors.stream().map(float[]::clone).toList();
    }

    @Override
    public List<float[]> vectors() {
        return vectors.stream().map(float[]::clone).toList();
    }
}
