package cumt.zongzuo.community.recommendation.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import cumt.zongzuo.community.recommendation.config.RecommendationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class RecommendationModelStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecommendationModelStore.class);
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();
    private final Path directory;
    private final ObjectMapper objectMapper;
    private final int maxAgeDays;
    private final FileOperations fileOperations;
    private final ModelReader modelReader;

    @Autowired
    public RecommendationModelStore(RecommendationProperties properties, ObjectMapper objectMapper) {
        this(Path.of(properties.getModelDirectory()), objectMapper, properties.getModelMaxAgeDays());
    }

    public RecommendationModelStore(Path directory, ObjectMapper objectMapper, int maxAgeDays) {
        this(directory, objectMapper, maxAgeDays,
                (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING), defaultReader(objectMapper));
    }

    public RecommendationModelStore(Path directory, ObjectMapper objectMapper, int maxAgeDays,
                                    FileOperations fileOperations) {
        this(directory, objectMapper, maxAgeDays, fileOperations, defaultReader(objectMapper));
    }

    public RecommendationModelStore(Path directory, ObjectMapper objectMapper, int maxAgeDays,
                                    FileOperations fileOperations, ModelReader modelReader) {
        this.directory = directory.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.maxAgeDays = maxAgeDays;
        this.fileOperations = fileOperations;
        this.modelReader = modelReader;
    }

    public ModelPublicationResult publish(RecommendationModel model) {
        if (!JVM_LOCK.tryLock()) return ModelPublicationResult.failed("PUBLICATION_LOCK_UNAVAILABLE");
        try {
            Files.createDirectories(directory);
            Path lockPath = directory.resolve("active-model.lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.tryLock()) {
                if (ignored == null) return ModelPublicationResult.failed("PUBLICATION_LOCK_UNAVAILABLE");
                Path version = directory.resolve("recommendation-model-" + model.version() + "-"
                        + UUID.randomUUID() + ".json");
                writeAtomically(version, model);
                writeAtomically(directory.resolve("active-model.json"), model);
                return ModelPublicationResult.published(model.version());
            }
        } catch (AtomicMoveNotSupportedException exception) {
            return ModelPublicationResult.failed("ATOMIC_MOVE_UNSUPPORTED");
        } catch (java.nio.channels.OverlappingFileLockException exception) {
            return ModelPublicationResult.failed("PUBLICATION_LOCK_UNAVAILABLE");
        } catch (IOException exception) {
            LOGGER.warn("Recommendation model publication failed", exception);
            return ModelPublicationResult.failed("MODEL_STORE_IO_FAILURE");
        } finally {
            JVM_LOCK.unlock();
        }
    }

    public RecommendationModelLoadResult loadActive(Instant now) {
        Path active = directory.resolve("active-model.json");
        try {
            RecommendationModel model = modelReader.read(active);
            if (model == null) {
                return RecommendationModelLoadResult.unavailable(RecommendationModelLoadResult.Status.INVALID);
            }
            if (model.trainedAt().isAfter(now)) return RecommendationModelLoadResult.unavailable(RecommendationModelLoadResult.Status.INVALID);
            if (model.trainedAt().plus(maxAgeDays, ChronoUnit.DAYS).isBefore(now)) {
                return RecommendationModelLoadResult.unavailable(RecommendationModelLoadResult.Status.EXPIRED);
            }
            return RecommendationModelLoadResult.available(model);
        } catch (NoSuchFileException exception) {
            return RecommendationModelLoadResult.unavailable(RecommendationModelLoadResult.Status.ABSENT);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            LOGGER.warn("Ignoring invalid recommendation model at {}", active, exception);
            return RecommendationModelLoadResult.unavailable(RecommendationModelLoadResult.Status.INVALID);
        } catch (IOException exception) {
            LOGGER.warn("Recommendation model file could not be read at {}", active, exception);
            return RecommendationModelLoadResult.unavailable(RecommendationModelLoadResult.Status.IO_FAILURE);
        }
    }

    private static ModelReader defaultReader(ObjectMapper objectMapper) {
        return path -> {
            try (var input = Files.newInputStream(path)) {
                return objectMapper.readValue(input, RecommendationModel.class);
            }
        };
    }

    private void writeAtomically(Path destination, RecommendationModel model) throws IOException {
        Path temporary = Files.createTempFile(directory, ".recommendation-model-", ".tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), model);
            fileOperations.moveAtomically(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record ModelPublicationResult(boolean published, String reason) {
        static ModelPublicationResult published(String version) { return new ModelPublicationResult(true, version); }
        static ModelPublicationResult failed(String reason) { return new ModelPublicationResult(false, reason); }
    }

    @FunctionalInterface
    public interface FileOperations {
        void moveAtomically(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    public interface ModelReader {
        RecommendationModel read(Path path) throws IOException;
    }
}
