package io.github.legacygraph.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JacksonConfig 单元测试。
 * <p>
 * 验证 Jackson ObjectMapper 的注册模块和序列化配置。
 * </p>
 */
class JacksonConfigTest {

    private final JacksonConfig jacksonConfig = new JacksonConfig();

    /**
     * 测试：ObjectMapper Bean 已注册 JavaTimeModule。
     */
    @Test
    void testObjectMapper_JavaTimeModuleRegistered() {
        ObjectMapper mapper = jacksonConfig.objectMapper();

        assertNotNull(mapper);
        // 验证注册了 JavaTimeModule（LocalDateTime 可正常序列化）
        assertDoesNotThrow(() -> {
            String json = mapper.writeValueAsString(LocalDateTime.of(2024, 1, 1, 12, 0));
            assertNotNull(json);
            assertTrue(json.contains("\"2024"));
        });
    }

    /**
     * 测试：WRITE_DATES_AS_TIMESTAMPS 已禁用（日期序列化为 ISO 字符串而非时间戳）。
     */
    @Test
    void testObjectMapper_DatesAsTimestampsDisabled() {
        ObjectMapper mapper = jacksonConfig.objectMapper();

        assertFalse(mapper.getSerializationConfig()
                .hasSerializationFeatures(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS.getMask()));
    }

    /**
     * 测试：ObjectMapper 可以正确序列化普通 POJO。
     */
    @Test
    void testObjectMapper_SerializePojo() throws Exception {
        ObjectMapper mapper = jacksonConfig.objectMapper();

        record TestPojo(String name, int value) {}
        TestPojo pojo = new TestPojo("test", 42);

        String json = mapper.writeValueAsString(pojo);
        assertNotNull(json);
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"test\""));
    }
}
