package io.github.legacygraph.service.scan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 文件快照墓碑服务（G-05）— 增量扫描补齐删除/重命名/逻辑重扫。
 * <p>
 * 增量扫描时，文件可能被删除、重命名或其依赖发生变更。本服务负责：
 * <ol>
 *   <li>{@link #tombstoneDeletedNodes} — 标记删除文件对应的图节点为 TOMBSTONED（墓碑态）</li>
 *   <li>{@link #markStaleNodes} — 标记需要逻辑重扫的节点为 STALE（过期态）</li>
 *   <li>{@link #evict} — 失效已无最新引用的节点与向量（EVICTED，最终态）</li>
 * </ol>
 * <p>
 * 状态流转：PENDING_CONFIRM/CONFIRMED → TOMBSTONED/STALE → EVICTED（deleted=1）
 * <p>
 * 使用 JdbcTemplate 直接操作 lg_graph_node 与 lg_vector_document 表，
 * 不依赖已废弃的 GraphNode 实体。
 */
@Slf4j
@Service
public class FileSnapshotTombstoneService {

    /** 节点状态：墓碑（文件已删除） */
    public static final String STATUS_TOMBSTONED = "TOMBSTONED";
    /** 节点状态：过期（需要逻辑重扫） */
    public static final String STATUS_STALE = "STALE";
    /** 节点状态：已失效（无最新引用，最终态） */
    public static final String STATUS_EVICTED = "EVICTED";

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public FileSnapshotTombstoneService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 失效已无最新引用的节点与向量。
     * <p>
     * 将当前快照中已不存在对应文件的图节点标记为 EVICTED 并软删除（deleted=1），
     * 同时失效关联的向量文档。通常在 tombstone 过渡期后调用。
     *
     * @param projectId     项目 ID
     * @param scanVersionId 当前扫描版本 ID（用于记录 last_scan_version_id）
     * @return 被失效的节点数量
     */
    public int evict(String projectId, String scanVersionId) {
        if (projectId == null) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        // 失效节点：source_path 不在当前文件快照中，且尚未被软删除
        String evictNodeSql = "UPDATE lg_graph_node SET status = ?, deleted = 1, " +
                "tombstone_reason = COALESCE(tombstone_reason, 'EVICTED_NO_REFERENCE'), " +
                "tombstoned_at = COALESCE(tombstoned_at, ?), updated_at = ? " +
                "WHERE project_id = ? AND deleted = 0 " +
                "AND source_path IS NOT NULL " +
                "AND source_path NOT IN (SELECT file_path FROM lg_file_snapshot WHERE project_id = ?)";
        int nodeCount = jdbcTemplate.update(evictNodeSql,
                STATUS_EVICTED, now, now, projectId, projectId);
        log.info("Evicted {} graph nodes for projectId={}, scanVersionId={}",
                nodeCount, projectId, scanVersionId);

        // 失效向量：source_uri 指向已删除文件路径的向量文档软删除
        String evictVectorSql = "UPDATE lg_vector_document SET deleted = 1 " +
                "WHERE project_id = ? AND deleted = 0 " +
                "AND source_uri IS NOT NULL " +
                "AND source_uri NOT IN (SELECT file_path FROM lg_file_snapshot WHERE project_id = ?)";
        int vectorCount = jdbcTemplate.update(evictVectorSql, projectId, projectId);
        log.info("Evicted {} vector documents for projectId={}, scanVersionId={}",
                vectorCount, projectId, scanVersionId);

        return nodeCount;
    }

    /**
     * 标记删除的文件对应的节点为 TOMBSTONED（墓碑态）。
     * <p>
     * 对比当前文件快照，将 source_path 已不存在的图节点标记为 TOMBSTONED，
     * 记录墓碑原因与时间。节点不会被立即软删除，进入过渡态以便后续重命名恢复或最终失效。
     *
     * @param projectId     项目 ID
     * @param scanVersionId 当前扫描版本 ID
     * @return 被标记为 TOMBSTONED 的节点数量
     */
    public int tombstoneDeletedNodes(String projectId, String scanVersionId) {
        if (projectId == null) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        // 标记墓碑：source_path 不在当前文件快照中，且当前状态非 TOMBSTONED/EVICTED
        String sql = "UPDATE lg_graph_node SET status = ?, tombstone_reason = 'FILE_DELETED', " +
                "tombstoned_at = ?, last_scan_version_id = ?, updated_at = ? " +
                "WHERE project_id = ? AND deleted = 0 " +
                "AND status NOT IN (?, ?) " +
                "AND source_path IS NOT NULL " +
                "AND source_path NOT IN (SELECT file_path FROM lg_file_snapshot WHERE project_id = ?)";
        int count = jdbcTemplate.update(sql,
                STATUS_TOMBSTONED, now, scanVersionId, now,
                projectId, STATUS_TOMBSTONED, STATUS_EVICTED, projectId);
        log.info("Tombstoned {} deleted-file nodes for projectId={}, scanVersionId={}",
                count, projectId, scanVersionId);
        return count;
    }

    /**
     * 标记需要逻辑重扫的节点为 STALE（过期态）。
     * <p>
     * 当依赖变更、引用方变更或外部信号触发逻辑重扫时，将相关节点标记为 STALE，
     * 驱动后续扫描重新抽取这些节点的图谱信息。
     *
     * @param projectId     项目 ID
     * @param scanVersionId 当前扫描版本 ID
     * @param reason        标记为 STALE 的原因（如 DEPENDENCY_CHANGED、REFERENCE_UPDATED）
     * @return 被标记为 STALE 的节点数量
     */
    public int markStaleNodes(String projectId, String scanVersionId, String reason) {
        if (projectId == null) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        String safeReason = reason != null ? reason : "LOGIC_RESCAN_REQUIRED";
        // 标记过期：仅对未删除、非 EVICTED 的节点生效
        String sql = "UPDATE lg_graph_node SET status = ?, tombstone_reason = ?, " +
                "tombstoned_at = ?, last_scan_version_id = ?, updated_at = ? " +
                "WHERE project_id = ? AND deleted = 0 " +
                "AND status <> ? " +
                "AND source_path IS NOT NULL " +
                "AND source_path IN (SELECT file_path FROM lg_file_snapshot WHERE project_id = ?)";
        int count = jdbcTemplate.update(sql,
                STATUS_STALE, safeReason, now, scanVersionId, now,
                projectId, STATUS_EVICTED, projectId);
        log.info("Marked {} nodes as STALE for projectId={}, scanVersionId={}, reason={}",
                count, projectId, scanVersionId, safeReason);
        return count;
    }
}
