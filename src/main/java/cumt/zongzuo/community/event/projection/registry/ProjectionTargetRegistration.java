package cumt.zongzuo.community.event.projection.registry;

import java.util.Objects;

public record ProjectionTargetRegistration(
        long id,
        String kind,
        String physicalName,
        String readAlias,
        String schemaFingerprint,
        String modelName,
        String modelDigest,
        int dimension,
        long generation,
        String targetRole,
        String operatorIdentity) {

    public ProjectionTargetRegistration {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(physicalName, "physicalName");
        Objects.requireNonNull(readAlias, "readAlias");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(targetRole, "targetRole");
        Objects.requireNonNull(operatorIdentity, "operatorIdentity");
        if (!schemaFingerprint.matches("[0-9a-f]{64}") || !modelDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("projection target digests must be lowercase SHA-256");
        }
        if (dimension <= 0 || generation <= 0) {
            throw new IllegalArgumentException("projection target dimension and generation must be positive");
        }
    }
}
