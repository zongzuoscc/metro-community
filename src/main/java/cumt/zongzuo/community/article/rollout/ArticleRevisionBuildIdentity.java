package cumt.zongzuo.community.article.rollout;

import java.util.Objects;

public record ArticleRevisionBuildIdentity(
        long binaryGeneration,
        long schemaGeneration,
        String buildDigest) {

    public ArticleRevisionBuildIdentity {
        if (binaryGeneration < 0 || schemaGeneration < 0) {
            throw new IllegalArgumentException("build generations must be nonnegative");
        }
        buildDigest = requireDigest(buildDigest, "buildDigest");
    }

    static String requireDigest(String digest, String field) {
        Objects.requireNonNull(digest, field);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hexadecimal characters");
        }
        return digest;
    }
}
