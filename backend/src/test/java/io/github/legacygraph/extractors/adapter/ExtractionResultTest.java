package io.github.legacygraph.extractors.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExtractionResult 单元测试。
 * 验证抽取结果对象的构建与字段访问。
 */
@ExtendWith(MockitoExtension.class)
class ExtractionResultTest {

    /**
     * 测试 Builder 构建完整结果。
     */
    @Test
    void builder_createsResultWithAllFields() {
        List<Object> data = new ArrayList<>();
        data.add("extra-info");

        ExtractionResult result = ExtractionResult.builder()
                .processedAssets(5)
                .nodeCount(10)
                .edgeCount(8)
                .evidenceCount(3)
                .summary("成功处理 5 个文件")
                .extractedData(data)
                .build();

        assertEquals(5, result.getProcessedAssets());
        assertEquals(10, result.getNodeCount());
        assertEquals(8, result.getEdgeCount());
        assertEquals(3, result.getEvidenceCount());
        assertEquals("成功处理 5 个文件", result.getSummary());
        assertEquals(1, result.getExtractedData().size());
    }

    /**
     * 测试默认构造函数 (Lombok @Builder 需要)。
     */
    @Test
    void defaultConstructor_createsEmptyResult() {
        ExtractionResult result = new ExtractionResult();

        assertEquals(0, result.getProcessedAssets());
        assertEquals(0, result.getNodeCount());
        assertNotNull(result.getExtractedData());
        assertTrue(result.getExtractedData().isEmpty());
    }

    /**
     * 测试空 extractedData 时的防御性初始化。
     */
    @Test
    void builder_withNullExtractedData_initializesEmptyList() {
        ExtractionResult result = ExtractionResult.builder()
                .processedAssets(1)
                .extractedData(null)
                .build();

        assertNotNull(result.getExtractedData());
    }

    /**
     * 测试全参数构造函数。
     */
    @Test
    void allArgsConstructor_createsValidResult() {
        List<Object> data = List.of("item1", "item2");
        ExtractionResult result = new ExtractionResult(
                2, 4, 3, 1, "done", data);

        assertEquals(2, result.getProcessedAssets());
        assertEquals(4, result.getNodeCount());
        assertEquals(3, result.getEdgeCount());
        assertEquals(1, result.getEvidenceCount());
        assertEquals("done", result.getSummary());
        assertEquals(2, result.getExtractedData().size());
    }
}
