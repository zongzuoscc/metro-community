package cumt.zongzuo.community.ai.agent.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 以 MySQL 发布版本为最终授权边界，并限制单篇文章的上下文占用。 */
final class PublishedArticleDocumentPostProcessor implements DocumentPostProcessor {

    private final PublishedArticleChunkResolver resolver;
    private final int contextLimit;

    PublishedArticleDocumentPostProcessor(PublishedArticleChunkResolver resolver, int contextLimit) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.contextLimit = contextLimit;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        Objects.requireNonNull(query, "query");
        List<Long> ids = documents.stream().map(Document::getId).map(Long::parseLong).sorted().toList();
        Map<Long, ResolvedArticleChunk> current = new HashMap<>();
        for (ResolvedArticleChunk chunk : resolver.resolveCurrent(ids)) {
            current.put(chunk.chunkId(), chunk);
        }
        Map<Long, Integer> articleCounts = new HashMap<>();
        List<Document> processed = new ArrayList<>();
        for (Document candidate : documents) {
            ResolvedArticleChunk chunk = current.get(Long.parseLong(candidate.getId()));
            if (chunk == null || articleCounts.getOrDefault(chunk.articleId(), 0) >= 2) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(candidate.getMetadata());
            metadata.put("articleId", chunk.articleId());
            metadata.put("revisionId", chunk.revisionId());
            metadata.put("chunkNo", chunk.chunkNo());
            metadata.put("title", chunk.title());
            metadata.put("headingPath", chunk.headingPath());
            metadata.put("revisionContentHash", chunk.revisionContentHash());
            metadata.put("chunkHash", chunk.chunkHash());
            processed.add(Document.builder().id(Long.toString(chunk.chunkId()))
                    .text(chunk.bodyText()).metadata(metadata).score(candidate.getScore()).build());
            articleCounts.merge(chunk.articleId(), 1, Integer::sum);
            if (processed.size() == contextLimit) {
                break;
            }
        }
        return List.copyOf(processed);
    }
}
