package cumt.zongzuo.community.article.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import cumt.zongzuo.community.article.model.ArticleRevision;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public final class ArticleRevisionIntegrityVerifier {

    private final ArticleContentCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;

    public ArticleRevisionIntegrityVerifier(ArticleContentCanonicalizer canonicalizer,
                                            ObjectMapper objectMapper) {
        this.canonicalizer = canonicalizer;
        this.objectMapper = objectMapper;
    }

    public Optional<VerifiedRevision> verify(ArticleRevision revision) {
        if (revision == null || revision.getContentHash() == null) {
            return Optional.empty();
        }
        try {
            JsonNode tagsNode = objectMapper.readTree(revision.getTagsJson());
            if (tagsNode == null || !tagsNode.isArray()) {
                return Optional.empty();
            }
            List<String> tags = new ArrayList<>();
            for (JsonNode tag : tagsNode) {
                if (!tag.isTextual()) {
                    return Optional.empty();
                }
                tags.add(tag.textValue());
            }
            ArticleContentSnapshot snapshot = canonicalizer.canonicalize(
                    revision.getTitle(), revision.getSummary(), revision.getBodyMarkdown(),
                    revision.getCover(), tags);
            if (!revision.getContentHash().equals(snapshot.contentHash())) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedRevision(List.copyOf(snapshot.tags()), snapshot.contentHash()));
        }
        catch (RuntimeException | java.io.IOException invalidRevision) {
            return Optional.empty();
        }
    }

    public String freshHashOrEmpty(ArticleRevision revision) {
        return verify(revision).map(VerifiedRevision::freshHash).orElse("");
    }

    public record VerifiedRevision(List<String> tags, String freshHash) {
    }
}
