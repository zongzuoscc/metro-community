package cumt.zongzuo.community.ai.userprovider;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAiProviderServiceTest {

    @Test
    void platformDefaultNeverExposesBackendProviderConfigurationFields() {
        UserAiProviderView view = UserAiProviderView.platformDefault();

        assertThat(view.configured()).isFalse();
        assertThat(view.provider()).isNull();
        assertThat(view.baseUrl()).isNull();
        assertThat(view.model()).isNull();
        assertThat(view.keyHint()).isNull();
        assertThat(view.fundingSource()).isEqualTo(UserAiFundingSource.PLATFORM);
    }

    @Test
    void savesEncryptedCredentialAndNeverReturnsThePlaintext() throws Exception {
        UserAiProviderMapper mapper = mock(UserAiProviderMapper.class);
        UserAiCredentialCipher cipher = new UserAiCredentialCipher(Base64.getEncoder()
                .encodeToString(new byte[32]));
        AiProviderEndpointPolicy endpoints = new AiProviderEndpointPolicy(host ->
                java.util.List.of(InetAddress.getByName("203.0.113.20")));
        UserAiProviderService service = new UserAiProviderService(mapper, cipher, endpoints);

        UserAiProviderView saved = service.save(7L, new UserAiProviderSaveRequest(
                "OPENAI", null, "gpt-4.1-mini", "sk-super-secret-value", true));

        org.mockito.ArgumentCaptor<UserAiProviderRecord> row =
                org.mockito.ArgumentCaptor.forClass(UserAiProviderRecord.class);
        verify(mapper).upsert(row.capture());
        assertThat(row.getValue().getEncryptedApiKey()).doesNotContain("sk-super-secret-value");
        assertThat(cipher.decrypt(row.getValue().getEncryptedApiKey()))
                .isEqualTo("sk-super-secret-value");
        assertThat(saved.keyHint()).isEqualTo("••••alue");
        assertThat(saved.toString()).doesNotContain("sk-super-secret-value");
        assertThat(saved.fundingSource()).isEqualTo(UserAiFundingSource.USER);
    }

    @Test
    void customProviderRejectsInternalEndpointBeforeAnyDatabaseWrite() {
        UserAiProviderMapper mapper = mock(UserAiProviderMapper.class);
        UserAiProviderService service = new UserAiProviderService(mapper,
                new UserAiCredentialCipher(Base64.getEncoder().encodeToString(new byte[32])),
                new AiProviderEndpointPolicy());

        assertThatThrownBy(() -> service.save(7L, new UserAiProviderSaveRequest(
                "CUSTOM", "https://127.0.0.1/v1", "private-model", "secret-key", true)))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void blankReplacementKeyKeepsTheExistingCiphertext() throws Exception {
        UserAiProviderMapper mapper = mock(UserAiProviderMapper.class);
        UserAiProviderRecord existing = new UserAiProviderRecord();
        existing.setUserId(7L);
        existing.setEncryptedApiKey("existing-ciphertext");
        existing.setKeyHint("••••old1");
        when(mapper.findByUserId(7L)).thenReturn(existing);
        UserAiProviderService service = new UserAiProviderService(mapper,
                new UserAiCredentialCipher(Base64.getEncoder().encodeToString(new byte[32])),
                new AiProviderEndpointPolicy(host ->
                        java.util.List.of(InetAddress.getByName("203.0.113.20"))));

        service.save(7L, new UserAiProviderSaveRequest(
                "QWEN", null, "qwen-plus", "", true));

        org.mockito.ArgumentCaptor<UserAiProviderRecord> row =
                org.mockito.ArgumentCaptor.forClass(UserAiProviderRecord.class);
        verify(mapper).upsert(row.capture());
        assertThat(row.getValue().getEncryptedApiKey()).isEqualTo("existing-ciphertext");
        assertThat(row.getValue().getKeyHint()).isEqualTo("••••old1");
    }

    @Test
    void userCanDisableAndDeleteTheCredential() {
        UserAiProviderMapper mapper = mock(UserAiProviderMapper.class);
        when(mapper.setEnabled(7L, false)).thenReturn(1);
        when(mapper.deleteByUserId(7L)).thenReturn(1);
        UserAiProviderService service = new UserAiProviderService(mapper,
                new UserAiCredentialCipher(Base64.getEncoder().encodeToString(new byte[32])),
                new AiProviderEndpointPolicy());

        service.setEnabled(7L, false);
        service.delete(7L);

        verify(mapper).setEnabled(7L, false);
        verify(mapper).deleteByUserId(7L);
    }
}
