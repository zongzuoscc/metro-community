package cumt.zongzuo.community.ai.agent;

import cumt.zongzuo.community.ai.agent.retrieval.*;
import cumt.zongzuo.community.ai.agent.websearch.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReActEvidenceTest {
    @Test
    void conflictingSourceNumbersAreRejectedBeforeMutatingEvidence() {
        var evidence = new ReActEvidence();
        var result = new AgentWebSearchResult("冲突来源[W1]",List.of(
                new AgentWebSource(1,"甲","https://example.com/a","网站"),
                new AgentWebSource(1,"乙","https://example.com/b","网站")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> evidence.addWeb(result))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(evidence.web().sources()).isEmpty();
    }

    @Test
    void repeatedWebSearchRenumbersMarkersWithoutCrossLinkingSources() {
        var evidence = new ReActEvidence();
        evidence.addWeb(new AgentWebSearchResult("第一份资料[W1]", List.of(
                new AgentWebSource(1,"甲","https://example.com/a","网站"))));
        evidence.addWeb(new AgentWebSearchResult("第二份资料[W1]，引用甲[W2]", List.of(
                new AgentWebSource(1,"乙","https://example.com/b","网站"),
                new AgentWebSource(2,"甲","https://example.com/a","网站"))));
        assertThat(evidence.web().summary()).contains("第一份资料[W1]","第二份资料[W2]，引用甲[W1]");
        assertThat(evidence.web().sources()).extracting(AgentWebSource::url)
                .containsExactly("https://example.com/a","https://example.com/b");
    }

    @Test
    void unknownMarkerFromLaterSearchCannotBindToAnEarlierSource() {
        var evidence = new ReActEvidence();
        evidence.addWeb(new AgentWebSearchResult("甲[W1]",List.of(
                new AgentWebSource(1,"甲","https://example.com/a","网站"))));
        evidence.addWeb(new AgentWebSearchResult("无来源[W1]",List.of()));
        assertThat(evidence.web().summary()).doesNotContain("无来源[W1]");
    }

    @Test
    void repeatedArticlesAccumulateAndReplaceAnOlderRevisionOfTheSameArticle() {
        var evidence = new ReActEvidence();
        evidence.addArticles(result(chunk(1,10,100),chunk(2,20,200)));
        evidence.addArticles(result(chunk(1,10,100),chunk(3,30,300)));
        assertThat(evidence.articles().authorizedChunks()).extracting(ResolvedArticleChunk::chunkId)
                .containsExactly(1L,2L,3L);
        evidence.addArticles(result(chunk(4,10,101)));
        assertThat(evidence.articles().authorizedChunks()).extracting(ResolvedArticleChunk::chunkId)
                .containsExactly(2L,3L,4L);
    }

    private ArticleRetrievalResult result(ResolvedArticleChunk... chunks) {
        return new ArticleRetrievalResult(chunks.length,0,true,false,List.of(chunks),List.of());
    }

    private ResolvedArticleChunk chunk(long id,long article,long revision) {
        return new ResolvedArticleChunk(id,article,revision,0,"标题",List.of(),"文章实际内容","hash","hash");
    }
}
