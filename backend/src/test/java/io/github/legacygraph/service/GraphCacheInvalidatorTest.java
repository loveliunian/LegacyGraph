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
    void testInvalidateVersion_EvictsByPatterns() {
        GraphCacheInvalidator invalidator = new GraphCacheInvalidator(cacheService);
        invalidator.invalidateVersion("v1-abc");

        // 现在使用 evictByPatterns 批量失效：5 个模式
        verify(cacheService).evictByPatterns(argThat(patterns ->
            patterns.size() == 5
            && patterns.contains("graph:v1abc:*")
            && patterns.contains("report-*")
            && patterns.contains("vec:search:*")
            && patterns.stream().anyMatch(p -> p.startsWith("validation-report::"))
        ));
        verify(cacheService, never()).evictByPrefix(anyString());
    }

    @Test
    void testInvalidateAll_WhenVersionNull() {
        new GraphCacheInvalidator(cacheService).invalidateAll();
        verify(cacheService).evictByPatterns(argThat(patterns ->
            patterns.size() == 4
            && patterns.contains("graph:*")
            && patterns.contains("validation-report*")
            && patterns.contains("report-*")
            && patterns.contains("vec:search:*")
        ));
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
