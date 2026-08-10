package cumt.zongzuo.community.article;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "metro.article.revision-mode=POINTER_READ")
class ArticleRevisionPointerReadIntegrationTest extends AbstractArticleRevisionFenceIntegrationTest {
    @Override
    protected ArticleRevisionMode expectedMode() {
        return ArticleRevisionMode.POINTER_READ;
    }
}
