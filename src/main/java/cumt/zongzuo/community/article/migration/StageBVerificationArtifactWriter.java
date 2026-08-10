package cumt.zongzuo.community.article.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.rollout.ArticleRevisionBuildIdentity;
import cumt.zongzuo.community.article.rollout.StageBVerificationRun;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public class StageBVerificationArtifactWriter {

    private static final int MAXIMUM_ARTIFACT_BYTES = 8 * 1024 * 1024;

    private final StageBMigrationProperties properties;
    private final ObjectMapper objectMapper;

    public StageBVerificationArtifactWriter(StageBMigrationProperties properties,
                                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public StageBVerificationArtifact write(StageBMigrationReport report,
                                            StageBVerificationRun run,
                                            ArticleRevisionBuildIdentity identity,
                                            String operatorIdentity) {
        Path output = requiredOutputPath();
        StageBVerificationArtifact artifact = new StageBVerificationArtifact(
                1, report, identity, run, operatorIdentity,
                Math.addExact(run.checkpointVersion(), 1),
                StageBMigrationReportHasher.hash(report));
        byte[] bytes;
        try {
            bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(artifact);
        }
        catch (IOException serializationFailure) {
            throw new IllegalStateException("verification artifact cannot be serialized",
                    serializationFailure);
        }
        if (bytes.length < 1 || bytes.length > MAXIMUM_ARTIFACT_BYTES) {
            throw new IllegalStateException("verification artifact size is outside 1.."
                    + MAXIMUM_ARTIFACT_BYTES + " bytes");
        }
        createOwnerOnly(output, bytes);
        return artifact;
    }

    private Path requiredOutputPath() {
        String configured = properties.getVerificationReportPath();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "metro.migration.stage-b.verification-report-path is required for VERIFY");
        }
        Path output = Path.of(configured).normalize();
        if (!output.isAbsolute()) {
            throw new IllegalStateException("verification report path must be absolute");
        }
        Path parent = output.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "verification report parent must be a real directory");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("verification report path already exists");
        }
        return output;
    }

    private static void createOwnerOnly(Path output, byte[] bytes) {
        Set<StandardOpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        FileAttribute<?> permissions = PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------"));
        try (SeekableByteChannel channel = open(output, options, permissions)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
        catch (FileAlreadyExistsException conflict) {
            throw new IllegalStateException("verification report path already exists", conflict);
        }
        catch (IOException failure) {
            throw new IllegalStateException("verification artifact cannot be created", failure);
        }
    }

    private static SeekableByteChannel open(Path output, Set<StandardOpenOption> options,
                                            FileAttribute<?> permissions) throws IOException {
        try {
            return Files.newByteChannel(output, options, permissions);
        }
        catch (UnsupportedOperationException unsupportedPermissions) {
            SeekableByteChannel channel = Files.newByteChannel(output, options);
            try {
                Files.setPosixFilePermissions(output,
                        PosixFilePermissions.fromString("rw-------"));
            }
            catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystems cannot express the owner-only attribute.
            }
            return channel;
        }
    }
}
