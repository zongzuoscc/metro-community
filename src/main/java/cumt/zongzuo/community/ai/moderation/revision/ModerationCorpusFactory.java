package cumt.zongzuo.community.ai.moderation.revision;

import cumt.zongzuo.community.article.model.ArticleRevision;

import java.util.Objects;

/** Builds the deterministic, field-delimited text reviewed by every shadow moderation run. */
public final class ModerationCorpusFactory {

    private ModerationCorpusFactory() {
    }

    public static String from(ArticleRevision revision) {
        Objects.requireNonNull(revision, "revision");
        StringBuilder corpus = new StringBuilder();
        field(corpus, "TITLE", revision.getTitle());
        field(corpus, "SUMMARY", revision.getSummary());
        field(corpus, "BODY_MARKDOWN", revision.getBodyMarkdown());
        field(corpus, "TAGS_JSON", revision.getTagsJson());
        field(corpus, "COVER", revision.getCover());
        return corpus.toString();
    }

    private static void field(StringBuilder target, String name, String value) {
        String safe = value == null ? "" : value;
        target.append("<<<").append(name).append(" length=").append(safe.length()).append(">>>\n")
                .append(safe).append("\n<<<END_").append(name).append(">>>\n");
    }
}
