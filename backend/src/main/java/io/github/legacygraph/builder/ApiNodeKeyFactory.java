package io.github.legacygraph.builder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * ApiEndpoint 节点的 nodeKey 工厂 — 解决评估 §2.1 标注的"同名重载覆盖"唯一性问题。
 *
 * <p>旧 {@link GraphBuilder#normalizeApiKey(String, String)} 只用 (METHOD, PATH)，
 * 同 controller 两条 {@code @GetMapping("/user")}（不同 params / 不同 produces）会被覆盖。
 * 本工厂在 PATH 后追加 {@code @<8-char signature hash>}，使重载方法产生不同 nodeKey，
 * 都可落库。</p>
 *
 * <p>spring 6+ 引入的 {@code headers = "..."} / {@code params = "..."} / {@code produces = "..."}
 * 三类显式限定条件被映射到 METHOD/PATH 之后；如三者皆无则回退到基础 key 形式</p>
 */
public final class ApiNodeKeyFactory {

    private ApiNodeKeyFactory() {}

    /**
     * 从 base key 计算含 signature 后缀的完整 nodeKey。
     *
     * @param baseKey 旧 nodeKey，调用 {@link GraphBuilder#normalizeApiKey(String, String)} 得到
     * @param signature 用于消歧的限定条件（headers / params / produces 拼接），null 或空则不加后缀
     * @return 含 {@code @<hash>} 后缀的 nodeKey
     */
    public static String of(String baseKey, String signature) {
        if (baseKey == null || baseKey.isBlank()) return baseKey;
        if (signature == null || signature.isBlank()) return baseKey;
        String hash = sha256Short8(signature);
        return baseKey + "@" + hash;
    }

    /**
     * 计算 ApiEndpoint 的完整 nodeKey（内部用 {@link GraphBuilder#normalizeApiKey(String, String)}）。
     *
     * @param httpMethod HTTP 方法（GET / POST / ...）
     * @param path       完整路径（已规范化的 URL）
     * @param signature  用于消歧的限定条件字符串（headers + "|" + params + "|" + produces），null/空则不加后缀
     */
    public static String of(String httpMethod, String path, String signature) {
        return of(GraphBuilder.normalizeApiKey(httpMethod, path), signature);
    }

    private static String sha256Short8(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // 取前 4 字节（8 个十六进制字符），冲突概率 1/2^32 ≈ 4 亿分之一
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 标准算法，不可达；回退到 hashCode
            return Integer.toHexString(input.hashCode() & 0xFFFFFFFF);
        }
    }
}
