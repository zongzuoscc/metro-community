package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.entity.Article;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

public record RecommendationCandidate(
        Article article,
        Set<Source> sources,
        Set<String> tags,
        double tagAffinity,
        double authorAffinity,
        double similarScore,
        double heatScore,
        double freshnessScore,
        double readPenalty,
        double score,
        String reason) {

    public enum Source {
        FOLLOW,
        TAG,
        SIMILAR,
        EXPLORE
    }

    public RecommendationCandidate {
        Objects.requireNonNull(article, "article must not be null");
        sources = Collections.unmodifiableSet(new LinkedHashSet<>(sources));
        tags = Collections.unmodifiableSet(new LinkedHashSet<>(tags));
    }

    public static RecommendationCandidate unranked(
            Article article,
            Set<Source> sources,
            Set<String> tags,
            double tagAffinity,
            double authorAffinity,
            double similarScore,
            double heatScore,
            double freshnessScore,
            double readPenalty) {
        return new RecommendationCandidate(article, sources, tags, tagAffinity, authorAffinity,
                similarScore, heatScore, freshnessScore, readPenalty, 0D, null);
    }

    public Long articleId() {
        return article.getId();
    }

    public Long authorId() {
        return article.getAuthorId();
    }

    RecommendationCandidate withSources(Set<Source> newSources) {
        return new RecommendationCandidate(article, newSources, tags, tagAffinity, authorAffinity,
                similarScore, heatScore, freshnessScore, readPenalty, score, reason);
    }

    RecommendationCandidate withFeatures(Set<String> newTags, double newTagAffinity,
                                         double newAuthorAffinity, double newSimilarScore,
                                         double newHeatScore, double newFreshnessScore,
                                         double newReadPenalty) {
        return unranked(article, sources, newTags, newTagAffinity, newAuthorAffinity,
                newSimilarScore, newHeatScore, newFreshnessScore, newReadPenalty);
    }

    RecommendationCandidate withRanking(double newScore, String newReason) {
        return new RecommendationCandidate(article, sources, tags, tagAffinity, authorAffinity,
                similarScore, heatScore, freshnessScore, readPenalty, newScore, newReason);
    }
}
