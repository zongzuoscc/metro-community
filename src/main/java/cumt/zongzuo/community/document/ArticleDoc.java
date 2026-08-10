package cumt.zongzuo.community.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;

/**
 * 专门存储在 Elasticsearch 中的文章结构
 * indexName = "article" 相当于 MySQL 里的表名为 article
 */
@Data // 使用 Lombok 自动生成 Get/Set
@Document(indexName = "article")
public class ArticleDoc {

    public static final String INDEX_NAME = "article";

    @Id // 对应文章的真实 ID
    private Long id;

    /** Immutable published revision used to build this current-state document. */
    @Field(type = FieldType.Long)
    private Long revisionId;

    /** Canonical SHA-256 identity of the immutable published revision. */
    @Field(type = FieldType.Keyword)
    private String contentHash;

    /** Durable monotonic fence for current-pointer projection effects. */
    @Field(type = FieldType.Long)
    private Long projectionLifecycleEpoch;

    /** Article lock version read from the same current MySQL snapshot. */
    @Field(type = FieldType.Long)
    private Long projectionVersion;

    /** Kept in the same ES document so a delayed write cannot revive a deletion. */
    @Field(type = FieldType.Boolean)
    private Boolean projectionTombstone;

    // 标题：细粒度拆分，粗粒度搜索
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    // 正文：细粒度拆分，粗粒度搜索
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    // 摘要：同样可以参与分词搜索
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String summary;

    // 封面链接：不需要分词，精确匹配即可
    @Field(type = FieldType.Keyword)
    private String cover;

    // 作者 ID：不需要分词，因为是长整型，直接用 Long 类型精确匹配
    @Field(type = FieldType.Long)
    private Long authorId;

    // 各种统计数据，与 MySQL 实体类保持一致 (Integer)
    @Field(type = FieldType.Integer)
    private Integer viewCount;

    @Field(type = FieldType.Integer)
    private Integer likeCount;

    @Field(type = FieldType.Integer)
    private Integer commentCount;

    @Field(type = FieldType.Integer)
    private Integer collectCount;

    // 创建时间
    @Field(type = FieldType.Date, format = {}, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
