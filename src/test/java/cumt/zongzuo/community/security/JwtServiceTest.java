package cumt.zongzuo.community.security;

import cumt.zongzuo.community.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-with-at-least-thirty-two-characters";
    private final JwtService jwtService = new JwtService(
            new SecurityProperties(SECRET, Duration.ofMinutes(30), List.of("http://localhost:5173")));

    @Test
    void parsesTheSubjectFromItsOwnSignedToken() {
        String token = jwtService.generate(42L);

        assertThat(jwtService.parse(token)).isEqualTo(42L);
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        JwtService anotherService = new JwtService(
                new SecurityProperties("another-test-secret-with-at-least-thirty-two-characters",
                        Duration.ofMinutes(30), List.of()));

        assertThatThrownBy(() -> jwtService.parse(anotherService.generate(42L)))
                .isInstanceOf(RuntimeException.class);
    }
}
