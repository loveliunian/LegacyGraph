package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.entity.SourceAssetSnapshot;
import io.github.legacygraph.repository.ScanVersionRepository;
import io.github.legacygraph.repository.SourceAssetSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * P3b 增量扫描服务 —— 只扫描变更文件，复用上次扫描结果。
 * <p>
 * 集成入口参考 {@code ProjectScanner.startFullScan}：
 * <ol>
 *   <li>扫描前调用 {@link #detectChangedFiles} 对比当前文件与上次扫描快照（mtime + size），得到变更文件列表；</li>
 *   <li>调用 {@link #markNodesAsStale} 将变更文件对应的图谱节点标记为 STALE，便于后续重扫时只处理 STALE 节点。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
public class IncrementalScanService {

    /** 增量扫描关注的文件扩展名（与 ProjectScanner.isAdapterCandidate 对齐） */
    private static final Set<String> TRACKED_EXTENSIONS = Set.of(
            ".java", ".xml", ".bpmn", ".bpmn20.xml", ".vue", ".jsx", ".tsx", ".ts", ".js",
            ".md", ".pdf", ".docx", ".txt", ".rst", ".adoc", ".html", ".htm");

    /** 排除的构建/依赖目录片段 */
    private static final String[] EXCLUDED_DIR_FRAGMENTS = new String[]{
            "/node_modules/", "/.git/", "/target/", "/dist/", "/build/", "/__pycache__/",
            "/.idea/", "/.vscode/"
    };

    /** 节点 STALE 状态（重扫时按此过滤只处理变更节点） */
    public static final String STALE_STATUS = NodeStatus.STALE.name();

    /** 需要检查 STALE 标记的图谱节点类型 */
    private static final String[] NODE_TYPES_TO_SCAN = new String[]{
            "Controller", "Service", "Mapper", "Method", "SqlStatement",
            "ApiEndpoint", "Table", "Column"
    };

    private final Neo4jGraphDao neo4jGraphDao;
    private final ScanVersionRepository scanVersionRepository;
    private final SourceAssetSnapshotRepository sourceAssetSnapshotRepository;

    public IncrementalScanService(Neo4jGraphDao neo4jGraphDao,
                                  ScanVersionRepository scanVersionRepository,
                                  SourceAssetSnapshotRepository sourceAssetSnapshotRepository) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.scanVersionRepository = scanVersionRepository;
        this.sourceAssetSnapshotRepository = sourceAssetSnapshotRepository;
    }

    /**
     * 对比当前文件列表与上次扫描快照，返回变更的文件路径列表（相对 baseDir 的相对路径）。
     * <p>使用文件最后修改时间 + 大小做快速对比，避免逐文件计算哈希。</p>
     *
     * @param projectId     项目 ID
     * @param baseDir       扫描根目录
     * @param lastVersionId 上次扫描版本 ID（为 null/空或不存在时返回空列表，调用方应回退全量扫描）
     * @return 变更文件相对路径列表（含新增、修改、删除）
     */
    public List<String> detectChangedFiles(String projectId, String baseDir, String lastVersionId) {
        if (baseDir == null || baseDir.isBlank()) {
            return List.of();
        }
        Path root = Paths.get(baseDir);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }
        if (lastVersionId == null || lastVersionId.isBlank()) {
            log.info("Incremental detect: no lastVersionId, fallback to full scan: projectId={}", projectId);
            return List.of();
        }
        ScanVersion lastVersion = scanVersionRepository.getById(lastVersionId);
        if (lastVersion == null) {
            log.warn("Incremental detect: lastVersionId={} not found, fallback to full scan", lastVersionId);
            return List.of();
        }

        Map<String, SourceAssetSnapshot> lastSnapshots = loadLastSnapshots(projectId, lastVersionId);
        log.info("Incremental detect: projectId={}, lastVersionId={}, snapshotCount={}",
                projectId, lastVersionId, lastSnapshots.size());

        List<String> changed = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();

        try (Stream<Path> stream = Files.walk(root, 10)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isTrackedFile)
                    .forEach(file -> {
                        String relativePath;
                        try {
                            relativePath = root.relativize(file.toAbsolutePath()).toString();
                        } catch (IllegalArgumentException e) {
                            relativePath = file.getFileName().toString();
                        }
                        seenPaths.add(relativePath);

                        long lastModified;
                        long size;
                        try {
                            lastModified = Files.getLastModifiedTime(file).toMillis();
                            size = Files.size(file);
                        } catch (IOException e) {
                            log.debug("Skip file (io error): {} — {}", file, e.getMessage());
                            return;
                        }

                        SourceAssetSnapshot snap = lastSnapshots.get(relativePath);
                        if (snap == null) {
                            // 新文件
                            changed.add(relativePath);
                        } else if (snap.getLastModifiedMs() == null || snap.getFileSize() == null
                                || snap.getLastModifiedMs() != lastModified
                                || snap.getFileSize() != size) {
                            // mtime 或 size 变化
                            changed.add(relativePath);
                        }
                    });
        } catch (IOException e) {
            log.warn("detectChangedFiles walk failed for {}: {}", baseDir, e.getMessage());
            return List.of();
        }

        // 已删除文件：快照中有但当前文件系统不存在
        for (String path : lastSnapshots.keySet()) {
            if (!seenPaths.contains(path)) {
                changed.add(path);
            }
        }

        log.info("Incremental detect result: projectId={}, lastVersionId={}, totalChanged={}",
                projectId, lastVersionId, changed.size());
        return changed;
    }

    /**
     * 将变更文件对应的图谱节点标记为 STALE 状态，便于后续重扫时只处理 STALE 节点。
     *
     * @param projectId         项目 ID
     * @param versionId         当前版本 ID
     * @param changedFilePaths  变更文件路径列表（相对路径，与 {@link #detectChangedFiles} 返回值一致）
     */
    public void markNodesAsStale(String projectId, String versionId, List<String> changedFilePaths) {
        if (changedFilePaths == null || changedFilePaths.isEmpty()) {
            return;
        }
        Set<String> changedSet = new HashSet<>(changedFilePaths);
        int marked = 0;
        for (String nodeType : NODE_TYPES_TO_SCAN) {
            List<GraphNode> nodes;
            try {
                nodes = neo4jGraphDao.queryNodes(
                        projectId, versionId, nodeType, null, null, null, 0);
            } catch (Exception e) {
                log.debug("queryNodes failed for type {}: {}", nodeType, e.getMessage());
                continue;
            }
            if (nodes == null || nodes.isEmpty()) {
                continue;
            }
            for (GraphNode node : nodes) {
                String sourcePath = node.getSourcePath();
                if (sourcePath == null || sourcePath.isBlank()) {
                    continue;
                }
                if (matchesAnyChanged(sourcePath, changedSet)) {
                    try {
                        neo4jGraphDao.setNodeProperty(node.getId(), "status", STALE_STATUS);
                        marked++;
                    } catch (Exception e) {
                        log.debug("setNodeProperty(STALE) failed for node {}: {}", node.getId(), e.getMessage());
                    }
                }
            }
        }
        log.info("Marked {} nodes as STALE: projectId={}, versionId={}, changedFiles={}",
                marked, projectId, versionId, changedFilePaths.size());
    }

    /**
     * 判断节点 sourcePath 是否命中任一变更相对路径。
     * sourcePath 通常是绝对路径，变更列表是相对路径，因此用 endsWith / contains 匹配。
     */
    private boolean matchesAnyChanged(String sourcePath, Set<String> changedRelativePaths) {
        for (String rel : changedRelativePaths) {
            if (rel == null || rel.isBlank()) continue;
            if (sourcePath.endsWith(rel) || sourcePath.contains(rel)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 加载上次扫描的资产快照：relativePath -> snapshot
     */
    private Map<String, SourceAssetSnapshot> loadLastSnapshots(String projectId, String lastVersionId) {
        Map<String, SourceAssetSnapshot> map = new HashMap<>();
        try {
            List<SourceAssetSnapshot> snapshots = sourceAssetSnapshotRepository.selectList(
                    new LambdaQueryWrapper<SourceAssetSnapshot>()
                            .eq(SourceAssetSnapshot::getProjectId, projectId)
                            .eq(SourceAssetSnapshot::getVersionId, lastVersionId));
            for (SourceAssetSnapshot s : snapshots) {
                if (s.getRelativePath() != null) {
                    map.put(s.getRelativePath(), s);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load snapshots for lastVersionId={}: {}", lastVersionId, e.getMessage());
        }
        return map;
    }

    private boolean isTrackedFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        String pathStr = path.toString().toLowerCase();
        for (String frag : EXCLUDED_DIR_FRAGMENTS) {
            if (pathStr.contains(frag)) {
                return false;
            }
        }
        for (String ext : TRACKED_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
