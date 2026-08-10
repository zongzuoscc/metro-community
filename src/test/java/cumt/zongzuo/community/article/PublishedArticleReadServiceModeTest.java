package cumt.zongzuo.community.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import cumt.zongzuo.community.article.config.ArticleRevisionModeResolver;
import cumt.zongzuo.community.article.service.PublishedArticleReadService;
import cumt.zongzuo.community.article.service.AuthorArticleReadService;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.ArticleTagMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishedArticleReadServiceModeTest {

    private static final long ARTICLE_ID = 91_001L;

    @ParameterizedTest
    @EnumSource(ArticleRevisionMode.class)
    void publicReadsUseExactlyTheSourceSelectedByTheFrozenMode(ArticleRevisionMode mode) {
        ArticleMapper mapper = mock(ArticleMapper.class);
        ArticleRevisionModeResolver resolver = mock(ArticleRevisionModeResolver.class);
        when(resolver.current()).thenReturn(mode);
        when(mapper.selectLegacyPublicById(ARTICLE_ID)).thenReturn(article("LEGACY"));
        when(mapper.selectPublicById(ARTICLE_ID)).thenReturn(article("POINTER"));
        PublishedArticleReadService reads = new PublishedArticleReadService(
                mapper, resolver, new ObjectMapper());

        Article result = reads.findById(ARTICLE_ID);

        boolean pointerMode = mode == ArticleRevisionMode.POINTER_READ
                || mode == ArticleRevisionMode.CUTOVER;
        assertThat(result.getTitle()).isEqualTo(pointerMode ? "POINTER" : "LEGACY");
        if (pointerMode) {
            verify(mapper).selectPublicById(ARTICLE_ID);
            verify(mapper, never()).selectLegacyPublicById(ARTICLE_ID);
        } else {
            verify(mapper).selectLegacyPublicById(ARTICLE_ID);
            verify(mapper, never()).selectPublicById(ARTICLE_ID);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"POINTER_READ", "CUTOVER"})
    void pointerModesNeverFallBackToLegacyWhenThePointerIsMissing(ArticleRevisionMode mode) {
        ArticleMapper mapper = mock(ArticleMapper.class);
        ArticleRevisionModeResolver resolver = mock(ArticleRevisionModeResolver.class);
        when(resolver.current()).thenReturn(mode);
        when(mapper.selectLegacyPublicById(ARTICLE_ID)).thenReturn(article("LEGACY_LEAK"));
        PublishedArticleReadService reads = new PublishedArticleReadService(
                mapper, resolver, new ObjectMapper());

        assertThat(reads.findById(ARTICLE_ID)).isNull();
        verify(mapper).selectPublicById(ARTICLE_ID);
        verify(mapper, never()).selectLegacyPublicById(ARTICLE_ID);
    }

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"LEGACY", "SHADOW", "VERIFY_FENCE"})
    void preCutoverRecycleReadsRemainOnTheLegacyMirror(ArticleRevisionMode mode) {
        ArticleMapper mapper = mock(ArticleMapper.class);
        ArticleRevisionModeResolver resolver = mock(ArticleRevisionModeResolver.class);
        when(resolver.current()).thenReturn(mode);
        when(mapper.selectOwnerLegacyRecycle(ARTICLE_ID)).thenReturn(java.util.List.of(article("LEGACY")));
        PublishedArticleReadService published = new PublishedArticleReadService(mapper, resolver, new ObjectMapper());
        AuthorArticleReadService reads = new AuthorArticleReadService(
                mapper, mock(ArticleTagMapper.class), resolver, published);

        assertThat(reads.findRecycleBin(ARTICLE_ID)).singleElement()
                .satisfies(value -> assertThat(value.getTitle()).isEqualTo("LEGACY"));
        verify(mapper).selectOwnerLegacyRecycle(ARTICLE_ID);
        verify(mapper, never()).selectOwnerDraftRecycle(ARTICLE_ID);
    }

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"POINTER_READ"})
    void pointerReadBlocksPrivateRecycleReadsDuringTheCutoverFence(ArticleRevisionMode mode) {
        ArticleMapper mapper = mock(ArticleMapper.class);
        ArticleRevisionModeResolver resolver = mock(ArticleRevisionModeResolver.class);
        when(resolver.current()).thenReturn(mode);
        PublishedArticleReadService published = new PublishedArticleReadService(mapper, resolver, new ObjectMapper());
        AuthorArticleReadService reads = new AuthorArticleReadService(
                mapper, mock(ArticleTagMapper.class), resolver, published);

        assertThatThrownBy(() -> reads.findRecycleBin(ARTICLE_ID))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(503));
        verify(mapper, never()).selectOwnerLegacyRecycle(ARTICLE_ID);
        verify(mapper, never()).selectOwnerDraftRecycle(ARTICLE_ID);
    }

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"CUTOVER"})
    void cutoverRecycleReadsOnlyTheOwnersDraft(ArticleRevisionMode mode) {
        ArticleMapper mapper = mock(ArticleMapper.class);
        ArticleRevisionModeResolver resolver = mock(ArticleRevisionModeResolver.class);
        when(resolver.current()).thenReturn(mode);
        when(mapper.selectOwnerDraftRecycle(ARTICLE_ID)).thenReturn(java.util.List.of(article("DRAFT")));
        PublishedArticleReadService published = new PublishedArticleReadService(mapper, resolver, new ObjectMapper());
        AuthorArticleReadService reads = new AuthorArticleReadService(
                mapper, mock(ArticleTagMapper.class), resolver, published);

        assertThat(reads.findRecycleBin(ARTICLE_ID)).singleElement()
                .satisfies(value -> assertThat(value.getTitle()).isEqualTo("DRAFT"));
        verify(mapper).selectOwnerDraftRecycle(ARTICLE_ID);
        verify(mapper, never()).selectOwnerLegacyRecycle(ARTICLE_ID);
    }

    @ParameterizedTest
    @EnumSource(value = ArticleRevisionMode.class, names = {"LEGACY", "SHADOW", "VERIFY_FENCE"})
    void preCutoverOwnerEditHydratesLegacyTags(ArticleRevisionMode mode) {
        ArticleMapper mapper = mock(ArticleMapper.class);
        ArticleTagMapper articleTagMapper = mock(ArticleTagMapper.class);
        ArticleRevisionModeResolver resolver = mock(ArticleRevisionModeResolver.class);
        when(resolver.current()).thenReturn(mode);
        when(mapper.selectOwnerLegacyById(ARTICLE_ID, ARTICLE_ID)).thenReturn(article("LEGACY"));
        when(articleTagMapper.selectTagNamesByArticleId(ARTICLE_ID))
                .thenReturn(java.util.List.of("legacy-tag"));
        PublishedArticleReadService published = new PublishedArticleReadService(mapper, resolver, new ObjectMapper());
        AuthorArticleReadService reads = new AuthorArticleReadService(
                mapper, articleTagMapper, resolver, published);

        assertThat(reads.findForEdit(ARTICLE_ID, ARTICLE_ID).getTagList())
                .containsExactly("legacy-tag");
        verify(articleTagMapper).selectTagNamesByArticleId(ARTICLE_ID);
        verify(mapper, never()).selectOwnerDraftById(ARTICLE_ID, ARTICLE_ID);
    }

    private static Article article(String title) {
        Article article = new Article();
        article.setId(ARTICLE_ID);
        article.setTitle(title);
        return article;
    }
}
