package io.github.legacygraph.extractors.adapter;

import io.github.legacygraph.builder.GraphBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MyBatisXmlAdapter 单元测试。
 * 验证 MyBatis Mapper XML 适配器的 supports/extract/capability 与 isMapperFile。
 */
@ExtendWith(MockitoExtension.class)
class MyBatisXmlAdapterTest {

    @Mock
    private GraphBuilder graphBuilder;

    @Mock
    private FactPersister factPersister;

    @InjectMocks
    private MyBatisXmlAdapter adapter;

    /**
     * 测试 supports — 识别 Mapper XML 文件。
     */
    @Test
    void supports_returnsTrueForMapperXml() {
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder()
                .file(Path.of("/src/OrderMapper.xml"))
                .relativePath("/src/OrderMapper.xml")
                .fileType("xml")
                .build();

        assertTrue(adapter.supports(ctx, asset));
    }

    /**
     * 测试 supports — 非 Mapper XML 返回 false。
     */
    @Test
    void supports_returnsFalseForNonMapperXml() {
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder()
                .file(Path.of("/config/spring.xml"))
                .relativePath("/config/spring.xml")
                .fileType("xml")
                .build();

        assertFalse(adapter.supports(ctx, asset));
    }

    /**
     * 测试 supports — 非 XML 文件返回 false。
     */
    @Test
    void supports_returnsFalseForJavaFile() {
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder()
                .file(Path.of("Test.java"))
                .fileType("java")
                .build();

        assertFalse(adapter.supports(ctx, asset));
    }

    /**
     * 测试 isMapperFile — 静态方法检测 Mapper XML。
     */
    @Test
    void isMapperFile_returnsTrueForMapperXml() {
        assertTrue(MyBatisXmlAdapter.isMapperFile("src/main/resources/mapper/OrderMapper.xml"));
        assertTrue(MyBatisXmlAdapter.isMapperFile("UserMapper.xml"));
        assertTrue(MyBatisXmlAdapter.isMapperFile("config/usermapper.xml"));
    }

    /**
     * 测试 isMapperFile — 普通 XML 返回 false。
     */
    @Test
    void isMapperFile_returnsFalseForNonMapper() {
        assertFalse(MyBatisXmlAdapter.isMapperFile("pom.xml"));
        assertFalse(MyBatisXmlAdapter.isMapperFile("application.xml"));
        assertFalse(MyBatisXmlAdapter.isMapperFile(null));
    }

    /**
     * 测试 capability 返回正确信息。
     */
    @Test
    void capability_returnsCorrectCapability() {
        AdapterCapability cap = adapter.capability();

        assertEquals("MyBatisXmlAdapter", cap.getName());
        assertEquals(Set.of("xml"), cap.getLanguages());
        assertEquals(Set.of("mybatis"), cap.getFrameworks());
        assertFalse(cap.isAiEnhanced());
        assertEquals(30, cap.getPriority());
    }
}
