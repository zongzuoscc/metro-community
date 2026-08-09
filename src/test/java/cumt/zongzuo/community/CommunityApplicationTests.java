package cumt.zongzuo.community;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityApplicationTests {

    @Test
    void targetsJava21AndKeepsProductionConfigSecretFree() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        String config = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(pom).contains("<java.version>21</java.version>");
        assertThat(config)
                .doesNotContain("yangyiming.com")
                .doesNotContain("GTg3F34BVVjFK4XB");
    }

    @Test
    void documentsServingAsDisabledByDefaultWhileDailyTrainingRemainsEnabled() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        String environment = Files.readString(Path.of(".env.example"));

        assertThat(readme)
                .contains("推荐排序 Serving 默认关闭")
                .contains("训练任务仍按 Asia/Shanghai 每日 02:15 运行")
                .doesNotContain("推荐训练默认关闭");
        assertThat(environment).contains(
                "RECOMMENDATION_ENABLED=false",
                "RECOMMENDATION_MODEL_WINDOW_DAYS=90",
                "RECOMMENDATION_LABEL_WINDOW_DAYS=7",
                "RECOMMENDATION_MODEL_MAX_AGE_DAYS=7",
                "RECOMMENDATION_TRAINING_SAMPLE_LIMIT=50000",
                "RECOMMENDATION_MODEL_DIRECTORY=");
    }
}
