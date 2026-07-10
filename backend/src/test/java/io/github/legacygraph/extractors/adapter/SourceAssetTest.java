package io.github.legacygraph.extractors.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SourceAsset 单元测试。
 * 验证源资产对象的构建与字段访问。
 */
@ExtendWith(MockitoExtension.class)
class SourceAssetTest {

    @TempDir
    Path tempDir;

    /**
     * 测试 Builder 构建完整资产。
     */
    @Test
    void builder_createsAssetWithAllFields() {
        Path file = tempDir.resolve("OrderController.java");
        SourceAsset asset = SourceAsset.builder()
                .file(file)
                .relativePath("OrderController.java")
                .fileType("java")
                .language("java")
                .framework("spring")
                .fileSize(1024L)
                .build();

        assertEquals(file, asset.getFile());
        assertEquals("OrderController.java", asset.getRelativePath());
        assertEquals("java", asset.getFileType());
        assertEquals("java", asset.getLanguage());
        assertEquals("spring", asset.getFramework());
        assertEquals(1024L, asset.getFileSize());
    }

    /**
     * 测试默认构造函数。
     */
    @Test
    void defaultConstructor_createsEmptyAsset() {
        SourceAsset asset = new SourceAsset();

        assertNull(asset.getFile());
        assertNull(asset.getRelativePath());
        assertNull(asset.getFileType());
        assertEquals(0L, asset.getFileSize());
    }

    /**
     * 测试全参数构造函数。
     */
    @Test
    void allArgsConstructor_createsValidAsset() {
        Path file = tempDir.resolve("test.xml");
        SourceAsset asset = new SourceAsset(
                file, "mapper/OrderMapper.xml",
                "xml", "xml", "mybatis", 2048L,
                null, null, 0L, null, false, null);

        assertEquals(file, asset.getFile());
        assertEquals("mapper/OrderMapper.xml", asset.getRelativePath());
        assertEquals("xml", asset.getFileType());
        assertEquals("xml", asset.getLanguage());
        assertEquals("mybatis", asset.getFramework());
        assertEquals(2048L, asset.getFileSize());
    }

    /**
     * 测试 lombok @Data 自动生成的 setter/getter 行为。
     */
    @Test
    void setters_updateFieldsCorrectly() {
        SourceAsset asset = new SourceAsset();
        asset.setFileType("vue");
        asset.setLanguage("javascript");

        assertEquals("vue", asset.getFileType());
        assertEquals("javascript", asset.getLanguage());
    }
}
