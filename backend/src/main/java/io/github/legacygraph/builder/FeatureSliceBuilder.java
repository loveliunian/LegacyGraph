package io.github.legacygraph.builder;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 功能切片构建器。
 * <p>
 * 从统一图谱中查询节点和边，构建完整的功能切片路径：
 * </p>
 * <pre>
 * Feature → Page → ApiEndpoint → Method → SqlStatement → Table
 *         ↓ Button             ↓ Service   ↓ Mapper
 *         ↓ Permission         ↓ Rule      ↓ Column
 * </pre>
 *
 * <p>FeatureSlice 不是新建第三套图，而是总图上的投影视图。</p>
 */
@Slf4j
@Component
public class FeatureSliceBuilder {

    private final Neo4jGraphDao neo4jGraphDao;

    public FeatureSliceBuilder(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 从图谱中为指定功能构建完整切片（别名，语义更清晰）。
     * <p>等价于 {@link #buildSlice(String, String, String)}。</p>
     */
    public FeatureSlice buildSliceByFeatureName(String projectId, String versionId, String featureName) {
        return buildSlice(projectId, versionId, featureName);
    }

    /**
     * 从图谱中为指定功能构建完整切片。
     *
     * @param projectId   项目ID
     * @param versionId   版本ID
     * @param featureName 功能名称
     * @return 构建的功能切片
     */
    public FeatureSlice buildSlice(String projectId, String versionId, String featureName) {
        Optional<GraphNode> featureNode = findFeatureNode(projectId, versionId, featureName);
        if (featureNode.isEmpty()) {
            return missingSlice(projectId, versionId, featureName);
        }
        return buildSliceFromFeatureNode(projectId, versionId, featureNode.get(), featureName);
    }

    /**
     * 按 Feature 节点 ID 构建切片详情。
     */
    public FeatureSlice buildSliceById(String projectId, String sliceId) {
        Optional<GraphNode> featureNode = neo4jGraphDao.findNodeById(sliceId);
        if (featureNode.isEmpty()
                || !projectId.equals(featureNode.get().getProjectId())
                || !NodeType.Feature.name().equals(featureNode.get().getNodeType())) {
            return missingSlice(projectId, null, sliceId);
        }
        GraphNode node = featureNode.get();
        String featureName = node.getDisplayName() != null && !node.getDisplayName().isBlank()
                ? node.getDisplayName() : node.getNodeName();
        return buildSliceFromFeatureNode(projectId, node.getVersionId(), node, featureName);
    }

    private FeatureSlice missingSlice(String projectId, String versionId, String featureName) {
        return FeatureSlice.builder()
                .sliceId(featureName)
                .projectId(projectId)
                .versionId(versionId)
                .name(featureName)
                .featureName(featureName)
                .status("NO_FEATURE_NODE")
                .coverageStatus("UNCOVERED")
                .riskLevel("HIGH")
                .confidence(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private FeatureSlice buildSliceFromFeatureNode(String projectId, String versionId,
                                                  GraphNode featureNode, String featureName) {
        FeatureSlice.FeatureSliceBuilder builder = FeatureSlice.builder()
                .sliceId(featureNode.getId())
                .projectId(projectId)
                .versionId(versionId)
                .name(featureName)
                .featureName(featureName)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());

        // ========== 第2层：Feature → Page（通过 EXPOSED_BY 边） ==========
        List<GraphNode> pages = findConnectedNodes(projectId, versionId,
                featureNode.getId(), EdgeType.EXPOSED_BY.name(), true);
        List<String> pageIds = pages.stream().map(GraphNode::getId).toList();
        builder.pageIds(pageIds);
        if (!pageIds.isEmpty()) {
            builder.entryPage(pages.get(0).getDisplayName());
        }

        // ========== 第3层：Page → ApiEndpoint（通过 CALLS 边） ==========
        List<String> allApiIds = new ArrayList<>();
        for (GraphNode page : pages) {
            List<GraphNode> apis = findConnectedNodes(projectId, versionId,
                    page.getId(), EdgeType.CALLS.name(), true);
            allApiIds.addAll(apis.stream().map(GraphNode::getId).toList());
        }
        builder.apiIds(allApiIds);

        // ========== 第4层：ApiEndpoint → Method（通过 HANDLED_BY 边） ==========
        List<String> allMethodIds = new ArrayList<>();
        for (String apiId : allApiIds) {
            List<GraphNode> methods = findConnectedNodes(projectId, versionId,
                    apiId, EdgeType.HANDLED_BY.name(), true);
            allMethodIds.addAll(methods.stream().map(GraphNode::getId).toList());
        }
        builder.methodIds(allMethodIds);

        // ========== 第5层：Method → SqlStatement（通过 EXECUTES 边） ==========
        List<String> allSqlIds = new ArrayList<>();
        for (String methodId : allMethodIds) {
            List<GraphNode> sqlNodes = findConnectedNodes(projectId, versionId,
                    methodId, EdgeType.EXECUTES.name(), true);
            allSqlIds.addAll(sqlNodes.stream().map(GraphNode::getId).toList());
        }
        builder.sqlIds(allSqlIds);

        // ========== 第6层：SqlStatement → Table（通过 READS/WRITES 边） ==========
        List<String> allTableIds = new ArrayList<>();
        for (String sqlId : allSqlIds) {
            for (String edgeType : List.of(EdgeType.READS.name(), EdgeType.WRITES.name(), EdgeType.JOINS.name())) {
                List<GraphNode> tables = findConnectedNodes(projectId, versionId,
                        sqlId, edgeType, true);
                allTableIds.addAll(tables.stream().map(GraphNode::getId).toList());
            }
        }
        builder.tableIds(allTableIds.stream().distinct().toList());

        // ========== 第7层：ApiEndpoint → Permission（通过 REQUIRES_PERMISSION 边） ==========
        List<String> allPermIds = new ArrayList<>();
        for (String apiId : allApiIds) {
            List<GraphNode> perms = findConnectedNodes(projectId, versionId,
                    apiId, EdgeType.REQUIRES_PERMISSION.name(), true);
            allPermIds.addAll(perms.stream().map(GraphNode::getId).toList());
        }
        builder.permissionIds(allPermIds);

        // ========== 计算覆盖状态和风险 ==========
        int totalLayers = 6; // Page, API, Method, SQL, Table, Permission
        int coveredLayers = 0;
        if (!pageIds.isEmpty()) coveredLayers++;
        if (!allApiIds.isEmpty()) coveredLayers++;
        if (!allMethodIds.isEmpty()) coveredLayers++;
        if (!allSqlIds.isEmpty()) coveredLayers++;
        if (!allTableIds.isEmpty()) coveredLayers++;
        if (!allPermIds.isEmpty()) coveredLayers++;

        double coverageRatio = (double) coveredLayers / totalLayers;
        String coverageStatus = coverageRatio >= 0.8 ? "COVERED"
                : coverageRatio >= 0.3 ? "PARTIAL" : "UNCOVERED";
        String riskLevel = coverageRatio < 0.5 ? "HIGH"
                : coverageRatio < 0.8 ? "MEDIUM" : "LOW";

        BigDecimal confidence = BigDecimal.valueOf(coverageRatio * 0.9)
                .setScale(2, RoundingMode.HALF_UP);

        List<String> sources = new ArrayList<>();
        if (!pages.isEmpty()) sources.add("FRONTEND");
        if (!allApiIds.isEmpty()) sources.add("BACKEND");
        if (!allSqlIds.isEmpty()) sources.add("SQL");
        if (!allTableIds.isEmpty()) sources.add("DATABASE");

        builder.coverageStatus(coverageStatus)
                .riskLevel(riskLevel)
                .confidence(confidence)
                .status("ACTIVE")
                .evidenceSources(sources)
                .updatedAt(LocalDateTime.now());

        log.info("Built feature slice: name={}, coverage={}, risk={}, layers={}/{}",
                featureName, coverageStatus, riskLevel, coveredLayers, totalLayers);

        return builder.build();
    }

    /**
     * 批量构建所有功能的切片。
     */
    public List<FeatureSlice> buildAllSlices(String projectId, String versionId) {
        List<FeatureSlice> slices = new ArrayList<>();

        // 查询所有 Feature 节点
        List<GraphNode> features = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Feature.name(),
                null, null, null, 100);

        for (GraphNode feature : features) {
            String featureName = feature.getDisplayName() != null
                    ? feature.getDisplayName() : feature.getNodeName();
            try {
                FeatureSlice slice = buildSlice(projectId, versionId, featureName);
                slices.add(slice);
            } catch (Exception e) {
                log.warn("Failed to build slice for feature {}: {}", featureName, e.getMessage());
            }
        }

        log.info("Built {} feature slices for project={}, version={}",
                slices.size(), projectId, versionId);
        return slices;
    }

    // ========== 内部辅助方法 ==========

    private Optional<GraphNode> findFeatureNode(String projectId, String versionId, String featureName) {
        // 先精确查找
        List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Feature.name(),
                featureName, null, null, null, 1);
        if (!nodes.isEmpty()) return Optional.of(nodes.get(0));

        // 再模糊查找
        List<GraphNode> all = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Feature.name(),
                null, null, null, null, 500);
        for (GraphNode node : all) {
            String name = node.getNodeName() != null ? node.getNodeName().toLowerCase() : "";
            String display = node.getDisplayName() != null ? node.getDisplayName().toLowerCase() : "";
            String query = featureName.toLowerCase();
            if (name.contains(query) || display.contains(query)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /**
     * 查找从指定节点出发、沿特定边类型连接的目标节点。
     *
     * @param outgoing true=出边（from→to），false=入边（to→from）
     */
    private List<GraphNode> findConnectedNodes(String projectId, String versionId,
                                                String nodeId, String edgeType,
                                                boolean outgoing) {
        List<GraphEdge> edges = neo4jGraphDao.queryEdges(projectId, versionId,
                edgeType, null, outgoing ? nodeId : null,
                null, null, 50);

        List<GraphNode> nodes = new ArrayList<>();
        for (GraphEdge edge : edges) {
            String targetId = outgoing ? edge.getToNodeId() : edge.getFromNodeId();
            if (outgoing && !nodeId.equals(edge.getFromNodeId())) continue;
            if (!outgoing && !nodeId.equals(edge.getToNodeId())) continue;
            neo4jGraphDao.findNodeById(targetId).ifPresent(nodes::add);
        }
        return nodes;
    }
}
