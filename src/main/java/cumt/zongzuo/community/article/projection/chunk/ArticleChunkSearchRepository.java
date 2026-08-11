package cumt.zongzuo.community.article.projection.chunk;

import java.util.List;

public interface ArticleChunkSearchRepository {

    List<ArticleChunkSearchHit> searchActive(String query, int topK);
}
