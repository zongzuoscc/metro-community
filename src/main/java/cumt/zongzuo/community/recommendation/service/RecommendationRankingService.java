package cumt.zongzuo.community.recommendation.service;

import cumt.zongzuo.community.recommendation.service.RecommendationCandidate.Source;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RecommendationRankingService {

    private static final double HEAT_SATURATION = 1_000D;

    public List<RecommendationCandidate> rank(Long userId, List<RecommendationCandidate> candidates,
                                              Set<Long> shownArticleIds) {
        Set<Long> shown = shownArticleIds == null ? Set.of() : shownArticleIds;
        return candidates.stream()
                .filter(candidate -> eligible(userId, candidate, shown))
                .map(this::score)
                .sorted(Comparator.comparingDouble(RecommendationCandidate::score).reversed()
                        .thenComparing(RecommendationCandidate::articleId))
                .toList();
    }

    public List<RecommendationCandidate> diversify(List<RecommendationCandidate> rankedCandidates, int limit) {
        if (limit <= 0 || rankedCandidates.isEmpty()) {
            return List.of();
        }
        int targetSize = Math.min(limit, rankedCandidates.size());
        List<RecommendationCandidate> selected = new ArrayList<>(targetSize);
        List<RecommendationCandidate> skipped = new ArrayList<>();
        Map<String, Integer> topTenTagCounts = new HashMap<>();

        for (RecommendationCandidate candidate : rankedCandidates) {
            if (selected.size() >= targetSize) {
                break;
            }
            if (wouldMakeThirdConsecutiveAuthor(selected, candidate)
                    || wouldMakeFifthTopTenTag(selected, candidate, topTenTagCounts)) {
                skipped.add(candidate);
                continue;
            }
            addSelected(selected, candidate, topTenTagCounts);
        }

        if (selected.size() < targetSize) {
            Set<Long> selectedIds = new HashSet<>();
            selected.forEach(candidate -> selectedIds.add(candidate.articleId()));
            for (RecommendationCandidate candidate : skipped) {
                if (selected.size() >= targetSize) {
                    break;
                }
                if (selectedIds.add(candidate.articleId())) {
                    selected.add(candidate);
                }
            }
        }
        return List.copyOf(selected);
    }

    public static double normalizeHeat(long rawHeat) {
        double nonNegativeHeat = Math.max(0D, (double) rawHeat);
        return nonNegativeHeat / (nonNegativeHeat + HEAT_SATURATION);
    }

    private boolean eligible(Long userId, RecommendationCandidate candidate, Set<Long> shownArticleIds) {
        return candidate.articleId() != null
                && candidate.authorId() != null
                && !candidate.authorId().equals(userId)
                && Integer.valueOf(1).equals(candidate.article().getStatus())
                && Integer.valueOf(0).equals(candidate.article().getIsDeleted())
                && !shownArticleIds.contains(candidate.articleId());
    }

    private RecommendationCandidate score(RecommendationCandidate candidate) {
        double score = candidate.tagAffinity() * 3D
                + candidate.authorAffinity() * 2.5D
                + candidate.similarScore() * 2D
                + candidate.heatScore()
                + candidate.freshnessScore()
                - candidate.readPenalty();
        return candidate.withRanking(score, reason(candidate));
    }

    private String reason(RecommendationCandidate candidate) {
        Source winningSource = candidate.sources().stream()
                .max(Comparator.comparingDouble(source -> sourceContribution(candidate, source)))
                .orElse(null);
        if (winningSource == null) {
            return null;
        }
        return switch (winningSource) {
            case FOLLOW -> "来自你关注的作者";
            case TAG -> "因为你常看 " + candidate.tags().stream().findFirst().orElse("相关话题");
            case SIMILAR -> "与你最近阅读的内容相似";
            case EXPLORE -> "社区近期热议";
        };
    }

    private double sourceContribution(RecommendationCandidate candidate, Source source) {
        return switch (source) {
            case FOLLOW -> candidate.authorAffinity() * 2.5D;
            case TAG -> candidate.tagAffinity() * 3D;
            case SIMILAR -> candidate.similarScore() * 2D;
            case EXPLORE -> candidate.heatScore() + candidate.freshnessScore();
        };
    }

    private boolean wouldMakeThirdConsecutiveAuthor(List<RecommendationCandidate> selected,
                                                    RecommendationCandidate candidate) {
        int size = selected.size();
        return size >= 2
                && candidate.authorId().equals(selected.get(size - 1).authorId())
                && candidate.authorId().equals(selected.get(size - 2).authorId());
    }

    private boolean wouldMakeFifthTopTenTag(List<RecommendationCandidate> selected,
                                           RecommendationCandidate candidate,
                                           Map<String, Integer> topTenTagCounts) {
        if (selected.size() >= 10) {
            return false;
        }
        return candidate.tags().stream().anyMatch(tag -> topTenTagCounts.getOrDefault(tag, 0) >= 4);
    }

    private void addSelected(List<RecommendationCandidate> selected, RecommendationCandidate candidate,
                             Map<String, Integer> topTenTagCounts) {
        if (selected.size() < 10) {
            candidate.tags().forEach(tag -> topTenTagCounts.merge(tag, 1, Integer::sum));
        }
        selected.add(candidate);
    }
}
