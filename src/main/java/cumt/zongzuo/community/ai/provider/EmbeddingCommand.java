package cumt.zongzuo.community.ai.provider;

import java.util.List;
import java.util.Objects;

public record EmbeddingCommand(AiCapability capability, List<String> inputs) {

    public EmbeddingCommand {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(inputs, "inputs must not be null");
        inputs = List.copyOf(inputs);
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must not be empty");
        }
        if (inputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("inputs must not contain null values");
        }
    }
}
