package io.github.legacygraph.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.service.graph.GraphCacheInvalidator;

/**
 * GraphCacheInvalidator 测试 — 按版本失效图谱视图 + 整体失效报告缓存。
 */
@ExtendWith(MockitoExtension.class)
class GraphCacheInvalidatorTest {

    @Mock
    private CacheService cacheService;

    @Test
    void testInvalidateVersion_EvictsGraphReportValidationAndVector() {
        GraphCacheInvalidator invalidator = new GraphCacheInvalidator(cacheService);
        invalidator.invalidateVersion("v1-abc");

        // 图谱视图按版本（去横线）前缀失效
        verify(cacheService).evictByPrefix("graph:v1abc:");
        // 验证报告按版本失效（带/不带横线两种形式）
        verify(cacheService).evictByPrefix("validation-report::v1-abc");
        verify(cacheService).evictByPrefix("validation-report::v1abc");
        // 报告整体失效
        verify(cacheService).evictByPrefix("report-");
        // 语义检索缓存兜底失效
        verify(cacheService).evictByPrefix("vec:search:");
    }

    @Test
    void testInvalidateAll_WhenVersionNull() {
        new GraphCacheInvalidator(cacheService).invalidateAll();
        verify(cacheService).evictByPrefix("graph:");
        verify(cacheService).evictByPrefix("validation-report");
        verify(cacheService).evictByPrefix("report-");
        verify(cacheService).evictByPrefix("vec:search:");
    }

    @Test
    void testInvalidateProjectOverview() {
        GraphCacheInvalidator invalidator = new GraphCacheInvalidator(cacheService);
        invalidator.invalidateProjectOverview("proj-1");
        verify(cacheService).evictByPrefix("project-overview::proj-1");

        invalidator.invalidateProjectOverview(null);
        verify(cacheService).evictByPrefix("project-overview");
    }
}
