package cumt.zongzuo.community.article.projection.vector;

import java.util.Objects;

/**
 * 为本地首次启用 Dense 检索创建并核对文章 Collection。
 *
 * <p>该初始化器只处理文章向量事实，不创建未来的私人记忆 Collection，也不会在普通
 * 生产启动时自动改 schema。只有显式开启 {@code initialize-schema} 的受控环境才会调用；
 * 已存在的 Collection 仍由 {@link SdkMilvusSchemaAdmin} 做完整字段和索引校验，发生漂移
 * 时直接阻止启动，避免把向量写进不兼容结构。</p>
 */
public final class MilvusArticleSchemaInitializer {

    private final MilvusSchemaAdmin schemaAdmin;

    public MilvusArticleSchemaInitializer(MilvusSchemaAdmin schemaAdmin) {
        this.schemaAdmin = Objects.requireNonNull(schemaAdmin, "schemaAdmin");
    }

    public void initialize() {
        schemaAdmin.ensureExact(MilvusCollectionSchemas.article(),
                MilvusCollectionSchemas.ARTICLE_ALIAS);
    }
}
