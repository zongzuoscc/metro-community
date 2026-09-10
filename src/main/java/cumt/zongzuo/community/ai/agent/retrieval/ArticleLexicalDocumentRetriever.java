package cumt.zongzuo.community.ai.agent.retrieval;

import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 把现有 Elasticsearch BM25 边界适配为 Spring AI 文档检索器。 */
final class ArticleLexicalDocumentRetriever implements DocumentRetriever {

    private final ArticleChunkSearchRepository repository;
    private final int topK;

    ArticleLexicalDocumentRetriever(ArticleChunkSearchRepository repository, int topK) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.topK = topK;
    }

    @Override
    public List<Document> retrieve(Query query) {
        Objects.requireNonNull(query, "query");
        var hits = repository.searchActive(query.text(), topK);
        List<Document> documents = new ArrayList<>(hits.size());
        for (int index = 0; index < hits.size(); index++) {
            var hit = hits.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("articleId", hit.articleId());
            metadata.put("revisionId", hit.revisionId());
            metadata.put("lexicalRank", index + 1);
            documents.add(Document.builder().id(Long.toString(hit.chunkId()))
                    .text(Long.toString(hit.chunkId())).metadata(metadata).build());
        }
        return List.copyOf(documents);
    }
}
