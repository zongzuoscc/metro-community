package cumt.zongzuo.community.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.service.ArticleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Configuration
public class AiToolConfig {

    // 1. 定义大模型传递给我们的参数结构
    public record ArticleSearchRequest(String keyword) {}

    // 2. 注册工具 Bean
    // @Description 极其重要！这是写给大模型看的“说明书”，大模型会根据这段话决定要不要调用这个方法
    @Bean
    @Description("当用户询问社区有哪些文章、搜索某类技术教程、或者找人帮忙查阅资料时，调用此工具。传入用户的核心关键词即可在社区数据库中检索真实文章。")
    public Function<ArticleSearchRequest, String> searchArticlesTool(ArticleService articleService) {
        return request -> {
            log.info("🤖 触发 Agent 工具调用！AI 正在后台搜索关键词: {}", request.keyword());

            // 复用我们写好的 ES 高亮搜索接口，查第一页，取前 3 条最相关的
            Page<Article> page = articleService.searchArticles(request.keyword(), 1, 3);

            if (page.getRecords().isEmpty()) {
                return "没有找到与 '" + request.keyword() + "' 相关的文章，请告诉用户社区目前没有这方面的资料。";
            }

            // 将搜索结果拼接成纯文本“喂”给大模型
            return page.getRecords().stream()
                    .map(article -> String.format("标题: %s, 摘要: %s, 作者: %s, 链接: http://localhost:5173/article/%d",
                            article.getTitle(),
                            article.getSummary(),
                            article.getAuthorName(),
                            article.getId()))
                    .collect(Collectors.joining("\n---\n"));
        };
    }
}