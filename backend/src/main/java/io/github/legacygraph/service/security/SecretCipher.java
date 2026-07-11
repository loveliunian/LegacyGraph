package io.github.legacygraph.service.security;

/**
 * 密码/密钥加解密服务接口。
 * 用于数据库连接密码的可逆加密存储，替代旧的不可逆脱敏方案。
 */
public interface SecretCipher {

    /**
     * 加密明文，返回 Base64 编码的密文（含 IV 前缀）。
     *
     * @param plaintext 明文
     * @return Base64 编码的 "iv + ciphertext"，或 null（当 plaintext 为 null/空时）
     */
    String encrypt(String plaintext);

    /**
     * 解密密文，返回明文。
     *
     * @param ciphertext Base64 编码的密文（含 IV 前缀）
     * @return 明文
     * @throws SecretDecryptionException 解密失败（密钥错误/密文损坏）
     */
    String decrypt(String ciphertext);

    /**
     * 对明文密码进行有损脱敏，仅用于前端展示。
     *
     * @param plaintext 明文密码
     * @return 脱敏后的字符串（如 "xx***yy"）
     */
    String mask(String plaintext);
}
