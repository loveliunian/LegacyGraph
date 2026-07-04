package io.github.legacygraph.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.dto.scan.ResolvedDocScope;
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
        Map<String, SourceAsset> assets = new LinkedHashMap<>();
        boolean scanCode = shouldScanType(plan, "CODE_SCAN");
        boolean scanDocs = shouldScanType(plan, "DOC_PARSE");

        if (scanDocs) {
            addExplicitDocumentAssets(plan, assets);
        }

        if (plan.getRepos() != null && !plan.getRepos().isEmpty()) {
            for (var repo : plan.getRepos()) {
                Path backendPath = Path.of(repo.getBackendDir());
                Path frontendPath = Path.of(repo.getFrontendDir());

                if (Files.exists(backendPath)) {
                    addAssets(assets, walkAndBuildAssets(backendPath, scanCode, scanDocs));
                }
                if (!frontendPath.equals(backendPath) && Files.exists(frontendPath)) {
                    addAssets(assets, walkAndBuildAssets(frontendPath, scanCode, scanDocs));
                }
            }
        }

        return limitAssets(new ArrayList<>(assets.values()), plan);
    }

    private void addExplicitDocumentAssets(ResolvedScanPlan plan, Map<String, SourceAsset> assets) {
        if (plan.getDocuments() == null || plan.getDocuments().isEmpty()) {
            return;
        }
        for (ResolvedDocScope doc : plan.getDocuments()) {
            if (doc.getFilePath() == null || doc.getFilePath().isBlank()) {
                continue;
            }
            Path file = Path.of(doc.getFilePath());
            if (!Files.exists(file) || !Files.isRegularFile(file) || !hasSupportedExtension(file)) {
                continue;
            }
            String relativePath = doc.getDocName() != null && !doc.getDocName().isBlank()
                    ? doc.getDocName()
                    : file.getFileName().toString();
            SourceAsset asset = buildAsset(file, relativePath);
            if ("DOC".equals(asset.getAssetKind())) {
                assets.put(assetKey(asset), asset);
            }
        }
    }

    private void addAssets(Map<String, SourceAsset> assets, List<SourceAsset> discovered) {
        for (SourceAsset asset : discovered) {
            assets.putIfAbsent(assetKey(asset), asset);
        }
    }

    private List<SourceAsset> limitAssets(List<SourceAsset> assets, ResolvedScanPlan plan) {
        int maxFiles = plan.getMaxFiles() > 0 ? plan.getMaxFiles() : Integer.MAX_VALUE;
        int maxDocs = plan.getMaxDocs() > 0 ? plan.getMaxDocs() : Integer.MAX_VALUE;
        List<SourceAsset> limited = new ArrayList<>();
        int docCount = 0;

        for (SourceAsset asset : assets) {
            if ("DOC".equals(asset.getAssetKind())) {
                docCount++;
                if (docCount > maxDocs) {
                    continue;
                }
            }
            if (limited.size() >= maxFiles) {
                break;
            }
            limited.add(asset);
        }
        if (assets.size() > limited.size()) {
            log.warn("Asset count {} exceeds scan limits maxFiles={}, maxDocs={}, truncating",
                    assets.size(), plan.getMaxFiles(), plan.getMaxDocs());
        }
        return limited;
    }

    private boolean shouldScanType(ResolvedScanPlan plan, String scanType) {
        return plan.getScanTypes() == null
                || plan.getScanTypes().isEmpty()
                || plan.getScanTypes().contains(scanType);
    }

    private boolean shouldIncludeAsset(SourceAsset asset, boolean scanCode, boolean scanDocs) {
        if ("DOC".equals(asset.getAssetKind())) {
            return scanDocs;
        }
        return scanCode;
    }

    private String assetKey(SourceAsset asset) {
        if (asset.getFile() != null) {
            return asset.getFile().toAbsolutePath().normalize().toString();
        }
        return asset.getRelativePath();
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
        List<SourceAssetSnapshot> allSnapshots = findPreviousVersionSnapshots(projectId, versionId);
        for (SourceAssetSnapshot snapshot : allSnapshots) {
            if (!currentPaths.contains(snapshot.getRelativePath())) {
                snapshot.setScanStatus("DELETED");
                snapshot.setUpdatedAt(LocalDateTime.now());
                deletions.add(snapshot);
            }
        }
        return deletions;
    }

    private List<SourceAsset> walkAndBuildAssets(Path root, boolean scanCode, boolean scanDocs) {
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
            SourceAsset asset = buildAsset(p, relativePath);
            if (shouldIncludeAsset(asset, scanCode, scanDocs)) {
                assets.add(asset);
            }
        }
        return assets;
    }

    private SourceAsset buildAsset(Path file, String relativePath) {
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

    /**
     * M12 修复：流式计算 SHA-256，避免大文件全量读取导致 OOM。
     * 使用 8KB 缓冲区逐块 digest，内存占用恒定。
     */
    private String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (java.io.InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
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

    /**
     * M24 修复：仅查询上一个版本（按 createdAt 取最新非当前版本）的快照，
     * 避免随着版本增多数据量线性增长。
     * @param projectId 项目 ID
     * @param currentVersionId 当前版本 ID（排除）
     */
    private List<SourceAssetSnapshot> findPreviousVersionSnapshots(String projectId, String currentVersionId) {
        // 先找到上一个版本 ID（按 createdAt DESC，排除当前版本，取第一条的 versionId）
        List<SourceAssetSnapshot> latest = snapshotRepository.selectList(
                new LambdaQueryWrapper<SourceAssetSnapshot>()
                        .eq(SourceAssetSnapshot::getProjectId, projectId)
                        .ne(SourceAssetSnapshot::getVersionId, currentVersionId)
                        .select(SourceAssetSnapshot::getVersionId)
                        .orderByDesc(SourceAssetSnapshot::getCreatedAt)
                        .last("LIMIT 1")
        );
        if (latest.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        String prevVersionId = latest.get(0).getVersionId();
        return snapshotRepository.selectList(
                new LambdaQueryWrapper<SourceAssetSnapshot>()
                        .eq(SourceAssetSnapshot::getProjectId, projectId)
                        .eq(SourceAssetSnapshot::getVersionId, prevVersionId)
        );
    }
}
