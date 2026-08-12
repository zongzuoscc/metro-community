package cumt.zongzuo.community.ai.userprovider;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAiCredentialCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void encryptsEverySaveWithANewNonceAndNeverLeavesPlaintextInStorage() {
        UserAiCredentialCipher cipher = new UserAiCredentialCipher(KEY);

        String first = cipher.encrypt("sk-user-secret-value");
        String second = cipher.encrypt("sk-user-secret-value");

        assertThat(first).isNotEqualTo(second).doesNotContain("sk-user-secret-value");
        assertThat(second).doesNotContain("sk-user-secret-value");
        assertThat(cipher.decrypt(first)).isEqualTo("sk-user-secret-value");
        assertThat(cipher.decrypt(second)).isEqualTo("sk-user-secret-value");
    }

    @Test
    void refusesMissingOrInvalidMasterKeysInsteadOfSavingRecoverablePlaintext() {
        assertThatThrownBy(() -> new UserAiCredentialCipher(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new UserAiCredentialCipher(
                Base64.getEncoder().encodeToString("too-short".getBytes())))
                .isInstanceOf(IllegalStateException.class);
    }
}
