package io.github.legacygraph.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 图谱/报告缓存失效器（场景 4）。
 *
 * <p>图谱发生写入（节点/边合并、审核确认/驳回、置信度更新、重新扫描）后调用，
 * 失效相关的只读缓存，避免读到陈旧数据。</p>
 *
 * <p>图谱变更频率远低于查询，故采用「按版本失效图谱视图 + 整体失效报告」的保守策略，
 * 保证正确性优先。所有操作经 {@link CacheService} 容错，Redis 不可用时静默降级。</p>
 */
@Slf4j
@Service
public class GraphCacheInvalidator {

    private final CacheService cacheService;

    public GraphCacheInvalidator(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * 按版本失效图谱只读缓存（graph:{versionId}:*）与全部报告缓存。
     *
     * @param versionId 受影响版本；为 null 时失效所有图谱视图缓存（graph:*）
     */
    public void invalidateVersion(String versionId) {
        if (versionId != null) {
            cacheService.evictByPrefix("graph:" + versionId.replace("-", "") + ":");
        } else {
            cacheService.evictByPrefix("graph:");
        }
        // 报告缓存键形如 lg:report-xxx::projectId:versionId，按 report- 前缀整体失效
        cacheService.evictByPrefix("report-");
        log.debug("Graph/report cache invalidated for version={}", versionId);
    }

    /**
     * 失效所有图谱与报告缓存（无法确定版本时使用，如跨版本合并、批量操作）。
     */
    public void invalidateAll() {
        invalidateVersion(null);
    }
}
