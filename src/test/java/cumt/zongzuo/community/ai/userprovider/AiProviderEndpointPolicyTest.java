package cumt.zongzuo.community.ai.userprovider;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderEndpointPolicyTest {

    @Test
    void acceptsOnlyHttpsPublicOpenAiCompatibleEndpoints() throws Exception {
        AiProviderEndpointPolicy policy = new AiProviderEndpointPolicy(host -> List.of(
                InetAddress.getByName("93.184.216.34")));

        assertThat(policy.validateAndNormalize("https://models.example.com/v1/"))
                .isEqualTo("https://models.example.com/v1");
    }

    @Test
    void rejectsLoopbackPrivateMetadataAndCredentialBearingAddresses() {
        AiProviderEndpointPolicy policy = new AiProviderEndpointPolicy(host -> switch (host) {
            case "private.example" -> List.of(InetAddress.getLoopbackAddress());
            case "metadata.example" -> List.of(address("169.254.169.254"));
            default -> List.of(address("93.184.216.34"));
        });

        assertThatThrownBy(() -> policy.validateAndNormalize("http://models.example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validateAndNormalize("https://127.0.0.1/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validateAndNormalize("https://private.example/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validateAndNormalize("https://metadata.example/latest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validateAndNormalize("https://user:pass@models.example.com/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validateAndNormalize("https://models.example.com/v1?target=internal"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        }
        catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
