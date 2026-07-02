package io.github.legacygraph.extractors.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ExtractionAdapterRegistry 单元测试。
 * 验证适配器注册、选择与分类过滤。
 */
@ExtendWith(MockitoExtension.class)
class ExtractionAdapterRegistryTest {

    @Mock
    private ExtractionAdapter javaAdapter;

    @Mock
    private ExtractionAdapter xmlAdapter;

    @Mock
    private ExtractionAdapter aiAdapter;

    private ExtractionAdapterRegistry registry;

    @BeforeEach
    void setUp() {
        // 配置 mock 能力
        when(javaAdapter.capability()).thenReturn(
                AdapterCapability.builder().name("JavaAdapter").priority(10).aiEnhanced(false).build());
        when(xmlAdapter.capability()).thenReturn(
                AdapterCapability.builder().name("XmlAdapter").priority(30).aiEnhanced(false).build());
        when(aiAdapter.capability()).thenReturn(
                AdapterCapability.builder().name("AIAdapter").priority(90).aiEnhanced(true).build());

        registry = new ExtractionAdapterRegistry(List.of(javaAdapter, xmlAdapter, aiAdapter));
    }

    /**
     * 测试 getAllAdapters 返回所有已注册适配器（按优先级排序）。
     */
    @Test
    void getAllAdapters_returnsAllSortedByPriority() {
        List<ExtractionAdapter> all = registry.getAllAdapters();

        assertEquals(3, all.size());
        assertEquals("JavaAdapter", all.get(0).capability().getName());
        assertEquals("XmlAdapter", all.get(1).capability().getName());
        assertEquals("AIAdapter", all.get(2).capability().getName());
    }

    /**
     * 测试 selectAdapter 选择第一个匹配的适配器。
     */
    @Test
    void selectAdapter_selectsFirstMatching() {
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder().fileType("xml").build();
        when(javaAdapter.supports(ctx, asset)).thenReturn(false);
        when(xmlAdapter.supports(ctx, asset)).thenReturn(true);

        Optional<ExtractionAdapter> result = registry.selectAdapter(ctx, asset);

        assertTrue(result.isPresent());
        assertEquals("XmlAdapter", result.get().capability().getName());
        verify(javaAdapter).supports(ctx, asset);
        verify(xmlAdapter).supports(ctx, asset);
        verify(aiAdapter, never()).supports(any(), any());
    }

    /**
     * 测试无匹配时返回 empty。
     */
    @Test
    void selectAdapter_noMatch_returnsEmpty() {
        ScanContext ctx = ScanContext.builder().build();
        SourceAsset asset = SourceAsset.builder().fileType("unknown").build();
        when(javaAdapter.supports(ctx, asset)).thenReturn(false);
        when(xmlAdapter.supports(ctx, asset)).thenReturn(false);
        when(aiAdapter.supports(ctx, asset)).thenReturn(false);

        Optional<ExtractionAdapter> result = registry.selectAdapter(ctx, asset);

        assertTrue(result.isEmpty());
    }

    /**
     * 测试 getStructuralAdapters 过滤非 AI 适配器。
     */
    @Test
    void getStructuralAdapters_filtersNonAi() {
        List<ExtractionAdapter> structural = registry.getStructuralAdapters();

        assertEquals(2, structural.size());
        assertTrue(structural.stream().noneMatch(a -> a.capability().isAiEnhanced()));
    }

    /**
     * 测试 getAiEnhancedAdapters 仅返回 AI 适配器。
     */
    @Test
    void getAiEnhancedAdapters_filtersOnlyAi() {
        List<ExtractionAdapter> ai = registry.getAiEnhancedAdapters();

        assertEquals(1, ai.size());
        assertEquals("AIAdapter", ai.get(0).capability().getName());
    }
}
