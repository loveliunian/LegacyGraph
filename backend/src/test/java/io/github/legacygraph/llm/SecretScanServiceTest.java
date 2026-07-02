package io.github.legacygraph.llm;

import io.github.legacygraph.dto.graph.PrivacyLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SecretScanService 单元测试。
 * 验证密钥扫描、脱敏与隐私级别判定。
 */
@ExtendWith(MockitoExtension.class)
class SecretScanServiceTest {

    private SecretScanService service;

    @BeforeEach
    void setUp() {
        service = new SecretScanService();
    }

    /**
     * 测试空内容扫描返回安全结果。
     */
    @Test
    void scan_nullContent_returnsSafeResult() {
        SecretScanService.SecretScanResult result = service.scan(null);

        assertNotNull(result);
        assertFalse(result.isHasSecret());
        assertTrue(result.getFindings().isEmpty());
        assertEquals(PrivacyLevel.INTERNAL, result.getSuggestedLevel());
        assertEquals("none", result.getSuggestedPolicy());
    }

    /**
     * 测试空字符串内容返回安全结果。
     */
    @Test
    void scan_emptyContent_returnsSafeResult() {
        SecretScanService.SecretScanResult result = service.scan("");

        assertNotNull(result);
        assertFalse(result.isHasSecret());
        assertEquals(PrivacyLevel.INTERNAL, result.getSuggestedLevel());
    }

    /**
     * 测试包含私钥的内容被标记为 SECRET。
     */
    @Test
    void scan_privateKeyContent_marksAsSecret() {
        String content = "some config\n-----BEGIN RSA PRIVATE KEY-----\nMIIEpQIBAA...\n-----END RSA PRIVATE KEY-----\n";

        SecretScanService.SecretScanResult result = service.scan(content);

        assertNotNull(result);
        assertTrue(result.isHasSecret());
        assertEquals(PrivacyLevel.SECRET, result.getSuggestedLevel());
        assertEquals("mask", result.getSuggestedPolicy());
        assertTrue(result.getRedacted().contains("[PRIVATE_KEY_MASKED]"));
        assertFalse(result.getFindings().isEmpty());
        assertTrue(result.getFindings().stream().anyMatch(f -> "private_key".equals(f.getType())));
    }

    /**
     * 测试包含 AWS access key 的内容被检测。
     */
    @Test
    void scan_awsAccessKey_marksAsSecret() {
        String content = "AWS_ACCESS_KEY=AKIAIOSFODNN7EXAMPLE";

        SecretScanService.SecretScanResult result = service.scan(content);

        assertNotNull(result);
        assertTrue(result.isHasSecret());
        assertTrue(result.getRedacted().contains("[AWS_KEY_MASKED]"));
    }

    /**
     * 测试普通代码内容不触发密钥检测。
     */
    @Test
    void scan_normalCode_returnsInternal() {
        String content = "public class HelloWorld {\n" +
                "    public static void main(String[] args) {\n" +
                "        System.out.println(\"Hello\");\n" +
                "    }\n" +
                "}";

        SecretScanService.SecretScanResult result = service.scan(content);

        assertNotNull(result);
        assertFalse(result.isHasSecret());
        assertEquals(PrivacyLevel.INTERNAL, result.getSuggestedLevel());
        assertEquals(content, result.getRedacted());
    }

    /**
     * 测试包含 password=xxx 赋值的内容被脱敏。
     */
    @Test
    void scan_passwordAssignment_marksAsSecret() {
        String content = "spring.datasource.password=MySecret123";

        SecretScanService.SecretScanResult result = service.scan(content);

        assertNotNull(result);
        assertTrue(result.isHasSecret());
        assertTrue(result.getRedacted().contains("[MASKED]"));
    }
}
