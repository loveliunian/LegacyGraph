package io.github.legacygraph.builder;

import io.github.legacygraph.dto.graph.FeatureSlice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature Slice 存储 — 持久化/缓存型的 Slice 管理中心。
 */
@Slf4j
@Component
public class FeatureSliceStore {

    private final Map<String, FeatureSlice> sliceCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> projectIndex = new ConcurrentHashMap<>();

    private final FeatureSliceBuilder featureSliceBuilder;

    public FeatureSliceStore(FeatureSliceBuilder featureSliceBuilder) {
        this.featureSliceBuilder = featureSliceBuilder;
    }

    /** 从缓存或图谱获取切片 */
    public Optional<FeatureSlice> getOrBuild(String projectId, String versionId, String featureName) {
        Set<String> projectSlices = projectIndex.getOrDefault(projectId, Set.of());
        for (String sid : projectSlices) {
            FeatureSlice cached = sliceCache.get(sid);
            if (cached != null && featureName.equals(cached.getFeatureName())
                    && versionId.equals(cached.getVersionId())) {
                return Optional.of(cached);
            }
        }
        try {
            FeatureSlice slice = featureSliceBuilder.buildSlice(projectId, versionId, featureName);
            if (slice != null && slice.getSliceId() != null) {
                slice.setCreatedAt(slice.getCreatedAt() != null ? slice.getCreatedAt() : LocalDateTime.now());
                slice.setUpdatedAt(LocalDateTime.now());
                put(slice);
                return Optional.of(slice);
            }
        } catch (Exception e) {
            log.warn("FeatureSliceStore: build failed for {}/{}: {}", projectId, featureName, e.getMessage());
        }
        return Optional.empty();
    }

    /** 保存/更新切片到缓存 */
    public FeatureSlice put(FeatureSlice slice) {
        if (slice == null || slice.getSliceId() == null) return slice;
        slice.setUpdatedAt(LocalDateTime.now());
        sliceCache.put(slice.getSliceId(), slice);
        if (slice.getProjectId() != null) {
            projectIndex.computeIfAbsent(slice.getProjectId(), k -> ConcurrentHashMap.newKeySet())
                    .add(slice.getSliceId());
        }
        return slice;
    }

    /** 刷新切片 */
    public Optional<FeatureSlice> refresh(String sliceId) {
        FeatureSlice existing = sliceCache.get(sliceId);
        if (existing == null) return Optional.empty();
        try {
            FeatureSlice refreshed = featureSliceBuilder.buildSliceById(existing.getProjectId(), sliceId);
            if (refreshed != null) {
                refreshed.setCreatedAt(existing.getCreatedAt());
                refreshed.setUpdatedAt(LocalDateTime.now());
                sliceCache.put(sliceId, refreshed);
                return Optional.of(refreshed);
            }
        } catch (Exception e) {
            log.warn("FeatureSliceStore: refresh failed for {}: {}", sliceId, e.getMessage());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        return Optional.of(existing);
    }

    /** 按项目列出所有切片 */
    public List<FeatureSlice> listByProject(String projectId) {
        Set<String> sids = projectIndex.getOrDefault(projectId, Set.of());
        return sids.stream().map(sliceCache::get).filter(Objects::nonNull).toList();
    }

    /** 更新状态字段 */
    public Optional<FeatureSlice> updateStatus(String sliceId, String status, String coverageStatus,
                                                String riskLevel, List<String> evidenceSources) {
        FeatureSlice slice = sliceCache.get(sliceId);
        if (slice == null) return Optional.empty();
        if (status != null) slice.setStatus(status);
        if (coverageStatus != null) slice.setCoverageStatus(coverageStatus);
        if (riskLevel != null) slice.setRiskLevel(riskLevel);
        if (evidenceSources != null) slice.setEvidenceSources(new ArrayList<>(evidenceSources));
        slice.setUpdatedAt(LocalDateTime.now());
        return Optional.of(slice);
    }

    /** 移除切片 */
    public void remove(String sliceId) {
        FeatureSlice slice = sliceCache.remove(sliceId);
        if (slice != null && slice.getProjectId() != null) {
            Set<String> sids = projectIndex.get(slice.getProjectId());
            if (sids != null) sids.remove(sliceId);
        }
    }

    /** 清空项目缓存 */
    public void invalidateProject(String projectId) {
        Set<String> sids = projectIndex.remove(projectId);
        if (sids != null) sids.forEach(sliceCache::remove);
    }
}
