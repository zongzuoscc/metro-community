package cumt.zongzuo.community.article.rollout;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.migration.StageBMigrationProperties;
import org.springframework.boot.ApplicationArguments;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

final class StageBRolloutCommandRunner {

    private static final int MAXIMUM_SENTINEL_CONTROL_FILE_BYTES = 16 * 1024;

    private final StageBRolloutCommandProperties properties;
    private final StageBMigrationProperties migrationProperties;
    private final StageBRolloutOperator operator;
    private final ArticleRevisionBuildIdentity buildIdentity;
    private final ObjectMapper objectMapper;

    StageBRolloutCommandRunner(StageBRolloutCommandProperties properties,
                               StageBMigrationProperties migrationProperties,
                               StageBRolloutOperator operator,
                               ArticleRevisionBuildIdentity buildIdentity,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.migrationProperties = migrationProperties;
        this.operator = operator;
        this.buildIdentity = buildIdentity;
        this.objectMapper = objectMapper;
    }

    public void run(ApplicationArguments arguments) throws Exception {
        String operatorIdentity = requiredOperatorIdentity(
                migrationProperties.getOperatorIdentity());
        switch (properties.getAction()) {
            case BOOTSTRAP_LEGACY -> operator.bootstrapLegacy(buildIdentity, operatorIdentity);
            case ADVANCE -> operator.transitionTo(
                    requiredTarget(properties.getTarget()), buildIdentity, operatorIdentity);
            case AUTHORIZE_BUILD -> operator.authorizeBuild(
                    targetBuildIdentity(), buildIdentity, operatorIdentity);
            case BEGIN_SENTINEL -> beginSentinel(operatorIdentity);
            case RECORD_SENTINEL -> recordSentinel(operatorIdentity);
            case EMERGENCY_FENCE -> operator.emergencyFence(buildIdentity, operatorIdentity);
            case NONE -> throw new IllegalStateException(
                    "Stage B rollout operator requires an explicit action");
        }
    }

    private void beginSentinel(String operatorIdentity) throws Exception {
        Path runPath = requiredAbsolutePath(
                properties.getSentinelRunPath(), "sentinel run path");
        if (Files.exists(runPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("sentinel run path already exists");
        }
        Path parent = runPath.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("sentinel run parent must be a real directory");
        }
        StageBPointerSentinelRun run =
                operator.beginPointerSentinel(buildIdentity, operatorIdentity);
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(run);
        if (bytes.length > MAXIMUM_SENTINEL_CONTROL_FILE_BYTES) {
            throw new IllegalStateException("sentinel run file is unexpectedly large");
        }
        Files.write(runPath, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        restrictToOwner(runPath);
    }

    private void recordSentinel(String operatorIdentity) throws Exception {
        Path reportPath = requiredControlledInput(
                properties.getSentinelReportPath(), "sentinel report path");
        StageBPointerSentinelReport report = objectMapper.readValue(
                Files.readAllBytes(reportPath), StageBPointerSentinelReport.class);
        operator.recordPointerSentinelResult(report, buildIdentity, operatorIdentity);
    }

    private ArticleRevisionBuildIdentity targetBuildIdentity() {
        return new ArticleRevisionBuildIdentity(
                properties.getTargetBinaryGeneration(),
                properties.getTargetSchemaGeneration(),
                properties.getTargetBuildDigest());
    }

    private static ArticleRevisionMode requiredTarget(ArticleRevisionMode target) {
        if (target == null) {
            throw new IllegalStateException("Stage B rollout ADVANCE requires a target mode");
        }
        return target;
    }

    private static Path requiredControlledInput(String value, String field) throws Exception {
        Path path = requiredAbsolutePath(value, field);
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(field + " must be a regular non-symlink file");
        }
        long size = Files.size(path);
        if (size < 1 || size > MAXIMUM_SENTINEL_CONTROL_FILE_BYTES) {
            throw new IllegalStateException(field + " must be 1.."
                    + MAXIMUM_SENTINEL_CONTROL_FILE_BYTES + " bytes");
        }
        requireOwnerOnlyPermissions(path, field);
        return path;
    }

    private static Path requiredAbsolutePath(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is required");
        }
        Path path = Path.of(value).normalize();
        if (!path.isAbsolute()) {
            throw new IllegalStateException(field + " must be absolute");
        }
        return path;
    }

    private static void requireOwnerOnlyPermissions(Path path, String field) throws Exception {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(permission -> switch (permission) {
                case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
                     OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
                default -> false;
            })) {
                throw new IllegalStateException(field + " must not be accessible by group/others");
            }
        }
        catch (UnsupportedOperationException ignored) {
            // Absolute/no-symlink/type/size checks still apply on non-POSIX file systems.
        }
    }

    private static void restrictToOwner(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
        catch (UnsupportedOperationException ignored) {
            // Non-POSIX file systems cannot express these permissions.
        }
    }

    private static String requiredOperatorIdentity(String operatorIdentity) {
        if (operatorIdentity == null || operatorIdentity.isBlank()) {
            throw new IllegalStateException(
                    "Stage B rollout operator requires metro.migration.stage-b.operator-identity");
        }
        return operatorIdentity;
    }
}
