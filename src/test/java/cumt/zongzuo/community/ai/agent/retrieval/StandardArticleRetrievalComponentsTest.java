package cumt.zongzuo.community.ai.agent.retrieval;

import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchHit;
import cumt.zongzuo.community.article.projection.chunk.ArticleChunkSearchRepository;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorHit;
import cumt.zongzuo.community.article.projection.vector.ArticleVectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StandardArticleRetrievalComponentsTest {

    @Test
    void retrieversExposeStableChunkIdentityAndRouteRanksWithoutCredentials() {
        ArticleChunkSearchRepository lexicalRepository = mock(ArticleChunkSearchRepository.class);
        when(lexicalRepository.searchActive("locks", 4)).thenReturn(List.of(
                new ArticleChunkSearchHit(11L, 101L, 1001L, 9F)));
        ArticleVectorRepository vectorRepository = mock(ArticleVectorRepository.class);
        when(vectorRepository.searchActive("chunks", new float[]{1F, 0F}, 4, "bge", 3L))
                .thenReturn(List.of(new ArticleVectorHit(12L, 102L, 1002L, .9F)));

        DocumentRetriever lexical = new ArticleLexicalDocumentRetriever(lexicalRepository, 4);
        DocumentRetriever dense = new ArticleVectorDocumentRetriever(
                vectorRepository, "chunks", "bge", 4, new float[]{1F, 0F}, 3L, "denseRank");

        Document lexicalDocument = lexical.retrieve(new Query("locks")).getFirst();
        Document denseDocument = dense.retrieve(new Query("locks")).getFirst();
        assertThat(lexicalDocument.getId()).isEqualTo("11");
        assertThat(lexicalDocument.getMetadata()).containsEntry("articleId", 101L)
                .containsEntry("revisionId", 1001L).containsEntry("lexicalRank", 1)
                .doesNotContainKeys("credential", "apiKey", "token");
        assertThat(denseDocument.getId()).isEqualTo("12");
        assertThat(denseDocument.getMetadata()).containsEntry("denseRank", 1);
    }

    @Test
    void joinerFusesThreeRoutesWithRrfSixtyAndStableChunkTieBreak() {
        Query query = new Query("locks");
        List<Document> lexical = List.of(candidate(1, "lexicalRank", 1),
                candidate(2, "lexicalRank", 2));
        List<Document> dense = List.of(candidate(2, "denseRank", 1),
                candidate(1, "denseRank", 2));
        List<Document> hyde = List.of(candidate(3, "hydeRank", 1));
        DocumentJoiner joiner = new ReciprocalRankFusionDocumentJoiner();

        List<Document> joined = joiner.join(Map.of(query, List.of(lexical, dense, hyde)));

        assertThat(joined).extracting(Document::getId).containsExactly("1", "2", "3");
        assertThat((double) joined.getFirst().getMetadata().get("rrfScore"))
                .isEqualTo(1D / 61D + 1D / 62D);
        assertThat(joined.getFirst().getMetadata()).containsEntry("lexicalRank", 1)
                .containsEntry("denseRank", 2);
    }

    @Test
    void duplicateChunkInsideOneRouteContributesOnlyItsLastRank() {
        Query query = new Query("locks");
        DocumentJoiner joiner = new ReciprocalRankFusionDocumentJoiner();

        List<Document> joined = joiner.join(Map.of(query, List.of(List.of(
                candidate(1, "lexicalRank", 1), candidate(1, "lexicalRank", 2)))));

        assertThat(joined).singleElement().satisfies(document -> {
            assertThat((double) document.getMetadata().get("rrfScore")).isEqualTo(1D / 62D);
            assertThat(document.getMetadata()).containsEntry("lexicalRank", 2);
        });
    }

    @Test
    void postProcessorDropsStaleChunksAndKeepsAtMostTwoPerArticle() {
        PublishedArticleChunkResolver resolver = mock(PublishedArticleChunkResolver.class);
        when(resolver.resolveCurrent(List.of(11L, 12L, 13L, 99L))).thenReturn(List.of(
                resolved(11, 7, 1), resolved(12, 7, 1), resolved(13, 7, 1)));
        DocumentPostProcessor processor = new PublishedArticleDocumentPostProcessor(resolver, 8);
        List<Document> candidates = List.of(candidate(11, "lexicalRank", 1),
                candidate(12, "lexicalRank", 2), candidate(13, "lexicalRank", 3),
                candidate(99, "lexicalRank", 4));

        List<Document> processed = processor.process(new Query("locks"), candidates);

        assertThat(processed).extracting(Document::getId).containsExactly("11", "12");
        assertThat(processed).allSatisfy(document -> assertThat(document.getText()).isEqualTo("Body"));
    }

    private static Document candidate(long chunkId, String rank, int value) {
        return Document.builder().id(Long.toString(chunkId)).text("")
                .metadata(Map.of(rank, value)).build();
    }

    private static ResolvedArticleChunk resolved(long chunkId, long articleId, long revisionId) {
        return new ResolvedArticleChunk(chunkId, articleId, revisionId, 0, "Title", List.of(),
                "Body", "a".repeat(64), "b".repeat(64));
    }
}
