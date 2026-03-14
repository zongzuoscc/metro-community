package cumt.zongzuo.community;

import cumt.zongzuo.community.document.ArticleDoc;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.repository.ArticleRepository;
import cumt.zongzuo.community.service.ArticleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.Collectors;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommunityApplicationTests {

	@Autowired
	private ArticleService articleService;

	@Autowired
	private ArticleRepository articleRepository;

	@Test
	void syncArticleToEs() {
		// 1. 从 MySQL 查询所有文章数据 (只查询未被软删除的文章)
		List<Article> articleList = articleService.lambdaQuery()
				.eq(Article::getIsDeleted, 0)
				.list();

		if (articleList.isEmpty()) {
			System.out.println("MySQL 中没有需要同步的文章数据！");
			return;
		}

		// 2. 将 MySQL 的 Article 对象转换为 ES 的 ArticleDoc 对象
		List<ArticleDoc> docList = articleList.stream().map(article -> {
			ArticleDoc doc = new ArticleDoc();
			doc.setId(article.getId());
			doc.setTitle(article.getTitle());
			doc.setContent(article.getContent());
			doc.setSummary(article.getSummary());
			doc.setCover(article.getCover());

			// 修正字段：使用 authorId，并防御空指针
			doc.setAuthorId(article.getAuthorId() != null ? article.getAuthorId() : 0L);

			// 修正类型：保持为 Integer
			doc.setViewCount(article.getViewCount() != null ? article.getViewCount() : 0);
			doc.setLikeCount(article.getLikeCount() != null ? article.getLikeCount() : 0);
			doc.setCommentCount(article.getCommentCount() != null ? article.getCommentCount() : 0);
			doc.setCollectCount(article.getCollectCount() != null ? article.getCollectCount() : 0);

			doc.setCreateTime(article.getCreateTime());
			return doc;
		}).collect(Collectors.toList());

		// 3. 批量保存到 Elasticsearch 中 (这里会自动触发 IK 分词器)
		articleRepository.saveAll(docList);

		System.out.println("🎉 成功同步 " + docList.size() + " 篇文章到 Elasticsearch！");
	}
}