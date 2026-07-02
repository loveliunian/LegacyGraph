package io.github.legacygraph.builder;

import io.github.legacygraph.dto.graph.FeatureSlice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FeatureSliceStore 单元测试。
 * 验证切片缓存 put/getOrBuild/listByProject 操作。
 */
@ExtendWith(MockitoExtension.class)
class FeatureSliceStoreTest {

    @Mock
    private FeatureSliceBuilder featureSliceBuilder;

    private FeatureSliceStore store;

    @BeforeEach
    void setUp() {
        store = new FeatureSliceStore(featureSliceBuilder);
    }

    /**
     * 测试 put 后能通过 getOrBuild 从缓存获取。
     */
    @Test
    void putAndGetOrBuild_returnsFromCache() {
        FeatureSlice slice = FeatureSlice.builder()
                .sliceId("slice-1")
                .projectId("project-1")
                .versionId("v1")
                .name("订单创建")
                .featureName("订单创建")
                .status("ACTIVE")
                .coverageStatus("COVERED")
                .riskLevel("LOW")
                .confidence(BigDecimal.ONE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        store.put(slice);

        Optional<FeatureSlice> result = store.getOrBuild("project-1", "v1", "订单创建");

        assertTrue(result.isPresent());
        assertEquals("slice-1", result.get().getSliceId());
        assertEquals("LOW", result.get().getRiskLevel());
        // 不应触发 Builder
        verify(featureSliceBuilder, never()).buildSlice(anyString(), anyString(), anyString());
    }

    /**
     * 测试缓存未命中时调用 Builder 构建。
     */
    @Test
    void getOrBuild_cacheMiss_callsBuilder() {
        FeatureSlice built = FeatureSlice.builder()
                .sliceId("slice-2")
                .projectId("project-1")
                .versionId("v1")
                .name("用户管理")
                .featureName("用户管理")
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(featureSliceBuilder.buildSlice("project-1", "v1", "用户管理")).thenReturn(built);

        Optional<FeatureSlice> result = store.getOrBuild("project-1", "v1", "用户管理");

        assertTrue(result.isPresent());
        assertEquals("slice-2", result.get().getSliceId());
        verify(featureSliceBuilder).buildSlice("project-1", "v1", "用户管理");
    }

    /**
     * 测试 listByProject 返回项目所有切片。
     */
    @Test
    void listByProject_returnsAllSlices() {
        FeatureSlice s1 = FeatureSlice.builder()
                .sliceId("s1").projectId("project-1").name("订单创建")
                .featureName("订单创建").build();
        FeatureSlice s2 = FeatureSlice.builder()
                .sliceId("s2").projectId("project-1").name("用户管理")
                .featureName("用户管理").build();
        store.put(s1);
        store.put(s2);

        List<FeatureSlice> result = store.listByProject("project-1");

        assertEquals(2, result.size());
    }

    /**
     * 测试 updateStatus 更新缓存切片状态。
     */
    @Test
    void updateStatus_updatesCachedSlice() {
        FeatureSlice slice = FeatureSlice.builder()
                .sliceId("slice-3")
                .projectId("project-1")
                .name("支付")
                .featureName("支付")
                .status("ACTIVE")
                .coverageStatus("PARTIAL")
                .riskLevel("MEDIUM")
                .build();
        store.put(slice);

        Optional<FeatureSlice> updated = store.updateStatus(
                "slice-3", "INACTIVE", "COVERED", "LOW", List.of("BACKEND"));

        assertTrue(updated.isPresent());
        assertEquals("INACTIVE", updated.get().getStatus());
        assertEquals("COVERED", updated.get().getCoverageStatus());
        assertEquals("LOW", updated.get().getRiskLevel());
        assertEquals(List.of("BACKEND"), updated.get().getEvidenceSources());
    }
}
