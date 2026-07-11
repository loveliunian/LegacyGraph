package io.github.legacygraph.service.source;

import io.github.legacygraph.entity.SourceSnapshotEntity;
import io.github.legacygraph.repository.SourceSnapshotRepository;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 源快照服务（G-02）。
 * <p>
 * 管理不可变 SourceSnapshot 父表的生命周期：每次扫描由 SourceConnector 产出一条新快照，
 * 通过 {@code parentSnapshotId} 自动串联上一个快照构成版本链。提供最新快照查询、
 * 按扫描版本查询以及内容变更判定（基于 contentHash 比对）能力。
 * </p>
 */
@Slf4j
@Service
public class SourceSnapshotService {

    /** 新建快照的默认状态 */
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final SourceSnapshotRepository snapshotRepository;

    public SourceSnapshotService(SourceSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * 创建一条不可变源快照。
     * <p>
     * 自动查找该 (projectId, sourceType, sourceId) 的上一个最新快照，
     * 将其 ID 写入 {@code parentSnapshotId} 构成版本链。
     *
     * @param projectId     项目 ID
     * @param sourceType    源类型（CODE / DOC / DB / RUN / EXTERNAL）
     * @param sourceId      源 ID
     * @param sourceUri     源 URI
     * @param contentHash   内容哈希（SHA-256）
     * @param scanVersionId 扫描版本 ID
     * @param mimeType      MIME 类型
     * @param sizeBytes     字节大小
     * @param aclHash       ACL 哈希
     * @return 已持久化的快照实体
     */
    public SourceSnapshotEntity createSnapshot(String projectId, String sourceType, String sourceId,
                                               String sourceUri, String contentHash, String scanVersionId,
                                               String mimeType, long sizeBytes, String aclHash) {
        // 查找上一个最新快照，作为父快照
        SourceSnapshotEntity parent = findLatest(projectId, sourceType, sourceId);

        SourceSnapshotEntity snapshot = new SourceSnapshotEntity();
        snapshot.setId(IdUtil.fastUUID());
        snapshot.setProjectId(projectId);
        snapshot.setSourceType(sourceType);
        snapshot.setSourceId(sourceId);
        snapshot.setSourceUri(sourceUri);
        snapshot.setContentHash(contentHash);
        snapshot.setParentSnapshotId(parent == null ? null : parent.getId());
        snapshot.setScanVersionId(scanVersionId);
        snapshot.setMimeType(mimeType);
        snapshot.setSizeBytes(sizeBytes);
        snapshot.setAclHash(aclHash);
        snapshot.setStatus(STATUS_ACTIVE);
        snapshot.setCreatedAt(LocalDateTime.now());

        snapshotRepository.insert(snapshot);
        log.info("已创建源快照：projectId={}, sourceType={}, sourceId={}, snapshotId={}, parentId={}",
                projectId, sourceType, sourceId, snapshot.getId(), snapshot.getParentSnapshotId());
        return snapshot;
    }

    /**
     * 查找指定源的最新快照（按创建时间倒序取第一条）。
     *
     * @param projectId  项目 ID
     * @param sourceType 源类型
     * @param sourceId   源 ID
     * @return 最新快照，无记录时返回 null
     */
    public SourceSnapshotEntity findLatest(String projectId, String sourceType, String sourceId) {
        return snapshotRepository.lambdaQuery()
                .eq(SourceSnapshotEntity::getProjectId, projectId)
                .eq(SourceSnapshotEntity::getSourceType, sourceType)
                .eq(SourceSnapshotEntity::getSourceId, sourceId)
                .orderByDesc(SourceSnapshotEntity::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 按扫描版本查找全部快照。
     *
     * @param scanVersionId 扫描版本 ID
     * @return 该扫描版本下的快照列表
     */
    public List<SourceSnapshotEntity> findByScanVersion(String scanVersionId) {
        return snapshotRepository.lambdaQuery()
                .eq(SourceSnapshotEntity::getScanVersionId, scanVersionId)
                .orderByDesc(SourceSnapshotEntity::getCreatedAt)
                .list();
    }

    /**
     * 判断指定源的内容是否发生变化（基于 contentHash 比对）。
     * <p>
     * 无历史快照视为已变化（新增源）；哈希一致视为未变化。
     *
     * @param projectId   项目 ID
     * @param sourceType  源类型
     * @param sourceId    源 ID
     * @param contentHash 当前内容哈希
     * @return true 表示内容已变化（需重新扫描），false 表示未变化
     */
    public boolean hasChanged(String projectId, String sourceType, String sourceId, String contentHash) {
        SourceSnapshotEntity latest = findLatest(projectId, sourceType, sourceId);
        if (latest == null) {
            return true;
        }
        return !contentHash.equals(latest.getContentHash());
    }
}
