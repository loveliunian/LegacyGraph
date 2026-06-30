package io.github.legacygraph.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AiScanConfig.fromScanScope 解析测试。
 */
class AiScanConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testParse_AllFields() {
        String json = "{\"enableAi\":true,\"autoGenerateTestCase\":true,\"minConfidence\":0.7}";
        AiScanConfig config = AiScanConfig.fromScanScope(json, objectMapper);
        assertTrue(config.isEnableAi());
        assertTrue(config.isAutoGenerateTestCase());
        assertEquals(0.7, config.getMinConfidence());
    }

    @Test
    void testParse_PartialFields_UsesDefaults() {
        String json = "{\"enableAi\":true}";
        AiScanConfig config = AiScanConfig.fromScanScope(json, objectMapper);
        assertTrue(config.isEnableAi());
        assertFalse(config.isAutoGenerateTestCase());
        assertEquals(0.6, config.getMinConfidence());
    }

    @Test
    void testParse_NullOrBlank_ReturnsDisabledDefault() {
        AiScanConfig fromNull = AiScanConfig.fromScanScope(null, objectMapper);
        assertFalse(fromNull.isEnableAi());

        AiScanConfig fromBlank = AiScanConfig.fromScanScope("   ", objectMapper);
        assertFalse(fromBlank.isEnableAi());
    }

    @Test
    void testParse_InvalidJson_ReturnsDisabledDefault() {
        AiScanConfig config = AiScanConfig.fromScanScope("not-json{", objectMapper);
        assertFalse(config.isEnableAi());
        assertEquals(0.6, config.getMinConfidence());
    }
}
