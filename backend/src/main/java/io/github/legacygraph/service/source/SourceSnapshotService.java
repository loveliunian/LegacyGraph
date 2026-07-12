package io.github.legacygraph.service.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.source.SourceDescriptor;
import io.github.legacygraph.entity.SourceSnapshot;
import io.github.legacygraph.repository.SourceSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * G-02: SourceSnapshotService — 资料源不可变快照服务。
 * <p>
 * 在扫描版本启动时为所有发现的资料源创建不可变快照，记录"扫描时看到了什么"。
 * 快照写入后不可修改，仅可查询。用于：
 * </p>
 * <ul>
 *   <li>版本间 diff：对比两次扫描的资料源差异</li>
 *   <li>审计追溯：回溯某次扫描使用了哪些代码/文档/数据库</li>
 *   <li>增量扫描辅助：与 {@link io.github.legacygraph.service.scan.FileChangeDetector} 互补</li>
 * </ul>
 */
@Slf4j
@Service
public class SourceSnapshotService {

    private final SourceSnapshotRepository sourceSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public SourceSnapshotService(SourceSnapshotRepository sourceSnapshotRepository,
                                  ObjectMapper objectMapper,
                                  JdbcTemplate jdbcTemplate) {
        this.sourceSnapshotRepository = sourceSnapshotRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 为扫描版本创建资料源快照（批量）。
     * <p>快照不可变：同一 versionId 重复调用时跳过已存在的快照。</p>
     *
     * @param projectId   项目 ID
     * @param versionId   扫描版本 ID
     * @param descriptors 资料源描述符列表
     * @return 实际写入的快照数
     */
    public int createSnapshots(String projectId, String versionId, List<SourceDescriptor> descriptors) {
        if (projectId == null || versionId == null || descriptors == null || descriptors.isEmpty()) {
            return 0;
        }
        // 检查是否已有快照（不可变：已存在则跳过）
        long existing = countSnapshots(projectId, versionId);
        if (existing > 0) {
            log.info("SourceSnapshot already exists for projectId={}, versionId={} ({}), skip",
                    projectId, versionId, existing);
            return 0;
        }
        int created = 0;
        LocalDateTime now = LocalDateTime.now();
        for (SourceDescriptor descriptor : descriptors) {
            try {
                SourceSnapshot snapshot = SourceSnapshot.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .sourceType(descriptor.getSourceType() != null ? descriptor.getSourceType() : "UNKNOWN")
                        .descriptorJson(serializeDescriptor(descriptor))
                        .contentHash(descriptor.getContentHash())
                        .contentSize(parseSize(descriptor.getSize()))
                        .snapshotTime(now)
                        .createdAt(now)
                        .build();
                sourceSnapshotRepository.insert(snapshot);
                created++;
            } catch (Exception e) {
                log.warn("Failed to create source snapshot: projectId={}, sourceType={}, err={}",
                        projectId, descriptor.getSourceType(), e.getMessage());
            }
        }
        log.info("Created {} source snapshots for projectId={}, versionId={}", created, projectId, versionId);
        return created;
    }

    /**
     * 查询扫描版本的资料源快照列表。
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID
     * @return 快照列表（按 sourceType 排序）
     */
    public List<SourceSnapshot> getSnapshots(String projectId, String versionId) {
        if (projectId == null || versionId == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<SourceSnapshot> wrapper = new LambdaQueryWrapper<SourceSnapshot>()
                .eq(SourceSnapshot::getProjectId, projectId)
                .eq(SourceSnapshot::getVersionId, versionId)
                .orderByAsc(SourceSnapshot::getSourceType);
        return sourceSnapshotRepository.selectList(wrapper);
    }

    /**
     * 按资料源类型查询快照。
     */
    public List<SourceSnapshot> getSnapshotsByType(String projectId, String versionId, String sourceType) {
        if (projectId == null || versionId == null || sourceType == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<SourceSnapshot> wrapper = new LambdaQueryWrapper<SourceSnapshot>()
                .eq(SourceSnapshot::getProjectId, projectId)
                .eq(SourceSnapshot::getVersionId, versionId)
                .eq(SourceSnapshot::getSourceType, sourceType)
                .orderByAsc(SourceSnapshot::getSnapshotTime);
        return sourceSnapshotRepository.selectList(wrapper);
    }

    /**
     * 统计扫描版本的快照数。
     */
    public long countSnapshots(String projectId, String versionId) {
        if (projectId == null || versionId == null) {
            return 0;
        }
        LambdaQueryWrapper<SourceSnapshot> wrapper = new LambdaQueryWrapper<SourceSnapshot>()
                .eq(SourceSnapshot::getProjectId, projectId)
                .eq(SourceSnapshot::getVersionId, versionId);
        return sourceSnapshotRepository.selectCount(wrapper);
    }

    /**
     * 反序列化快照中的 SourceDescriptor。
     */
    public SourceDescriptor deserializeSnapshot(SourceSnapshot snapshot) {
        if (snapshot == null || snapshot.getDescriptorJson() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot.getDescriptorJson(), SourceDescriptor.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize SourceDescriptor: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 内部方法 ====================

    /**
     * S1-T1: 查询 file_snapshot 只读归档视图。
     * <p>外部查询 file_snapshot 统一走 lg_file_snapshot_archive 视图（只读），
     * 不直接访问 lg_file_snapshot 表（仅供 FileChangeDetector 内部写入）。</p>
     *
     * @param projectId 项目 ID
     * @return 归档文件快照列表（file_path → file_hash 等字段）
     */
    public List<Map<String, Object>> queryFileSnapshotArchive(String projectId) {
        if (projectId == null) {
            return Collections.emptyList();
        }
        try {
            return jdbcTemplate.queryForList(
                    "SELECT file_path, file_hash, file_size, scanned_at, " +
                    "extractor_version, embedding_model, change_type, last_seen_at " +
                    "FROM lg_file_snapshot_archive WHERE project_id = ? ORDER BY file_path",
                    projectId
            );
        } catch (Exception e) {
            log.warn("Failed to query file_snapshot_archive view: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * S1-T1: 统计归档文件快照数。
     */
    public long countFileSnapshotArchive(String projectId) {
        if (projectId == null) {
            return 0;
        }
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM lg_file_snapshot_archive WHERE project_id = ?",
                    Long.class,
                    projectId
            );
            return count != null ? count : 0;
        } catch (Exception e) {
            log.warn("Failed to count file_snapshot_archive: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * S1-T1: 检查 file_snapshot 归档视图是否可用。
     * <p>用于启动时验证 V86 迁移已执行。</p>
     */
    public boolean isArchiveViewAvailable() {
        try {
            jdbcTemplate.queryForObject("SELECT 1 FROM lg_file_snapshot_archive LIMIT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String serializeDescriptor(SourceDescriptor descriptor) {
        try {
            return objectMapper.writeValueAsString(descriptor);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize SourceDescriptor: {}", e.getMessage());
            return "{}";
        }
    }

    private Long parseSize(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(size.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
