package cumt.zongzuo.community;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityApplicationTests {

    @Test
    void targetsJava17AndKeepsProductionConfigSecretFree() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        String config = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(pom).contains("<java.version>17</java.version>");
        assertThat(config)
                .doesNotContain("yangyiming.com")
                .doesNotContain("GTg3F34BVVjFK4XB");
    }
}
