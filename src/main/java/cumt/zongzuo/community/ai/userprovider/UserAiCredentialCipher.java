package cumt.zongzuo.community.ai.userprovider;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用 AES-256-GCM 保护用户自有 API Key。
 *
 * <p>每次加密都生成独立 96 位 nonce，密文包含版本字节、nonce 和 GCM 认证密文。
 * 主密钥只能从部署环境提供，不写入数据库、日志或接口响应。</p>
 */
public final class UserAiCredentialCipher {

    private static final byte FORMAT_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public UserAiCredentialCipher(String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.strip());
        }
        catch (IllegalArgumentException invalidBase64) {
            throw new IllegalStateException("AI credential master key must be Base64", invalidBase64);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("AI credential master key must contain exactly 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    /** 返回可存储的 Base64 密文，不保留任何明文前缀或后缀。 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("AI API key must not be blank");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, nonce,
                plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(1 + nonce.length + encrypted.length)
                .put(FORMAT_VERSION).put(nonce).put(encrypted).array());
    }

    /** 验证 GCM 认证标签后解密，密文被篡改时必须整体失败。 */
    public String decrypt(String encoded) {
        try {
            byte[] envelope = Base64.getDecoder().decode(encoded);
            if (envelope.length <= 1 + NONCE_BYTES || envelope[0] != FORMAT_VERSION) {
                throw new IllegalStateException("Stored AI credential has an unsupported format");
            }
            byte[] nonce = java.util.Arrays.copyOfRange(envelope, 1, 1 + NONCE_BYTES);
            byte[] ciphertext = java.util.Arrays.copyOfRange(envelope, 1 + NONCE_BYTES, envelope.length);
            return new String(crypt(Cipher.DECRYPT_MODE, nonce, ciphertext), StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("Stored AI credential is malformed", malformed);
        }
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD("metro-user-ai-credential-v1".getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        }
        catch (GeneralSecurityException error) {
            throw new IllegalStateException("AI credential cryptography failed", error);
        }
    }
}
