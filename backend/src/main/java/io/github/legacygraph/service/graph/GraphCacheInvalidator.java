package io.github.legacygraph.service.graph;

import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
        List<String> patterns = new ArrayList<>();
        if (versionId != null) {
            String normalized = IdUtil.normalizeId(versionId);
            patterns.add("graph:" + normalized + ":*");
            // S3-T5: 同时清除 scan_version 时间戳缓存，确保下次查询获取最新 updatedAt
            patterns.add("scanver:ts:" + normalized + "*");
            patterns.add("validation-report::*" + versionId + "*");
            if (!normalized.equals(versionId)) {
                patterns.add("validation-report::*" + normalized + "*");
            }
        } else {
            patterns.add("graph:*");
            patterns.add("scanver:ts:*");
            patterns.add("validation-report*");
        }
        // 报告缓存键形如 lg:report-xxx::projectId:versionId，按 report- 前缀整体失效
        patterns.add("report-*");
        // 语义检索结果缓存（向量库随扫描更新；短 TTL + 此处兜底失效）
        patterns.add("vec:search:*");
        cacheService.evictByPatterns(patterns);
        log.debug("Graph/report cache invalidated for version={}", versionId);
    }

    /**
     * 失效所有图谱与报告缓存（无法确定版本时使用，如跨版本合并、批量操作）。
     */
    public void invalidateAll() {
        invalidateVersion(null);
    }

    /**
     * 失效指定项目的概览缓存（仪表盘）。
     * 在扫描、审核、源接入变更后调用。
     */
    public void invalidateProjectOverview(String projectId) {
        if (projectId == null) {
            cacheService.evictByPrefix("project-overview");
        } else {
            cacheService.evictByPrefix("project-overview::" + projectId);
        }
    }
}
