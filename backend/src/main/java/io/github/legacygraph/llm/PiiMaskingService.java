package io.github.legacygraph.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII 脱敏服务 - 在发送给 LLM 前脱敏敏感信息
 */
@Slf4j
@Service
public class PiiMaskingService {

    // 常见敏感信息模式
    private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "(sk-[a-zA-Z0-9]{36,}|[a-z0-9]{32,}\\.[a-z0-9]{32,}\\.[a-z0-9]{32,})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CONNECTION_STRING_PATTERN = Pattern.compile(
            "(jdbc:.*://[^\\s]+|postgres://[^\\s]+|mysql://[^\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(password|passwd|pwd|secret|token|key)\\s*[=:]\\s*['\"]?[^\\s'\"]{4,}['\"]?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}");

    private static final Pattern IP_ADDRESS_PATTERN = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    /**
     * 对输入文本进行脱敏
     */
    public String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        String masked = input;

        // API Key 脱敏
        masked = API_KEY_PATTERN.matcher(masked).replaceAll("[API_KEY_MASKED]");

        // 连接串脱敏
        masked = CONNECTION_STRING_PATTERN.matcher(masked).replaceAll("[CONNECTION_STRING_MASKED]");

        // 密码类脱敏
        Matcher passwordMatcher = PASSWORD_PATTERN.matcher(masked);
        StringBuffer sb = new StringBuffer();
        while (passwordMatcher.find()) {
            String match = passwordMatcher.group();
            // 保留键名，只脱敏值
            int eqPos = match.indexOf('=');
            if (eqPos < 0) eqPos = match.indexOf(':');
            if (eqPos >= 0) {
                passwordMatcher.appendReplacement(sb,
                        match.substring(0, eqPos + 1) + " [MASKED]");
            } else {
                passwordMatcher.appendReplacement(sb, "[PASSWORD_MASKED]");
            }
        }
        passwordMatcher.appendTail(sb);
        masked = sb.toString();

        // 邮箱脱敏
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("[EMAIL_MASKED]");

        // 手机号脱敏
        masked = PHONE_PATTERN.matcher(masked).replaceAll("[PHONE_MASKED]");

        // IP 地址脱敏
        masked = IP_ADDRESS_PATTERN.matcher(masked).replaceAll("[IP_MASKED]");

        if (!masked.equals(input)) {
            log.debug("PII masking applied");
        }

        return masked;
    }
}
