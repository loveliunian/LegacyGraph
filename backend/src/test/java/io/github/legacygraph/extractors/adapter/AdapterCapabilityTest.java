package io.github.legacygraph.extractors.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AdapterCapability 单元测试。
 * 验证适配器能力描述对象的构建与字段访问。
 */
@ExtendWith(MockitoExtension.class)
class AdapterCapabilityTest {

    /**
     * 测试 Builder 构建完整能力描述。
     */
    @Test
    void builder_createsCapabilityWithAllFields() {
        AdapterCapability cap = AdapterCapability.builder()
                .name("MyBatisXmlAdapter")
                .languages(Set.of("xml"))
                .frameworks(Set.of("mybatis"))
                .fileTypes(Set.of("xml"))
                .aiEnhanced(false)
                .priority(30)
                .build();

        assertNotNull(cap);
        assertEquals("MyBatisXmlAdapter", cap.getName());
        assertEquals(Set.of("xml"), cap.getLanguages());
        assertEquals(Set.of("mybatis"), cap.getFrameworks());
        assertEquals(Set.of("xml"), cap.getFileTypes());
        assertFalse(cap.isAiEnhanced());
        assertEquals(30, cap.getPriority());
    }

    /**
     * 测试 AI 增强型适配器标记。
     */
    @Test
    void aiEnhanced_flagIsSetCorrectly() {
        AdapterCapability aiCap = AdapterCapability.builder()
                .name("AIAdapter")
                .languages(Set.of("java", "python"))
                .aiEnhanced(true)
                .priority(90)
                .build();

        assertTrue(aiCap.isAiEnhanced());
    }

    /**
     * 测试优先级排序（数值越小越优先）。
     */
    @Test
    void priority_smallerNumberMeansHigherPriority() {
        AdapterCapability high = AdapterCapability.builder().name("High").priority(10).build();
        AdapterCapability low = AdapterCapability.builder().name("Low").priority(90).build();

        assertTrue(high.getPriority() < low.getPriority());
    }

    /**
     * 测试空集合默认值。
     */
    @Test
    void emptyBuilder_hasNullCollections() {
        AdapterCapability cap = AdapterCapability.builder().name("Test").build();

        assertNull(cap.getLanguages());
    }
}
