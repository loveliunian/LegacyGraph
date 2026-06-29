package io.github.legacygraph;

import io.github.legacygraph.llm.PiiMaskingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PiiMaskingServiceTest {

    private PiiMaskingService piiMaskingService;

    @BeforeEach
    void setUp() {
        piiMaskingService = new PiiMaskingService();
    }

    @Test
    void testMask_NullInput() {
        assertNull(piiMaskingService.mask(null));
    }

    @Test
    void testMask_EmptyInput() {
        String result = piiMaskingService.mask("");
        assertEquals("", result);
    }

    @Test
    void testMask_CleanInputUnchanged() {
        String input = "这是一个普通的业务代码，没有任何敏感信息。";
        String result = piiMaskingService.mask(input);
        assertEquals(input, result);
    }

    @Test
    void testMask_ApiKey() {
        String input = "openai_api_key = sk-abcdefghijklmnopqrstuvwxyz1234567890abc";
        String result = piiMaskingService.mask(input);
        assertTrue(result.contains("[API_KEY_MASKED]") || result.contains("[MASKED]"),
                "API Key 应被脱敏: " + result);
        assertFalse(result.contains("sk-abcdefghijklmnopqrstuvwxyz1234567890abc"),
                "原始 API Key 不应出现");
    }

    @Test
    void testMask_JdbcConnectionString() {
        String input = "jdbc:postgresql://localhost:5432/legacy_graph?user=admin&password=secret123";
        String result = piiMaskingService.mask(input);
        assertTrue(result.contains("[CONNECTION_STRING_MASKED]"),
                "JDBC 连接串应被脱敏: " + result);
    }

    @Test
    void testMask_PasswordKeyValue() {
        String input = "password=my_secret_password_123";
        String result = piiMaskingService.mask(input);
        assertFalse(result.contains("my_secret_password_123"),
                "密码值不应出现明文");
    }

    @Test
    void testMask_Email() {
        String input = "联系邮箱: admin@example.com";
        String result = piiMaskingService.mask(input);
        assertTrue(result.contains("[EMAIL_MASKED]"),
                "邮箱应被脱敏: " + result);
        assertFalse(result.contains("admin@example.com"),
                "原始邮箱不应出现");
    }

    @Test
    void testMask_Phone() {
        String input = "联系电话: 13800138000";
        String result = piiMaskingService.mask(input);
        assertTrue(result.contains("[PHONE_MASKED]"),
                "手机号应被脱敏: " + result);
    }

    @Test
    void testMask_IpAddress() {
        String input = "服务器 IP: 192.168.1.100";
        String result = piiMaskingService.mask(input);
        assertTrue(result.contains("[IP_MASKED]"),
                "IP 地址应被脱敏: " + result);
    }

    @Test
    void testMask_MultipleSensitivePatterns() {
        String input = """
                server:
                  host: db.example.com
                  password=super_secret!
                api_key = sk-test-key-abcdefghijklmnopqrstuvwxyz1234567890abc
                admin@company.com
                """;
        String result = piiMaskingService.mask(input);
        assertNotEquals(input, result, "混合敏感信息应被脱敏");
        // 密码被脱敏
        assertFalse(result.contains("super_secret!"),
                "密码不应出现");
        // API Key 被脱敏（可能是 [API_KEY_MASKED] 或 password 脱敏模式）
        boolean apiKeyMasked = result.contains("[API_KEY_MASKED]") || result.contains("[MASKED]");
        assertTrue(apiKeyMasked, "API Key 应被脱敏");
        // 邮箱被脱敏
        assertFalse(result.contains("admin@company.com"),
                "邮箱不应出现");
    }

    @Test
    void testMask_PasswordColonFormat() {
        String input = "secret: my-token-value-here";
        String result = piiMaskingService.mask(input);
        assertFalse(result.contains("my-token-value-here"),
                "secret 值应被脱敏");
    }

    @Test
    void testMask_ShortValuesNotMasked() {
        // 长度 < 4 的值不算敏感密码
        String input = "password=abc";
        String result = piiMaskingService.mask(input);
        assertEquals(input, result, "过短的密码值不应被脱敏");
    }
}
