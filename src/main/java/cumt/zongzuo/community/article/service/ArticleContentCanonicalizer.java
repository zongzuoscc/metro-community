package cumt.zongzuo.community.article.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.model.ArticleContentSnapshot;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class ArticleContentCanonicalizer {

    private static final String HASH_VERSION = "article-content-v1\n";
    private static final Comparator<String> CODE_POINT_ORDER = ArticleContentCanonicalizer::compareCodePoints;

    private final ObjectMapper objectMapper;

    public ArticleContentCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ArticleContentSnapshot canonicalize(String title, String summary, String bodyMarkdown,
                                                String cover, Collection<String> tags) {
        String safeTitle = normalizeLineEndings(emptyIfNull(title));
        String safeSummary = normalizeLineEndings(emptyIfNull(summary));
        String safeBody = normalizeLineEndings(emptyIfNull(bodyMarkdown));
        String safeCover = normalizeLineEndings(emptyIfNull(cover));
        List<String> canonicalTags = canonicalTags(tags);
        String tagsJson = writeTags(canonicalTags);
        String hashMaterial = HASH_VERSION
                + field(safeTitle)
                + field(safeSummary)
                + field(safeBody)
                + field(safeCover)
                + finalField(tagsJson);
        return new ArticleContentSnapshot(safeTitle, safeSummary, safeBody, toPlainText(safeBody), safeCover,
                canonicalTags, tagsJson, sha256(hashMaterial));
    }

    private List<String> canonicalTags(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                distinct.add(trimmed);
            }
        }
        ArrayList<String> sorted = new ArrayList<>(distinct);
        sorted.sort(CODE_POINT_ORDER);
        return List.copyOf(sorted);
    }

    private String writeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("article tags cannot be serialized", exception);
        }
    }

    private static String field(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value + "\n";
    }

    private static String finalField(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String toPlainText(String markdown) {
        return normalizeLineEndings(markdown)
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("[`*_~>|]", "")
                .trim();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
