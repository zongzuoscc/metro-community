package cumt.zongzuo.community.ai.agent.retrieval;

import cumt.zongzuo.community.article.projection.vector.ArticleVectorRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 把现有 Milvus 查询适配为一次请求内不可变的文档检索器。 */
final class ArticleVectorDocumentRetriever implements DocumentRetriever {

    private final ArticleVectorRepository repository;
    private final String alias;
    private final String model;
    private final int topK;
    private final float[] vector;
    private final long parserGeneration;
    private final String rankKey;

    ArticleVectorDocumentRetriever(ArticleVectorRepository repository, String alias, String model,
                                   int topK, float[] vector, long parserGeneration, String rankKey) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.alias = Objects.requireNonNull(alias, "alias");
        this.model = Objects.requireNonNull(model, "model");
        this.topK = topK;
        this.vector = Objects.requireNonNull(vector, "vector").clone();
        this.parserGeneration = parserGeneration;
        if (!"denseRank".equals(rankKey) && !"hydeRank".equals(rankKey)) {
            throw new IllegalArgumentException("vector rank key is invalid");
        }
        this.rankKey = rankKey;
    }

    @Override
    public List<Document> retrieve(Query query) {
        Objects.requireNonNull(query, "query");
        var hits = repository.searchActive(alias, vector.clone(), topK, model, parserGeneration);
        List<Document> documents = new ArrayList<>(hits.size());
        for (int index = 0; index < hits.size(); index++) {
            var hit = hits.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("articleId", hit.articleId());
            metadata.put("revisionId", hit.revisionId());
            metadata.put(rankKey, index + 1);
            documents.add(Document.builder().id(Long.toString(hit.chunkId()))
                    .text(Long.toString(hit.chunkId())).metadata(metadata).score((double) hit.score()).build());
        }
        return List.copyOf(documents);
    }
}
