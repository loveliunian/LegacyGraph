package io.github.legacygraph.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.FileSnapshot;
import io.github.legacygraph.repository.FileSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件变更检测服务 — 基于文件内容 SHA-256 哈希实现增量扫描。
 * <p>
 * 扫描时记录每个文件的内容哈希到 lg_file_snapshot；重扫时对比哈希，
 * 仅对内容发生变更的文件重新执行抽取，避免重复处理未变更文件。
 * <p>
 * 典型流程：
 * <ol>
 *   <li>扫描前调用 {@link #getUnchangedFiles} 判断是否首次扫描（无历史快照则全量）</li>
 *   <li>调用 {@link #detectChangedFiles} 对比哈希得到变更文件列表（仅比较，不写入）</li>
 *   <li>仅对变更文件执行 ExtractionAdapter</li>
 *   <li>扫描完成后调用 {@link #recordSnapshots} 更新所有文件哈希</li>
 * </ol>
 */
@Slf4j
@Service
public class FileChangeDetector {

    private final FileSnapshotRepository fileSnapshotRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public FileChangeDetector(FileSnapshotRepository fileSnapshotRepository,
                              JdbcTemplate jdbcTemplate) {
        this.fileSnapshotRepository = fileSnapshotRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 计算文本内容的 SHA-256 哈希（64 位十六进制小写）。
     *
     * @param content 文本内容，null 返回 null
     */
    public String computeHash(String content) {
        if (content == null) {
            return null;
        }
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 记录（upsert）单个文件快照：计算 SHA-256 哈希后存入 lg_file_snapshot。
     * 按 (project_id, file_path) 唯一约束：存在则更新，不存在则新增。
     *
     * @param projectId 项目 ID
     * @param filePath  文件相对路径（相对于项目根目录）
     * @param content   文件文本内容
     */
    public void recordFileSnapshot(String projectId, String filePath, String content) {
        if (projectId == null || filePath == null) {
            return;
        }
        String hash = computeHash(content);
        long size = content != null ? content.getBytes(StandardCharsets.UTF_8).length : 0L;
        upsertSnapshot(projectId, filePath, hash, size);
    }

    /**
     * 批量记录文件快照。扫描完成后调用，更新所有候选文件的哈希。
     *
     * @param projectId     项目 ID
     * @param pathToContent 文件相对路径 → 文本内容
     */
    public void recordSnapshots(String projectId, Map<String, String> pathToContent) {
        if (projectId == null || pathToContent == null || pathToContent.isEmpty()) {
            return;
        }
        // 批量 upsert：使用 PostgreSQL INSERT ... ON CONFLICT，将 1222 次往返压缩到 1 次
        List<Object[]> batchParams = new ArrayList<>(pathToContent.size());
        for (Map.Entry<String, String> entry : pathToContent.entrySet()) {
            String filePath = entry.getKey();
            String content = entry.getValue();
            String hash = computeHash(content);
            long size = content != null ? content.getBytes(StandardCharsets.UTF_8).length : 0L;
            batchParams.add(new Object[]{projectId, filePath, hash, size, LocalDateTime.now()});
        }
        String sql = "INSERT INTO lg_file_snapshot (id, project_id, file_path, file_hash, file_size, scanned_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (project_id, file_path) DO UPDATE SET " +
                "file_hash = EXCLUDED.file_hash, file_size = EXCLUDED.file_size, " +
                "scanned_at = EXCLUDED.scanned_at, updated_at = EXCLUDED.updated_at";
        try {
            // 生成 UUID 并批量执行
            List<Object[]> fullParams = new ArrayList<>(batchParams.size());
            LocalDateTime now = LocalDateTime.now();
            for (Object[] p : batchParams) {
                fullParams.add(new Object[]{
                    io.github.legacygraph.util.IdUtil.fastUUID(), p[0], p[1], p[2], p[3], p[4], now, now
                });
            }
            jdbcTemplate.batchUpdate(sql, fullParams);
            log.info("Recorded {} file snapshots for projectId={}", pathToContent.size(), projectId);
        } catch (Exception e) {
            log.warn("Batch upsert file snapshots failed, fallback to row-by-row: {}", e.getMessage());
            for (Map.Entry<String, String> entry : pathToContent.entrySet()) {
                try {
                    recordFileSnapshot(projectId, entry.getKey(), entry.getValue());
                } catch (Exception ex) {
                    log.warn("Failed to record file snapshot: projectId={}, filePath={}, err={}",
                            projectId, entry.getKey(), ex.getMessage());
                }
            }
            log.info("Recorded {} file snapshots for projectId={} (fallback)", pathToContent.size(), projectId);
        }
    }

    /**
     * 对比当前文件内容与已存快照的哈希，返回发生变更的文件相对路径列表。
     * 仅做比较，不写入快照（快照在扫描完成后由 {@link #recordSnapshots} 更新）。
     *
     * @param projectId     项目 ID
     * @param pathToContent 文件相对路径 → 当前文本内容
     * @return 变更文件路径列表（新文件、内容修改均算变更）；无变更返回空列表
     */
    public List<String> detectChangedFiles(String projectId, Map<String, String> pathToContent) {
        if (projectId == null || pathToContent == null || pathToContent.isEmpty()) {
            return Collections.emptyList();
        }
        // 加载该项目所有已存快照（file_path → file_hash）
        Map<String, String> storedHashes = loadStoredHashes(projectId);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : pathToContent.entrySet()) {
            String filePath = entry.getKey();
            String currentHash = computeHash(entry.getValue());
            String stored = storedHashes.get(filePath);
            if (stored == null || !stored.equals(currentHash)) {
                changed.add(filePath);
            }
        }
        return changed;
    }

    /**
     * 获取已记录快照的文件路径集合。
     * 用于判断是否首次扫描（为空则无历史快照，应全量扫描）。
     *
     * @param projectId 项目 ID
     * @return 已记录快照的文件相对路径集合
     */
    public Set<String> getUnchangedFiles(String projectId) {
        if (projectId == null) {
            return Collections.emptySet();
        }
        return loadStoredHashes(projectId).keySet();
    }

    /**
     * 清除项目所有文件快照（全量重扫时使用）。
     *
     * @param projectId 项目 ID
     */
    public void clearSnapshots(String projectId) {
        if (projectId == null) {
            return;
        }
        LambdaQueryWrapper<FileSnapshot> wrapper = new LambdaQueryWrapper<FileSnapshot>()
                .eq(FileSnapshot::getProjectId, projectId);
        int deleted = fileSnapshotRepository.delete(wrapper);
        log.info("Cleared {} file snapshots for projectId={}", deleted, projectId);
    }

    // ==================== 内部方法 ====================

    /** 加载项目所有已存快照：file_path → file_hash */
    private Map<String, String> loadStoredHashes(String projectId) {
        LambdaQueryWrapper<FileSnapshot> wrapper = new LambdaQueryWrapper<FileSnapshot>()
                .eq(FileSnapshot::getProjectId, projectId)
                .select(FileSnapshot::getFilePath, FileSnapshot::getFileHash);
        List<FileSnapshot> snapshots = fileSnapshotRepository.selectList(wrapper);
        Map<String, String> map = new HashMap<>(snapshots.size());
        for (FileSnapshot s : snapshots) {
            map.put(s.getFilePath(), s.getFileHash());
        }
        return map;
    }

    /** 按 (project_id, file_path) upsert 快照 */
    private void upsertSnapshot(String projectId, String filePath, String hash, long size) {
        try {
            LambdaQueryWrapper<FileSnapshot> wrapper = new LambdaQueryWrapper<FileSnapshot>()
                    .eq(FileSnapshot::getProjectId, projectId)
                    .eq(FileSnapshot::getFilePath, filePath);
            FileSnapshot existing = fileSnapshotRepository.selectOne(wrapper);
            if (existing != null) {
                existing.setFileHash(hash);
                existing.setFileSize(size);
                existing.setScannedAt(LocalDateTime.now());
                fileSnapshotRepository.updateById(existing);
            } else {
                FileSnapshot snapshot = FileSnapshot.builder()
                        .projectId(projectId)
                        .filePath(filePath)
                        .fileHash(hash)
                        .fileSize(size)
                        .scannedAt(LocalDateTime.now())
                        .build();
                fileSnapshotRepository.insert(snapshot);
            }
        } catch (Exception e) {
            log.warn("Upsert file snapshot failed: projectId={}, filePath={}, err={}",
                    projectId, filePath, e.getMessage());
        }
    }

    /** 计算 SHA-256 哈希（十六进制小写） */
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
