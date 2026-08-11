package cumt.zongzuo.community.article.chunk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ArticleChunkId {

    private static final String DOMAIN = "article-chunk-id-v1";

    private ArticleChunkId() {
    }

    public static long from(long revisionId,
                            long parserGeneration,
                            String parserVersion,
                            int chunkNo,
                            String chunkHash) {
        if (revisionId <= 0 || parserGeneration <= 0 || chunkNo < 0) {
            throw new IllegalArgumentException("chunk identity values are out of range");
        }
        if (parserVersion == null || parserVersion.isBlank()) {
            throw new IllegalArgumentException("parserVersion must not be blank");
        }
        if (chunkHash == null || !chunkHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("chunkHash must be lowercase SHA-256 hex");
        }
        byte[] digest = sha256(String.join("\n", DOMAIN, Long.toString(revisionId),
                Long.toString(parserGeneration), parserVersion, Integer.toString(chunkNo), chunkHash));
        long candidate = ByteBuffer.wrap(digest, digest.length - Long.BYTES, Long.BYTES)
                .getLong() & Long.MAX_VALUE;
        return candidate == 0L ? 1L : candidate;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
