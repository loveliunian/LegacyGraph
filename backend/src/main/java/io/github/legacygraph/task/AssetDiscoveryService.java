package io.github.legacygraph.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.legacygraph.dto.scan.ResolvedScanPlan;
import io.github.legacygraph.entity.SourceAssetSnapshot;
import io.github.legacygraph.extractors.adapter.SourceAsset;
import io.github.legacygraph.repository.SourceAssetSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 资产发现服务 — 从 ResolvedScanPlan 发现 SourceAsset，
 * 支持增量扫描判定（hash/mtime 比较）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetDiscoveryService {

    private final SourceAssetSnapshotRepository snapshotRepository;

    /** 排除目录 */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", ".git", "target", "dist", "build", "__pycache__", ".idea", ".vscode"
    );

    /** 支持扫描的文件扩展名（用于预过滤） */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "java", "xml", "vue", "jsx", "tsx", "js", "ts",
            "md", "pdf", "docx", "txt", "rst", "adoc",
            "yml", "yaml", "properties", "sql"
    );

    /**
     * 发现代码和文档资产。
     */
    public List<SourceAsset> discoverAssets(ResolvedScanPlan plan) {
        List<SourceAsset> assets = new ArrayList<>();
        if (plan.getRepos() == null || plan.getRepos().isEmpty()) {
            return assets;
        }

        for (var repo : plan.getRepos()) {
            Path backendPath = Path.of(repo.getBackendDir());
            Path frontendPath = Path.of(repo.getFrontendDir());

            if (Files.exists(backendPath)) {
                assets.addAll(walkAndBuildAssets(backendPath, backendPath, repo.getRepoId(), plan));
            }
            if (!frontendPath.equals(backendPath) && Files.exists(frontendPath)) {
                assets.addAll(walkAndBuildAssets(frontendPath, frontendPath, repo.getRepoId(), plan));
            }
        }

        // 限制文件数量
        if (assets.size() > plan.getMaxFiles()) {
            log.warn("Asset count {} exceeds maxFiles limit {}, truncating", assets.size(), plan.getMaxFiles());
            return assets.subList(0, plan.getMaxFiles());
        }

        return assets;
    }

    /**
     * 判定资产是否为增量跳过（内容未变且抽取器版本未变）。
     */
    public boolean isIncrementalSkip(SourceAsset asset, String projectId, String versionId) {
        SourceAssetSnapshot prevSnapshot = findPreviousSnapshot(projectId, asset.getRelativePath());
        if (prevSnapshot == null) return false;

        boolean sameHash = Objects.equals(asset.getContentHash(), prevSnapshot.getContentHash());
        boolean sameVersion = Objects.equals(asset.getExtractorVersion(), prevSnapshot.getExtractorVersion());
        return sameHash && sameVersion;
    }

    /**
     * 批量写入资产快照（优化：一次查询现有记录，批量 insert/update）。
     */
    public void persistSnapshots(String projectId, String versionId, List<SourceAsset> assets) {
        if (assets.isEmpty()) return;

        // 一次查询所有现有快照，构建 path→snapshot 索引
        List<SourceAssetSnapshot> existingSnapshots = snapshotRepository.selectList(
                new LambdaQueryWrapper<SourceAssetSnapshot>()
                        .eq(SourceAssetSnapshot::getProjectId, projectId)
                        .eq(SourceAssetSnapshot::getVersionId, versionId));
        Map<String, SourceAssetSnapshot> existingMap = existingSnapshots.stream()
                .collect(Collectors.toMap(SourceAssetSnapshot::getRelativePath, s -> s, (a, b) -> a));

        List<SourceAssetSnapshot> toInsert = new ArrayList<>();
        List<SourceAssetSnapshot> toUpdate = new ArrayList<>();

        for (SourceAsset asset : assets) {
            SourceAssetSnapshot snapshot = toSnapshot(projectId, versionId, asset);
            SourceAssetSnapshot existing = existingMap.get(asset.getRelativePath());
            if (existing != null) {
                snapshot.setId(existing.getId());
                snapshot.setCreatedAt(existing.getCreatedAt());
                toUpdate.add(snapshot);
            } else {
                toInsert.add(snapshot);
            }
        }

        // 批量操作
        for (SourceAssetSnapshot s : toInsert) {
            snapshotRepository.insert(s);
        }
        for (SourceAssetSnapshot s : toUpdate) {
            snapshotRepository.updateById(s);
        }
    }

    /**
     * 发现被删除的资产（旧快照存在、新版本不存在），并标记为 DELETED。
     */
    public List<SourceAssetSnapshot> detectDeletions(String projectId, String versionId,
                                                     Set<String> currentPaths) {
        List<SourceAssetSnapshot> deletions = new ArrayList<>();
        List<SourceAssetSnapshot> allSnapshots = findPreviousVersionSnapshots(projectId);
        for (SourceAssetSnapshot snapshot : allSnapshots) {
            if (!currentPaths.contains(snapshot.getRelativePath())) {
                snapshot.setScanStatus("DELETED");
                snapshot.setUpdatedAt(LocalDateTime.now());
                deletions.add(snapshot);
            }
        }
        return deletions;
    }

    private List<SourceAsset> walkAndBuildAssets(Path root, Path basePath, String repoId, ResolvedScanPlan plan) {
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isExcludedDir(p, root))
                    .filter(p -> hasSupportedExtension(p))
                    .sorted() // 按路径字典序排序，确保 maxFiles 截断确定性
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to walk directory {}: {}", root, e.getMessage());
            return new ArrayList<>();
        }
        List<SourceAsset> assets = new ArrayList<>(paths.size());
        for (Path p : paths) {
            String relativePath = root.relativize(p).toString();
            SourceAsset asset = buildAsset(p, relativePath, repoId);
            assets.add(asset);
        }
        return assets;
    }

    private SourceAsset buildAsset(Path file, String relativePath, String repoId) {
        String ext = getExtension(file);
        String assetKind = classifyAssetKind(ext, relativePath);

        SourceAsset.SourceAssetBuilder builder = SourceAsset.builder()
                .file(file)
                .relativePath(relativePath)
                .fileType(ext)
                .assetKind(assetKind);

        try {
            builder.fileSize(Files.size(file));
            builder.lastModifiedMs(Files.getLastModifiedTime(file).toMillis());
            builder.contentHash(computeSha256(file));
        } catch (IOException e) {
            log.debug("Failed to read file metadata for {}: {}", relativePath, e.getMessage());
        }

        return builder.build();
    }

    private String classifyAssetKind(String ext, String relativePath) {
        if (ext == null) return "CODE";
        return switch (ext.toLowerCase()) {
            case "java", "kt", "groovy" -> "CODE";
            case "vue", "jsx", "tsx", "js", "ts" -> "FRONTEND";
            case "xml" -> relativePath.contains("Mapper") || relativePath.contains("mapper") ? "SQL" : "CONFIG";
            case "yml", "yaml", "properties" -> "CONFIG";
            case "sql" -> "SQL";
            case "md", "pdf", "docx", "txt", "rst", "adoc" -> "DOC";
            default -> "CODE";
        };
    }

    private boolean isExcludedDir(Path path, Path root) {
        for (Path part : root.relativize(path)) {
            if (EXCLUDED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSupportedExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return SUPPORTED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : name.substring(dot + 1).toLowerCase();
    }

    private String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private SourceAssetSnapshot toSnapshot(String projectId, String versionId, SourceAsset asset) {
        SourceAssetSnapshot snapshot = new SourceAssetSnapshot();
        snapshot.setProjectId(projectId);
        snapshot.setVersionId(versionId);
        snapshot.setAssetKind(asset.getAssetKind());
        snapshot.setRelativePath(asset.getRelativePath());
        snapshot.setContentHash(asset.getContentHash());
        snapshot.setFileSize(asset.getFileSize());
        snapshot.setLastModifiedMs(asset.getLastModifiedMs());
        snapshot.setExtractorVersion(asset.getExtractorVersion());
        snapshot.setScanStatus(asset.isDeleted() ? "DELETED" : "SCANNED");
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshot.setUpdatedAt(LocalDateTime.now());
        return snapshot;
    }

    private SourceAssetSnapshot findSnapshotByPath(String projectId, String versionId, String relativePath) {
        List<SourceAssetSnapshot> results = snapshotRepository.selectList(
                new LambdaQueryWrapper<SourceAssetSnapshot>()
                        .eq(SourceAssetSnapshot::getProjectId, projectId)
                        .eq(SourceAssetSnapshot::getVersionId, versionId)
                        .eq(SourceAssetSnapshot::getRelativePath, relativePath)
        );
        return results.isEmpty() ? null : results.get(0);
    }

    private SourceAssetSnapshot findPreviousSnapshot(String projectId, String relativePath) {
        List<SourceAssetSnapshot> results = snapshotRepository.selectList(
                new LambdaQueryWrapper<SourceAssetSnapshot>()
                        .eq(SourceAssetSnapshot::getProjectId, projectId)
                        .eq(SourceAssetSnapshot::getRelativePath, relativePath)
                        .orderByDesc(SourceAssetSnapshot::getCreatedAt)
                        .last("LIMIT 1")
        );
        return results.isEmpty() ? null : results.get(0);
    }

    private List<SourceAssetSnapshot> findPreviousVersionSnapshots(String projectId) {
        return snapshotRepository.selectList(
                new LambdaQueryWrapper<SourceAssetSnapshot>()
                        .eq(SourceAssetSnapshot::getProjectId, projectId)
        );
    }
}
