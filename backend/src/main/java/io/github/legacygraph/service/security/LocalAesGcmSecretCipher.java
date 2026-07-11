package io.github.legacygraph.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 本地 AES-GCM 加密实现。
 * <p>
 * 密文格式：Base64( IV[12 bytes] || ciphertext+tag )
 * <p>
 * 密钥来源：{@code legacygraph.secret.master-key} 配置项（Base64 编码的 32 字节 AES-256 密钥）。
 * 未配置时自动生成临时密钥并输出 WARN（仅供开发环境，重启后旧密文不可解密）。
 */
@Slf4j
@Component
public class LocalAesGcmSecretCipher implements SecretCipher {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int IV_LENGTH = 12;       // bytes

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalAesGcmSecretCipher(
            @Value("${legacygraph.secret.master-key:}") String masterKeyBase64) {
        this.keySpec = resolveKey(masterKeyBase64);
    }

    private SecretKeySpec resolveKey(String masterKeyBase64) {
        if (masterKeyBase64 != null && !masterKeyBase64.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
            if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
                throw new IllegalArgumentException(
                        "legacygraph.secret.master-key must decode to 16/24/32 bytes, got " + keyBytes.length);
            }
            log.info("SecretCipher initialized with configured master key ({}-bit)", keyBytes.length * 8);
            return new SecretKeySpec(keyBytes, ALGORITHM);
        }
        // 开发环境兜底：生成随机密钥
        byte[] randomKey = new byte[32];
        secureRandom.nextBytes(randomKey);
        log.warn("legacygraph.secret.master-key not configured! Generated a random ephemeral key. "
                + "Encrypted passwords will NOT survive a restart. Set legacygraph.secret.master-key in application.yml.");
        return new SecretKeySpec(randomKey, ALGORITHM);
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // IV || ciphertext
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new SecretDecryptionException("Failed to encrypt secret", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return "";
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < IV_LENGTH + 1) {
                throw new SecretDecryptionException("Ciphertext too short: " + combined.length + " bytes");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] cipherBytes = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (SecretDecryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new SecretDecryptionException("Failed to decrypt secret", e);
        }
    }

    @Override
    public String mask(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        if (plaintext.length() <= 4) {
            return "***";
        }
        return plaintext.substring(0, 2) + "***" + plaintext.substring(plaintext.length() - 2);
    }
}
