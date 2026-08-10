package cumt.zongzuo.community.article;

import cumt.zongzuo.community.article.config.ArticleRevisionMode;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "metro.article.revision-mode=VERIFY_FENCE")
class ArticleRevisionVerifyFenceIntegrationTest extends AbstractArticleRevisionFenceIntegrationTest {
    @Override
    protected ArticleRevisionMode expectedMode() {
        return ArticleRevisionMode.VERIFY_FENCE;
    }
}
