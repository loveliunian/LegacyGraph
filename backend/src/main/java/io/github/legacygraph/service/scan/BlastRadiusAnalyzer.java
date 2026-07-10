package io.github.legacygraph.service.scan;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Blast Radius 传播分析服务 — 当文件变更时，通过图遍历找到所有依赖该文件中节点的其他节点。
 *
 * <h3>分析流程</h3>
 * <ol>
 *   <li>{@link #analyzeBlastRadius} — 对每个变更文件，通过 sourcePath 找到文件中的所有节点，
 *       再反向遍历边类型白名单（CALLS/READS/WRITES/BELONGS_TO/DEPENDS_ON/IMPLEMENTS/EXTENDS）
 *       找到所有指向这些节点的入边源节点，即受变更影响的依赖者。</li>
 *   <li>{@link #markAffectedNodes} — 将受影响节点的 affected 标记为 true，并写入 affectedReason。</li>
 *   <li>{@link #getAffectedSubgraph} — 返回受影响节点及其直接邻居构成的子图，用于增量重扫。</li>
 * </ol>
 *
 * <h3>反向遍历边类型语义</h3>
 * <ul>
 *   <li>CALLS — 谁调用了变更文件中的方法</li>
 *   <li>READS/WRITES — 谁读写了这个文件涉及的表</li>
 *   <li>BELONGS_TO — 哪些类属于变更的 Package</li>
 *   <li>DEPENDS_ON — 哪些 Package 依赖了变更的 Package</li>
 *   <li>IMPLEMENTS/EXTENDS — 谁继承/实现了变更的类</li>
 * </ul>
 */
@Slf4j
@Service
public class BlastRadiusAnalyzer {

    /** 反向遍历的边类型白名单 */
    private static final List<String> REVERSE_EDGE_TYPES = List.of(
            EdgeType.CALLS.name(),
            EdgeType.READS.name(),
            EdgeType.WRITES.name(),
            EdgeType.BELONGS_TO.name(),
            EdgeType.DEPENDS_ON.name(),
            EdgeType.IMPLEMENTS.name(),
            EdgeType.EXTENDS.name());

    /** 子图查询时每个受影响节点最多采集的邻居数 */
    private static final int SUBGRAPH_NEIGHBOR_LIMIT = 100;

    private final Neo4jGraphDao graphDao;
    private final FileChangeDetector fileChangeDetector;

    public BlastRadiusAnalyzer(Neo4jGraphDao graphDao, FileChangeDetector fileChangeDetector) {
        this.graphDao = graphDao;
        this.fileChangeDetector = fileChangeDetector;
    }

    // ==================== SubTask 11.1: Blast Radius 传播分析 ====================

    /**
     * 分析变更文件的 Blast Radius（影响范围）。
     *
     * <p>对每个变更文件，通过 sourcePath 属性找到该文件中的所有节点（Class/Method/Field 等），
     * 再从这些节点出发反向遍历边类型白名单，找到所有依赖者。versionId 传 null 表示
     * 查询项目下所有版本。</p>
     *
     * @param projectId         项目 ID
     * @param changedFilePaths  变更文件相对路径列表
     * @return Blast Radius 分析结果，包含受影响节点 ID 列表和传播路径
     */
    public BlastRadiusResult analyzeBlastRadius(String projectId, List<String> changedFilePaths) {
        if (projectId == null || changedFilePaths == null || changedFilePaths.isEmpty()) {
            return BlastRadiusResult.empty();
        }

        Set<String> affectedNodeIds = new LinkedHashSet<>();
        List<PropagationPath> propagationPaths = new ArrayList<>();

        for (String filePath : changedFilePaths) {
            if (filePath == null || filePath.isBlank()) {
                continue;
            }
            // 通过 sourcePath 找到该文件中的所有节点
            List<Map<String, Object>> fileNodes = graphDao.findNodesBySourcePath(projectId, null, filePath);
            if (fileNodes.isEmpty()) {
                continue;
            }

            // 收集节点 ID
            List<String> nodeIds = new ArrayList<>(fileNodes.size());
            Map<String, String> nodeIdToKey = new LinkedHashMap<>();
            for (Map<String, Object> node : fileNodes) {
                String nodeId = str(node.get("nodeId"));
                String nodeKey = str(node.get("nodeKey"));
                if (nodeId != null) {
                    nodeIds.add(nodeId);
                    nodeIdToKey.put(nodeId, nodeKey);
                }
            }
            if (nodeIds.isEmpty()) {
                continue;
            }

            // 反向遍历边类型白名单，找到所有依赖者
            List<Map<String, Object>> dependents = graphDao.findReverseDependents(
                    projectId, null, nodeIds, REVERSE_EDGE_TYPES);
            for (Map<String, Object> dep : dependents) {
                String sourceId = str(dep.get("sourceId"));
                String sourceKey = str(dep.get("sourceKey"));
                String sourceType = str(dep.get("sourceType"));
                String targetId = str(dep.get("targetId"));
                String targetKey = str(dep.get("targetKey"));
                String edgeType = str(dep.get("edgeType"));

                if (sourceId == null) {
                    continue;
                }
                affectedNodeIds.add(sourceId);
                propagationPaths.add(new PropagationPath(
                        targetId, targetKey, sourceId, sourceKey, sourceType, edgeType, filePath));
            }
        }

        log.info("BlastRadiusAnalyzer: analyzed projectId={}, changedFiles={}, affectedNodes={}, paths={}",
                projectId, changedFilePaths.size(), affectedNodeIds.size(), propagationPaths.size());
        return new BlastRadiusResult(new ArrayList<>(affectedNodeIds), propagationPaths);
    }

    /**
     * 将受影响节点的 affected 标记为 true，并写入 affectedReason 描述。
     *
     * @param projectId 项目 ID
     * @param result    Blast Radius 分析结果
     */
    public void markAffectedNodes(String projectId, BlastRadiusResult result) {
        if (projectId == null || result == null || result.affectedNodeIds().isEmpty()) {
            return;
        }
        int marked = 0;
        for (String nodeId : result.affectedNodeIds()) {
            if (nodeId == null) {
                continue;
            }
            try {
                graphDao.setNodeProperty(nodeId, "affected", true);
                String reason = buildAffectedReason(nodeId, result.propagationPaths());
                graphDao.setNodeProperty(nodeId, "affectedReason", reason);
                marked++;
            } catch (Exception e) {
                log.warn("BlastRadiusAnalyzer: markAffectedNodes failed for nodeId={}: {}", nodeId, e.getMessage());
            }
        }
        log.info("BlastRadiusAnalyzer: marked {} affected nodes for projectId={}", marked, projectId);
    }

    // ==================== SubTask 11.2: 受影响子图 ====================

    /**
     * 返回受影响节点及其直接邻居构成的子图，用于增量重扫时仅重建这部分子图。
     *
     * <p>子图包含：
     * <ul>
     *   <li>受影响节点本身</li>
     *   <li>每个受影响节点的直接邻居（1 跳）</li>
     *   <li>上述节点集合之间的所有边</li>
     * </ul></p>
     *
     * @param projectId 项目 ID
     * @param result    Blast Radius 分析结果
     * @return 受影响子图（节点列表 + 边列表）
     */
    public AffectedSubgraph getAffectedSubgraph(String projectId, BlastRadiusResult result) {
        if (projectId == null || result == null || result.affectedNodeIds().isEmpty()) {
            return new AffectedSubgraph(List.of(), List.of());
        }

        // 收集受影响节点及其邻居
        Set<String> allNodeIds = new LinkedHashSet<>(result.affectedNodeIds());

        Map<String, Set<String>> neighborMap = graphDao.findNeighborNodeIdsBySources(
                projectId, result.affectedNodeIds(), SUBGRAPH_NEIGHBOR_LIMIT);
        for (Set<String> neighbors : neighborMap.values()) {
            allNodeIds.addAll(neighbors);
        }

        if (allNodeIds.isEmpty()) {
            return new AffectedSubgraph(List.of(), List.of());
        }

        // 查询节点对象
        List<GraphNode> nodes = graphDao.findNodesByIds(new ArrayList<>(allNodeIds));

        // 查询节点之间的边
        List<Map<String, Object>> edges = graphDao.queryEdgesForNodesByProject(
                projectId, new ArrayList<>(allNodeIds));

        log.info("BlastRadiusAnalyzer: built affected subgraph projectId={}, nodes={}, edges={}",
                projectId, nodes.size(), edges.size());
        return new AffectedSubgraph(nodes, edges);
    }

    // ==================== 辅助方法 ====================

    /** 为指定受影响节点构建 affectedReason 描述字符串 */
    private String buildAffectedReason(String affectedNodeId, List<PropagationPath> paths) {
        List<String> reasons = new ArrayList<>();
        for (PropagationPath p : paths) {
            if (affectedNodeId.equals(p.affectedNodeId())) {
                String changedKey = p.changedNodeKey() != null ? p.changedNodeKey() : p.changedNodeId();
                reasons.add(p.edgeType() + " <- " + changedKey + " (" + p.changedFilePath() + ")");
            }
        }
        if (reasons.isEmpty()) {
            return "affected by blast radius propagation";
        }
        return String.join("; ", reasons);
    }

    /** 安全将 Map 值转为 String */
    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    // ==================== 数据结构 ====================

    /**
     * Blast Radius 分析结果。
     *
     * @param affectedNodeIds   受影响节点 ID 列表（去重，保持插入顺序）
     * @param propagationPaths  传播路径列表，每条记录变更节点→受影响节点的边信息
     */
    public record BlastRadiusResult(List<String> affectedNodeIds, List<PropagationPath> propagationPaths) {
        public static BlastRadiusResult empty() {
            return new BlastRadiusResult(Collections.emptyList(), Collections.emptyList());
        }
    }

    /**
     * 单条传播路径 — 描述一个受影响节点如何依赖变更文件中的节点。
     *
     * @param changedNodeId    变更文件中的节点 ID（被依赖方）
     * @param changedNodeKey   变更文件中的节点 key
     * @param affectedNodeId   受影响节点 ID（依赖方）
     * @param affectedNodeKey  受影响节点 key
     * @param affectedNodeType 受影响节点类型
     * @param edgeType         依赖边类型（CALLS/READS/WRITES/BELONGS_TO/DEPENDS_ON/IMPLEMENTS/EXTENDS）
     * @param changedFilePath  变更文件路径
     */
    public record PropagationPath(String changedNodeId, String changedNodeKey,
                                   String affectedNodeId, String affectedNodeKey,
                                   String affectedNodeType, String edgeType,
                                   String changedFilePath) {}

    /**
     * 受影响子图 — 受影响节点及其直接邻居构成的子图。
     *
     * @param nodes 子图节点列表
     * @param edges 子图边列表，每行包含 {id, type, source, target}
     */
    public record AffectedSubgraph(List<GraphNode> nodes, List<Map<String, Object>> edges) {}
}
