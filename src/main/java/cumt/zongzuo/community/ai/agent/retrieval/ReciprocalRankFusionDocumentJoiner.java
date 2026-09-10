package cumt.zongzuo.community.ai.agent.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 跨 Elasticsearch 与 Milvus 的 RRF，不依赖任一存储实现。 */
final class ReciprocalRankFusionDocumentJoiner implements DocumentJoiner {

    private static final int RRF_CONSTANT = 60;

    @Override
    public List<Document> join(Map<Query, List<List<Document>>> documentsForQuery) {
        Map<String, Accumulator> merged = new LinkedHashMap<>();
        for (List<List<Document>> routes : documentsForQuery.values()) {
            for (List<Document> route : routes) {
                Map<String, RankedDocument> routeRanks = new LinkedHashMap<>();
                for (int index = 0; index < route.size(); index++) {
                    Document document = route.get(index);
                    routeRanks.put(document.getId(), new RankedDocument(document, index + 1));
                }
                for (RankedDocument ranked : routeRanks.values()) {
                    merged.computeIfAbsent(ranked.document().getId(),
                                    ignored -> new Accumulator(ranked.document()))
                            .add(ranked.document(), ranked.rank());
                }
            }
        }
        return merged.values().stream().map(Accumulator::document)
                .sorted(Comparator.comparingDouble(ReciprocalRankFusionDocumentJoiner::score)
                        .reversed().thenComparingLong(document -> Long.parseLong(document.getId())))
                .toList();
    }

    private record RankedDocument(Document document, int rank) {
    }

    private static double score(Document document) {
        return ((Number) document.getMetadata().get("rrfScore")).doubleValue();
    }

    private static final class Accumulator {
        private final String id;
        private final String text;
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private double score;

        private Accumulator(Document document) {
            id = document.getId();
            text = document.getText();
        }

        private void add(Document document, int rank) {
            metadata.putAll(document.getMetadata());
            score += 1D / (RRF_CONSTANT + rank);
        }

        private Document document() {
            metadata.put("rrfScore", score);
            return Document.builder().id(id).text(text).metadata(metadata).score(score).build();
        }
    }
}
