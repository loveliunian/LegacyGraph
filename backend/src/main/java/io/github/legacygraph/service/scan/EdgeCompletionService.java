package io.github.legacygraph.service.scan;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 边补全服务 — 通过传递闭包与规则校验补全图谱中的隐式依赖，提高连通性。
 *
 * <h3>补全策略</h3>
 * <ul>
 *   <li><b>传递闭包补全（{@link #completeTransitiveClosure}）</b>：
 *       Package 依赖链 2-3 跳的间接依赖补建 DEPENDS_ON 直接边。
 *       调用链（A--CALLS--&gt;B--CALLS--&gt;C）属正常调用链，不补全直接边以避免噪声。</li>
 *   <li><b>规则校验补全（{@link #completeByRules}）</b>：
 *       Class 缺少 BELONGS_TO 边 → 从 nodeKey(FQN) 解析 package 自动补建；
 *       Table 缺少 HAS_COLUMN / ApiEndpoint 缺少 HANDLED_BY → 标记异常，记录到报告，不自动补全。</li>
 * </ul>
 *
 * <h3>补全边标记约定</h3>
 * <p>所有由本服务补全的边必须满足：
 * <ul>
 *   <li>{@code sourceType = TRANSITIVE_CLOSURE}，与确定性边（CODE_AST 等）区分</li>
 *   <li>{@code confidence = 0.7}，低于确定性边的 1.0</li>
 *   <li>{@code status = PENDING_CONFIRM}，需人工或后续校验确认</li>
 * </ul>
 *
 * <h3>集成方式</h3>
 * <p>由 {@link ScanArtifactPublisher#publish} 在扫描产物发布后调用 {@link #completeAll}，
 * 单独 try/catch，失败不阻塞扫描主流程。</p>
 */
@Slf4j
@Service
public class EdgeCompletionService {

    /** 传递闭包查询的最大跳数区间（避免组合爆炸） */
    private static final int TRANSITIVE_MIN_HOPS = 2;
    private static final int TRANSITIVE_MAX_HOPS = 3;
    /** 单次补全的边数上限，防止超大图谱一次性写入过多 */
    private static final int TRANSITIVE_LIMIT = 100;
    private static final int RULE_SCAN_LIMIT = 2000;

    /** 补全边的统一置信度 */
    private static final BigDecimal COMPLETION_CONFIDENCE = BigDecimal.valueOf(0.7);

    /** 视为「类节点」的 nodeType 集合（无独立 Class 类型，由 Controller/Service/Mapper 等承担） */
    private static final List<String> CLASS_NODE_TYPES = List.of(
            NodeType.Controller.name(),
            NodeType.Service.name(),
            NodeType.Mapper.name(),
            NodeType.ConfigItem.name(),
            NodeType.ExternalSystem.name());

    private final Neo4jGraphDao neo4jGraphDao;

    public EdgeCompletionService(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    // ==================== 对外入口 ====================

    /**
     * 执行全部边补全（传递闭包 + 规则校验），返回汇总报告。
     * 各阶段独立 try/catch，单阶段失败不阻塞另一阶段。
     */
    public CompletionReport completeAll(String projectId, String versionId) {
        CompletionReport report = new CompletionReport(projectId, versionId);
        try {
            completeTransitiveClosure(projectId, versionId, report);
        } catch (Exception e) {
            log.warn("EdgeCompletionService: transitive closure failed (non-blocking) projectId={}, versionId={}: {}",
                    projectId, versionId, e.getMessage());
            report.addAnomaly(new Anomaly("TRANSTITIVE_CLOSURE_ERROR",
                    projectId, "传递闭包补全失败: " + e.getMessage()));
        }
        try {
            completeByRules(projectId, versionId, report);
        } catch (Exception e) {
            log.warn("EdgeCompletionService: rule validation failed (non-blocking) projectId={}, versionId={}: {}",
                    projectId, versionId, e.getMessage());
            report.addAnomaly(new Anomaly("RULE_VALIDATION_ERROR",
                    projectId, "规则校验补全失败: " + e.getMessage()));
        }
        log.info("EdgeCompletionService: completion done projectId={}, versionId={}, transitiveEdges={}, belongsToFixed={}, anomalies={}",
                projectId, versionId, report.getTransitiveEdgesAdded(),
                report.getBelongsToFixed(), report.getAnomalies().size());
        return report;
    }

    // ==================== SubTask 13.1: 传递闭包补全 ====================

    /**
     * 传递闭包补全：补建 Package 间接依赖的直接 DEPENDS_ON 边。
     *
     * <p>规则：若 PackageA --DEPENDS_ON--&gt; PackageB --DEPENDS_ON--&gt; PackageC，
     * 且 PackageA 与 PackageC 之间无直接 DEPENDS_ON 边，则补建。
     * 深度限制 2-3 跳，单次上限 100 条，避免组合爆炸。</p>
     *
     * <p>注意：调用链（A--CALLS--&gt;B--CALLS--&gt;C）是正常调用链，不补全直接边。</p>
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID
     * @return 补全的边数
     */
    public int completeTransitiveClosure(String projectId, String versionId) {
        return completeTransitiveClosure(projectId, versionId, new CompletionReport(projectId, versionId));
    }

    private int completeTransitiveClosure(String projectId, String versionId, CompletionReport report) {
        String normalizedVersionId = Neo4jGraphDao.normalizeId(versionId);
        // 查找 2-3 跳间接依赖且无直接 DEPENDS_ON 的 Package 对
        String cypher = String.format(
                "MATCH p=(a:Package)-[:DEPENDS_ON*%d..%d]->(c:Package) " +
                "WHERE a.projectId = $projectId AND a.versionId = $versionId " +
                "  AND c.versionId = $versionId " +
                "  AND a.id <> c.id " +
                "  AND NOT (a)-[:DEPENDS_ON]->(c) " +
                "RETURN DISTINCT a.id AS fromId, c.id AS toId, " +
                "       a.nodeKey AS fromKey, c.nodeKey AS toKey " +
                "LIMIT $limit",
                TRANSITIVE_MIN_HOPS, TRANSITIVE_MAX_HOPS);

        List<GraphEdge> toCreate = new ArrayList<>();
        List<Map<String, Object>> rows = neo4jGraphDao.executeReadQuery(cypher, Map.of(
                "projectId", projectId,
                "versionId", normalizedVersionId,
                "limit", TRANSITIVE_LIMIT));
        for (Map<String, Object> row : rows) {
            String fromId = (String) row.get("fromId");
            String toId = (String) row.get("toId");
            String fromKey = row.get("fromKey") != null ? (String) row.get("fromKey") : null;
            String toKey = row.get("toKey") != null ? (String) row.get("toKey") : null;
            toCreate.add(buildCompletionEdge(projectId, versionId,
                    fromId, toId, fromKey, toKey,
                    EdgeType.DEPENDS_ON.name(),
                    buildEdgeKey(fromKey, toKey, EdgeType.DEPENDS_ON)));
        }

        int added = 0;
        for (GraphEdge edge : toCreate) {
            try {
                Neo4jGraphDao.EdgeUpsert upsert = neo4jGraphDao.mergeEdge(edge);
                if (upsert.created()) {
                    added++;
                }
            } catch (Exception e) {
                log.debug("EdgeCompletionService: mergeEdge failed for transitive DEPENDS_ON {} -> {}: {}",
                        edge.getFromNodeId(), edge.getToNodeId(), e.getMessage());
            }
        }
        report.setTransitiveEdgesAdded(added);
        log.info("EdgeCompletionService: transitive closure added {} DEPENDS_ON edges (projectId={}, versionId={})",
                added, projectId, versionId);
        return added;
    }

    // ==================== SubTask 13.2: 规则校验补全 ====================

    /**
     * 规则校验补全：
     * <ol>
     *   <li>Class（Controller/Service/Mapper）缺少 BELONGS_TO 边 → 从 nodeKey(FQN) 解析 package，补建 BELONGS_TO</li>
     *   <li>Table 缺少 HAS_COLUMN 边 → 标记异常（不自动补全）</li>
     *   <li>ApiEndpoint 缺少 HANDLED_BY 边 → 标记异常（不自动补全）</li>
     * </ol>
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID
     * @return 补全的 BELONGS_TO 边数
     */
    public int completeByRules(String projectId, String versionId) {
        return completeByRules(projectId, versionId, new CompletionReport(projectId, versionId));
    }

    private int completeByRules(String projectId, String versionId, CompletionReport report) {
        int belongsFixed = fixMissingBelongsTo(projectId, versionId, report);
        detectTablesWithoutColumns(projectId, versionId, report);
        detectApiEndpointsWithoutHandler(projectId, versionId, report);
        report.setBelongsToFixed(belongsFixed);
        return belongsFixed;
    }

    /**
     * 规则 1：查找有 nodeKey 但无 BELONGS_TO 边的类节点，从 FQN 解析 package 补建 BELONGS_TO。
     */
    private int fixMissingBelongsTo(String projectId, String versionId, CompletionReport report) {
        String normalizedVersionId = Neo4jGraphDao.normalizeId(versionId);
        String cypher =
                "MATCH (n) " +
                "WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "  AND n.nodeType IN $classTypes " +
                "  AND n.nodeKey IS NOT NULL " +
                "  AND NOT (n)-[:BELONGS_TO]->() " +
                "RETURN n.id AS nodeId, n.nodeKey AS nodeKey, n.sourcePath AS sourcePath " +
                "LIMIT $limit";

        List<ClassRecord> candidates = new ArrayList<>();
        List<Map<String, Object>> classRows = neo4jGraphDao.executeReadQuery(cypher, Map.of(
                "projectId", projectId,
                "versionId", normalizedVersionId,
                "classTypes", CLASS_NODE_TYPES,
                "limit", RULE_SCAN_LIMIT));
        for (Map<String, Object> row : classRows) {
            candidates.add(new ClassRecord(
                    (String) row.get("nodeId"),
                    (String) row.get("nodeKey"),
                    row.get("sourcePath") != null ? (String) row.get("sourcePath") : null));
        }

        int fixed = 0;
        for (ClassRecord cls : candidates) {
            String pkgName = parsePackageFromNodeKey(cls.nodeKey());
            if (pkgName == null) {
                report.addAnomaly(new Anomaly("CLASS_NO_PACKAGE", cls.nodeId(),
                        "类节点无法解析包名，nodeKey=" + cls.nodeKey()));
                continue;
            }
            String pkgId = findPackageId(projectId, versionId, pkgName);
            if (pkgId == null) {
                report.addAnomaly(new Anomaly("PACKAGE_NOT_FOUND", cls.nodeId(),
                        "未找到 Package 节点，pkg=" + pkgName + "，class=" + cls.nodeKey()));
                continue;
            }
            try {
                GraphEdge edge = buildCompletionEdge(projectId, versionId,
                        cls.nodeId(), pkgId, cls.nodeKey(), pkgName,
                        EdgeType.BELONGS_TO.name(),
                        buildEdgeKey(cls.nodeKey(), pkgName, EdgeType.BELONGS_TO));
                Neo4jGraphDao.EdgeUpsert upsert = neo4jGraphDao.mergeEdge(edge);
                if (upsert.created()) {
                    fixed++;
                }
            } catch (Exception e) {
                log.debug("EdgeCompletionService: mergeEdge BELONGS_TO failed for class {} -> pkg {}: {}",
                        cls.nodeKey(), pkgName, e.getMessage());
            }
        }
        log.info("EdgeCompletionService: fixed {} missing BELONGS_TO edges (projectId={}, versionId={})",
                fixed, projectId, versionId);
        return fixed;
    }

    /**
     * 规则 2：查找无 HAS_COLUMN 边的 Table 节点，标记为异常（不自动补全）。
     */
    private void detectTablesWithoutColumns(String projectId, String versionId, CompletionReport report) {
        String normalizedVersionId = Neo4jGraphDao.normalizeId(versionId);
        String cypher =
                "MATCH (n:Table) " +
                "WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "  AND NOT (n)-[:HAS_COLUMN]->() " +
                "RETURN n.id AS nodeId, n.nodeKey AS nodeKey, n.nodeName AS nodeName " +
                "LIMIT $limit";
        List<Map<String, Object>> tableRows = neo4jGraphDao.executeReadQuery(cypher, Map.of(
                "projectId", projectId,
                "versionId", normalizedVersionId,
                "limit", RULE_SCAN_LIMIT));
        for (Map<String, Object> row : tableRows) {
            report.addAnomaly(new Anomaly("TABLE_NO_COLUMN",
                    (String) row.get("nodeId"),
                    "Table 缺少 HAS_COLUMN 边，table=" + row.get("nodeKey")));
        }
    }

    /**
     * 规则 3：查找无 HANDLED_BY 边的 ApiEndpoint，标记为异常（不自动补全）。
     */
    private void detectApiEndpointsWithoutHandler(String projectId, String versionId, CompletionReport report) {
        String normalizedVersionId = Neo4jGraphDao.normalizeId(versionId);
        String cypher =
                "MATCH (n:ApiEndpoint) " +
                "WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "  AND NOT (n)-[:HANDLED_BY]->() " +
                "RETURN n.id AS nodeId, n.nodeKey AS nodeKey, n.nodeName AS nodeName " +
                "LIMIT $limit";
        List<Map<String, Object>> apiRows = neo4jGraphDao.executeReadQuery(cypher, Map.of(
                "projectId", projectId,
                "versionId", normalizedVersionId,
                "limit", RULE_SCAN_LIMIT));
        for (Map<String, Object> row : apiRows) {
            report.addAnomaly(new Anomaly("API_NO_HANDLER",
                    (String) row.get("nodeId"),
                    "ApiEndpoint 缺少 HANDLED_BY 边，api=" + row.get("nodeKey")));
        }
    }

    // ==================== 辅助方法 ====================

    /** 查找指定 nodeKey 的 Package 节点 ID */
    private String findPackageId(String projectId, String versionId, String pkgName) {
        String normalizedVersionId = Neo4jGraphDao.normalizeId(versionId);
        List<Map<String, Object>> rows = neo4jGraphDao.executeReadQuery(
                "MATCH (pkg:Package {projectId: $projectId, versionId: $versionId, nodeKey: $pkgName}) " +
                "RETURN pkg.id AS pkgId LIMIT 1",
                Map.of("projectId", projectId, "versionId", normalizedVersionId, "pkgName", pkgName));
        if (!rows.isEmpty()) {
            return (String) rows.get(0).get("pkgId");
        }
        return null;
    }

    /**
     * 从类节点的 nodeKey（FQN）解析所属包名。
     * <p>例：{@code com.example.service.OrderService} → {@code com.example.service}；
     * 无包名的简单类名返回 null。</p>
     */
    static String parsePackageFromNodeKey(String nodeKey) {
        if (nodeKey == null || nodeKey.isBlank()) {
            return null;
        }
        int dot = nodeKey.lastIndexOf('.');
        if (dot <= 0) {
            // 无包名（简单类名）或以 . 开头，无法解析
            return null;
        }
        String pkg = nodeKey.substring(0, dot);
        // 包名至少包含一个标识符段，且不含路径分隔符（避免误把文件路径当 FQN）
        if (pkg.contains("/") || pkg.contains("\\")) {
            return null;
        }
        return pkg;
    }

    /**
     * 构造补全边，统一标记 sourceType=TRANSITIVE_CLOSURE, confidence=0.7, status=PENDING_CONFIRM。
     */
    private GraphEdge buildCompletionEdge(String projectId, String versionId,
                                          String fromNodeId, String toNodeId,
                                          String fromKey, String toKey,
                                          String edgeType, String edgeKey) {
        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setEdgeType(edgeType);
        edge.setEdgeKey(edgeKey != null ? edgeKey : "");
        edge.setSourceType(SourceType.TRANSITIVE_CLOSURE.name());
        edge.setConfidence(COMPLETION_CONFIDENCE);
        edge.setStatus(NodeStatus.PENDING_CONFIRM.name());
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        return edge;
    }

    /**
     * 构造边 key，沿用 GraphBuilder 约定：{@code fromKey->edgeType->toKey}。
     */
    private String buildEdgeKey(String fromKey, String toKey, EdgeType edgeType) {
        String f = fromKey != null ? fromKey : "";
        String t = toKey != null ? toKey : "";
        String type = edgeType.name().toLowerCase();
        return f + "->" + type + "->" + t;
    }

    // ==================== 报告数据结构 ====================

    /** 类节点扫描记录（内部使用） */
    private record ClassRecord(String nodeId, String nodeKey, String sourcePath) {}

    /**
     * 边补全报告。
     */
    public static class CompletionReport {
        private final String projectId;
        private final String versionId;
        private int transitiveEdgesAdded;
        private int belongsToFixed;
        private final List<Anomaly> anomalies = new ArrayList<>();

        public CompletionReport(String projectId, String versionId) {
            this.projectId = projectId;
            this.versionId = versionId;
        }

        public String getProjectId() { return projectId; }
        public String getVersionId() { return versionId; }
        public int getTransitiveEdgesAdded() { return transitiveEdgesAdded; }
        void setTransitiveEdgesAdded(int n) { this.transitiveEdgesAdded = n; }
        public int getBelongsToFixed() { return belongsToFixed; }
        void setBelongsToFixed(int n) { this.belongsToFixed = n; }
        public List<Anomaly> getAnomalies() { return anomalies; }
        void addAnomaly(Anomaly a) { anomalies.add(a); }
    }

    /**
     * 异常记录（规则校验发现的问题节点）。
     */
    public record Anomaly(String type, String nodeId, String message) {}
}
