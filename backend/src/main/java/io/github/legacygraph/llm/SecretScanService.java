package io.github.legacygraph.llm;

import io.github.legacygraph.dto.graph.PrivacyLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 密钥/凭据扫描服务（见 doc 4.4）— 把脱敏前移到证据入库层。
 * <p>
 * 在源码、配置、数据库连接串、日志样本进入图谱前做 secret scan：
 * 命中密钥的证据内容以 redacted 形式落库，并打 {@link PrivacyLevel#SECRET}，
 * 避免图谱平台本身成为高敏资产聚合点。
 * </p>
 */
@Slf4j
@Service
public class SecretScanService {

    @Data
    @Builder
    @AllArgsConstructor
    public static class SecretFinding {
        /** secret 类型：private_key / aws_access_key / github_token / api_key / jdbc_url / password_assign / high_entropy */
        private String type;
        /** 命中片段（已截断） */
        private String snippet;
    }

    @Data
    @Builder
    public static class SecretScanResult {
        private boolean hasSecret;
        private List<SecretFinding> findings;
        /** 命中密钥后的脱敏内容（未命中则原样返回） */
        private String redacted;
        /** 建议隐私级别：命中密钥为 SECRET，含连接串/凭据为 CONFIDENTIAL，否则 INTERNAL */
        private PrivacyLevel suggestedLevel;
        /** 建议脱敏策略 */
        private String suggestedPolicy;
    }

    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
            "-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----");

    private static final Pattern AWS_ACCESS_KEY_PATTERN = Pattern.compile(
            "\\bAKIA[0-9A-Z]{16}\\b");

    private static final Pattern GITHUB_TOKEN_PATTERN = Pattern.compile(
            "\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{82})\\b");

    private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "(sk-[A-Za-z0-9]{36,}|[A-Za-z0-9]{32}\\.[A-Za-z0-9]{32}\\.[A-Za-z0-9]{32})");

    private static final Pattern JDBC_URL_PATTERN = Pattern.compile(
            "(?i)jdbc:[a-z0-9]+://[^\\s\"']+");

    private static final Pattern PASSWORD_ASSIGN_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|apikey|api_key|access_key|private_key)\\s*[=:]\\s*['\"]?[A-Za-z0-9_!@#$%^&*./+\\-]{4,}['\"]?");

    /** 高熵候选：形如 base64/hex 的长串，避免误伤普通代码 —— 仅在密钥上下文中作为补充 */
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)(bearer|authorization)\\s*[=:]?\\s*[A-Za-z0-9_\\-\\.]{20,}");

    /**
     * 扫描内容，返回脱敏后的内容与建议隐私级别。
     */
    public SecretScanResult scan(String content) {
        if (content == null || content.isEmpty()) {
            return SecretScanResult.builder()
                    .hasSecret(false).findings(List.of()).redacted(content)
                    .suggestedLevel(PrivacyLevel.INTERNAL).suggestedPolicy("none")
                    .build();
        }

        List<SecretFinding> findings = new ArrayList<>();
        String redacted = content;
        boolean secretHit = false;
        boolean confidentialHit = false;

        // 1. 私钥（最高危，直接 SECRET）
        if (PRIVATE_KEY_PATTERN.matcher(redacted).find()) {
            findings.add(SecretFinding.builder().type("private_key")
                    .snippet("-----BEGIN ... PRIVATE KEY-----").build());
            redacted = PRIVATE_KEY_PATTERN.matcher(redacted)
                    .replaceAll("[PRIVATE_KEY_MASKED]");
            secretHit = true;
        }

        // 2. AWS access key
        redacted = markAndRedact(redacted, AWS_ACCESS_KEY_PATTERN, "aws_access_key",
                "[AWS_KEY_MASKED]", findings);
        if (!findings.isEmpty() && redacted.contains("[AWS_KEY_MASKED]")) secretHit = true;

        // 3. GitHub token
        int before = findings.size();
        redacted = markAndRedact(redacted, GITHUB_TOKEN_PATTERN, "github_token",
                "[GITHUB_TOKEN_MASKED]", findings);
        if (findings.size() > before) secretHit = true;

        // 4. 通用 API key (sk-... / xxx.yyy.zzz)
        before = findings.size();
        redacted = markAndRedact(redacted, API_KEY_PATTERN, "api_key",
                "[API_KEY_MASKED]", findings);
        if (findings.size() > before) secretHit = true;

        // 5. Bearer / Authorization token
        before = findings.size();
        redacted = markAndRedact(redacted, BEARER_TOKEN_PATTERN, "bearer_token",
                "[BEARER_TOKEN_MASKED]", findings);
        if (findings.size() > before) secretHit = true;

        // 6. JDBC 连接串（含凭据，CONFIDENTIAL；若含 password= 则升级 SECRET）
        before = findings.size();
        String afterJdbc = markAndRedact(redacted, JDBC_URL_PATTERN, "jdbc_url",
                "[JDBC_URL_MASKED]", findings);
        if (findings.size() > before) {
            confidentialHit = true;
            // 连接串里若带 password 参数视为 SECRET
            if (Pattern.compile("(?i)password=").matcher(redacted).find()) {
                secretHit = true;
            }
            redacted = afterJdbc;
        }

        // 7. password=xxx 赋值（保留键名）
        java.util.regex.Matcher pwMatcher = PASSWORD_ASSIGN_PATTERN.matcher(redacted);
        StringBuilder sb = new StringBuilder();
        boolean pwFound = false;
        while (pwMatcher.find()) {
            String match = pwMatcher.group();
            int eqPos = Math.max(match.indexOf('='), match.indexOf(':'));
            if (eqPos >= 0) {
                pwMatcher.appendReplacement(sb,
                        java.util.regex.Matcher.quoteReplacement(match.substring(0, eqPos + 1) + " [MASKED]"));
            } else {
                pwMatcher.appendReplacement(sb, "[PASSWORD_MASKED]");
            }
            findings.add(SecretFinding.builder().type("password_assign")
                    .snippet("[MASKED]").build());
            pwFound = true;
        }
        pwMatcher.appendTail(sb);
        if (pwFound) {
            redacted = sb.toString();
            secretHit = true;
        }

        PrivacyLevel level;
        String policy;
        if (secretHit) {
            level = PrivacyLevel.SECRET;
            policy = "mask";
        } else if (confidentialHit) {
            level = PrivacyLevel.CONFIDENTIAL;
            policy = "mask";
        } else {
            level = PrivacyLevel.INTERNAL;
            policy = "none";
        }

        if (secretHit || confidentialHit) {
            log.debug("Secret scan flagged {} finding(s), level={}", findings.size(), level);
        }

        return SecretScanResult.builder()
                .hasSecret(secretHit || confidentialHit)
                .findings(findings)
                .redacted(redacted)
                .suggestedLevel(level)
                .suggestedPolicy(policy)
                .build();
    }

    private String markAndRedact(String input, Pattern pattern, String type,
                                 String replacement, List<SecretFinding> findings) {
        java.util.regex.Matcher m = pattern.matcher(input);
        String result = input;
        boolean found = false;
        while (m.find()) {
            String g = m.group();
            findings.add(SecretFinding.builder().type(type)
                    .snippet(g.length() > 40 ? g.substring(0, 40) + "..." : g).build());
            found = true;
        }
        if (found) {
            result = pattern.matcher(input).replaceAll(
                    java.util.regex.Matcher.quoteReplacement(replacement));
        }
        return result;
    }
}
