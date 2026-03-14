package cumt.zongzuo.community.repository;

import cumt.zongzuo.community.document.ArticleDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * Elasticsearch 的数据访问层
 * 泛型1：绑定的文档实体类 ArticleDoc
 * 泛型2：主键的类型 Long
 */
@Repository
public interface ArticleRepository extends ElasticsearchRepository<ArticleDoc, Long> {
    // 只要继承了 ElasticsearchRepository，Spring Boot 会自动帮你实现基础的增删改查！
}