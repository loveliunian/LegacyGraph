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

        // 非 UUID versionId：5 个模式（graph + validation-report + report-* + vec:search:* + scanver:ts:*）
        verify(cacheService).evictByPatterns(argThat(patterns ->
            patterns.size() == 5
            && patterns.contains("graph:v1-abc:*")
            && patterns.contains("report-*")
            && patterns.contains("vec:search:*")
            && patterns.contains("scanver:ts:v1-abc*")
            && patterns.stream().anyMatch(p -> p.startsWith("validation-report::"))
        ));
        verify(cacheService, never()).evictByPrefix(anyString());
    }

    @Test
    void testInvalidateVersion_UuidVersionId_NormalizesHyphens() {
        GraphCacheInvalidator invalidator = new GraphCacheInvalidator(cacheService);
        invalidator.invalidateVersion("cda8cc37-5277-d6c0-da32-ba379795216a");

        // UUID versionId：6 个模式（graph normalized + 两个 validation-report + report-* + vec:search:* + scanver:ts:*）
        verify(cacheService).evictByPatterns(argThat(patterns ->
            patterns.size() == 6
            && patterns.contains("graph:cda8cc375277d6c0da32ba379795216a:*")
            && patterns.contains("report-*")
            && patterns.contains("vec:search:*")
            && patterns.contains("scanver:ts:cda8cc375277d6c0da32ba379795216a*")
            && patterns.contains("validation-report::*cda8cc37-5277-d6c0-da32-ba379795216a*")
            && patterns.contains("validation-report::*cda8cc375277d6c0da32ba379795216a*")
        ));
        verify(cacheService, never()).evictByPrefix(anyString());
    }

    @Test
    void testInvalidateAll_WhenVersionNull() {
        new GraphCacheInvalidator(cacheService).invalidateAll();
        verify(cacheService).evictByPatterns(argThat(patterns ->
            patterns.size() == 5
            && patterns.contains("graph:*")
            && patterns.contains("validation-report*")
            && patterns.contains("report-*")
            && patterns.contains("vec:search:*")
            && patterns.contains("scanver:ts:*")
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
