package cumt.zongzuo.community.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import cumt.zongzuo.community.recommendation.training.RecommendationFeatureVector;
import cumt.zongzuo.community.recommendation.training.RecommendationModel;
import cumt.zongzuo.community.recommendation.training.RecommendationModelStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationModelStoreTest {

    @TempDir
    Path directory;

    @Test
    void atomicallyPublishesAndLoadsOnlyTheActiveModel() {
        RecommendationModelStore store = new RecommendationModelStore(directory, mapper(), 7);
        RecommendationModel model = validModel();

        assertThat(store.publish(model).published()).isTrue();
        assertThat(store.loadActive(Instant.parse("2026-08-09T13:00:00Z")).model())
                .contains(model);
    }

    @Test
    void expiredActiveModelIsNotUsable() {
        RecommendationModelStore store = new RecommendationModelStore(directory, mapper(), 7);
        store.publish(validModel());

        assertThat(store.loadActive(Instant.parse("2026-08-20T12:00:00Z")).model()).isEmpty();
    }

    @Test
    void malformedActiveModelIsInvalidRatherThanAvailable() throws Exception {
        Files.writeString(directory.resolve("active-model.json"), "{not-json");

        assertThat(new RecommendationModelStore(directory, mapper(), 7)
                .loadActive(Instant.parse("2026-08-09T13:00:00Z")).status())
                .isEqualTo(cumt.zongzuo.community.recommendation.training.RecommendationModelLoadResult.Status.INVALID);
    }

    @Test
    void jsonNullActiveModelIsInvalidSchemaRatherThanAnOperationalFailure() throws Exception {
        Files.writeString(directory.resolve("active-model.json"), "null");

        assertThat(new RecommendationModelStore(directory, mapper(), 7)
                .loadActive(Instant.parse("2026-08-09T13:00:00Z")).status())
                .isEqualTo(cumt.zongzuo.community.recommendation.training.RecommendationModelLoadResult.Status.INVALID);
    }

    @Test
    void activeModelIsRetainedWhenAtomicPromotionIsUnavailable() {
        RecommendationModelStore normal = new RecommendationModelStore(directory, mapper(), 7);
        RecommendationModel previous = validModel();
        assertThat(normal.publish(previous).published()).isTrue();
        RecommendationModelStore unsupported = new RecommendationModelStore(directory, mapper(), 7,
                (source, target) -> { throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test"); });

        assertThat(unsupported.publish(new RecommendationModel("new", previous.trainedAt(), previous.featureNames(),
                previous.means(), previous.standardDeviations(), previous.weights(), previous.bias(),
                previous.validationAuc(), previous.baselineAuc())).reason())
                .isEqualTo("ATOMIC_MOVE_UNSUPPORTED");
        assertThat(normal.loadActive(Instant.parse("2026-08-09T13:00:00Z")).model()).contains(previous);
    }

    @Test
    void activeModelIsRetainedWhenOnlyTheSecondActivePromotionFails() {
        RecommendationModelStore normal = new RecommendationModelStore(directory, mapper(), 7);
        RecommendationModel previous = validModel();
        assertThat(normal.publish(previous).published()).isTrue();
        AtomicInteger moves = new AtomicInteger();
        RecommendationModelStore secondMoveFails = new RecommendationModelStore(directory, mapper(), 7,
                (source, target) -> {
                    if (moves.incrementAndGet() == 2) {
                        throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "active promotion");
                    }
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                });
        RecommendationModel replacement = copyWithVersion(previous, "replacement");

        assertThat(secondMoveFails.publish(replacement).reason()).isEqualTo("ATOMIC_MOVE_UNSUPPORTED");
        assertThat(normal.loadActive(Instant.parse("2026-08-09T13:00:00Z")).model()).contains(previous);
    }

    @Test
    void loadReadsOnlyTheActiveFile() throws Exception {
        RecommendationModelStore store = new RecommendationModelStore(directory, mapper(), 7);
        RecommendationModel model = validModel();
        assertThat(store.publish(model).published()).isTrue();
        Files.writeString(directory.resolve("recommendation-model-bad.json"), "{not-json");

        assertThat(store.loadActive(Instant.parse("2026-08-09T13:00:00Z")).model()).contains(model);
    }

    @Test
    void unreadableActivePathIsOperationalIoFailure() throws Exception {
        Files.createDirectory(directory.resolve("active-model.json"));

        assertThat(new RecommendationModelStore(directory, mapper(), 7)
                .loadActive(Instant.parse("2026-08-09T13:00:00Z")).status())
                .isEqualTo(cumt.zongzuo.community.recommendation.training.RecommendationModelLoadResult.Status.IO_FAILURE);
    }

    @Test
    void accessDeniedFromTheActualReadIsOperationalEvenWhenThePathDoesNotExist() throws Exception {
        RecommendationModelStore store = new RecommendationModelStore(directory.resolve("not-created"), mapper(), 7,
                Files::move, path -> { throw new AccessDeniedException(path.toString()); });

        assertThat(store.loadActive(Instant.parse("2026-08-09T13:00:00Z")).status())
                .isEqualTo(cumt.zongzuo.community.recommendation.training.RecommendationModelLoadResult.Status.IO_FAILURE);
    }

    @Test
    void genuinelyMissingActiveModelIsAbsent() {
        assertThat(new RecommendationModelStore(directory, mapper(), 7)
                .loadActive(Instant.parse("2026-08-09T13:00:00Z")).status())
                .isEqualTo(cumt.zongzuo.community.recommendation.training.RecommendationModelLoadResult.Status.ABSENT);
    }

    @Test
    void sameJvmFileLockContentionHasThePublicLockReason() throws Exception {
        RecommendationModelStore store = new RecommendationModelStore(directory, mapper(), 7);
        try (FileChannel channel = FileChannel.open(directory.resolve("active-model.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertThat(store.publish(validModel()).reason()).isEqualTo("PUBLICATION_LOCK_UNAVAILABLE");
        }
    }

    @Test
    void futureAndInvalidSchemaOrNumbersAreInvalid() throws Exception {
        assertInvalidModel(root -> root.put("trainedAt", "2026-08-10T12:00:00Z"));
        assertInvalidModel(root -> root.withArray("featureNames").set(0, root.textNode("wrong")));
        assertInvalidModel(root -> root.withArray("standardDeviations").set(0, root.numberNode(0D)));
        assertInvalidModel(root -> root.withArray("weights").set(0, root.textNode("NaN")));
        assertInvalidModel(root -> root.put("validationAuc", 1.01D));
    }

    private void assertInvalidModel(java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        ObjectMapper mapper = mapper();
        ObjectNode root = mapper.valueToTree(validModel());
        mutation.accept(root);
        Files.writeString(directory.resolve("active-model.json"), mapper.writeValueAsString(root));

        assertThat(new RecommendationModelStore(directory, mapper, 7)
                .loadActive(Instant.parse("2026-08-09T13:00:00Z")).status())
                .isEqualTo(cumt.zongzuo.community.recommendation.training.RecommendationModelLoadResult.Status.INVALID);
    }

    private static RecommendationModel validModel() {
        return new RecommendationModel("20260809T120000Z", Instant.parse("2026-08-09T12:00:00Z"),
                RecommendationFeatureVector.FEATURE_NAMES,
                List.of(0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D),
                List.of(1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D),
                List.of(1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D, 1D), 0D, .75, .6);
    }

    private static RecommendationModel copyWithVersion(RecommendationModel model, String version) {
        return new RecommendationModel(version, model.trainedAt(), model.featureNames(), model.means(),
                model.standardDeviations(), model.weights(), model.bias(), model.validationAuc(), model.baselineAuc());
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
