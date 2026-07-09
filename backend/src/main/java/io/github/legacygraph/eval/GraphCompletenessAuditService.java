package io.github.legacygraph.eval;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.ScanVersionRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图谱完整性审计服务 — 落地 §8.3 的 8 项端到端质量指标。
 * <p>
 * 与 {@link GraphifyQualityService} 的区别：前者评估 Graphify 导入的 benchmark 召回率，
 * 本服务评估 LegacyGraph 自建图谱的结构性完整性（边连通率、端到端链路覆盖度等）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphCompletenessAuditService {

    private final Neo4jGraphDao graphDao;
    private final ScanVersionRepository scanVersionRepository;

    /**
     * 执行完整性审计，返回 8 项指标结果。
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID，为空时取最新
     */
    public AuditResult audit(String projectId, String versionId) {
        String vid = resolveVersionId(projectId, versionId);
        if (vid == null) {
            return new AuditResult(projectId, null, "无扫描版本", List.of());
        }

        List<Metric> metrics = new ArrayList<>(8);

        // 1. Controller 入度覆盖率：被 Feature/Page 通过 EXPOSED_BY/CALLS 指向的 Controller 占比
        metrics.add(controllerInboundCoverage(projectId, vid));

        // 2. Service→Mapper 调用解析率：已解析 CALLS 边 / SERVICE_CALL 事实总数
        metrics.add(serviceMapperCallResolutionRate(projectId, vid));

        // 3. 端到端链路数：存在 Controller→…→Table 完整路径的条数
        metrics.add(endToEndPathCount(projectId, vid));

        // 4. 孤立节点率：无任何边的节点 / 总节点
        metrics.add(orphanNodeRate(projectId, vid));

        // 5. BusinessDomain 连通率：有出边的 BusinessDomain 占比
        metrics.add(businessDomainConnectivity(projectId, vid));

        // 6. 低置信边比例：PENDING_CONFIRM 边 / 总边
        metrics.add(lowConfidenceEdgeRate(projectId, vid));

        // 7. SqlStatement→Table 覆盖率：已连表的 SqlStatement 占比
        metrics.add(sqlStatementTableCoverage(projectId, vid));

        // 8. 外部验证覆盖率：EXTERNAL_VERIFY 来源且 CONFIRMED 的边占比
        metrics.add(externalVerificationCoverage(projectId, vid));

        return new AuditResult(projectId, vid, null, metrics);
    }

    // ==================== 指标实现 ====================

    /**
     * 1. Controller 入度覆盖率 = 被 EXPOSED_BY/CALLS 指向的 Controller / 总 Controller
     */
    private Metric controllerInboundCoverage(String projectId, String versionId) {
        List<GraphNode> controllers = graphDao.queryNodes(
                projectId, versionId, NodeType.Controller.name(), null, null, null, 0);
        int total = controllers.size();
        if (total == 0) {
            return new Metric("Controller 入度覆盖率", 0, 0, "≥95%", "无 Controller 节点");
        }

        // 查询所有指向 Controller 的边（EXPOSED_BY 或 CALLS）
        Set<String> controllersWithInbound = controllers.stream()
                .filter(c -> {
                    // 查找以该 Controller 为 toNodeId 的边
                    List<GraphEdge> inbound = graphDao.queryEdges(
                            projectId, versionId, null, c.getId(), null, null, null, 1);
                    return inbound != null && !inbound.isEmpty();
                })
                .map(GraphNode::getId)
                .collect(Collectors.toSet());

        double rate = (double) controllersWithInbound.size() / total;
        return new Metric("Controller 入度覆盖率", rate, total,
                "≥95%", controllersWithInbound.size() + "/" + total + " Controller 有入边");
    }

    /**
     * 2. Service→Mapper 调用解析率 = 已解析 CALLS 边数 / SERVICE_CALL 事实总数
     * 近似实现：统计 CALLS 边中 toNode 为 Mapper 类型的边数 / Service 节点数（作为近似分母）
     */
    private Metric serviceMapperCallResolutionRate(String projectId, String versionId) {
        // 统计 CALLS 边中目标为 Mapper 的数量
        List<GraphNode> mappers = graphDao.queryNodes(
                projectId, versionId, NodeType.Mapper.name(), null, null, null, 0);
        int resolvedCalls = 0;
        for (GraphNode mapper : mappers) {
            List<GraphEdge> callsToMapper = graphDao.queryEdges(
                    projectId, versionId, EdgeType.CALLS.name(), mapper.getId(), null, null, null, 0);
            if (callsToMapper != null) {
                resolvedCalls += callsToMapper.size();
            }
        }

        // 分母：Service 节点数作为近似（每个 Service 应至少调用一个 Mapper）
        List<GraphNode> services = graphDao.queryNodes(
                projectId, versionId, NodeType.Service.name(), null, null, null, 0);
        int totalServices = services.size();
        if (totalServices == 0) {
            return new Metric("Service→Mapper 调用解析率", 0, 0, "≥60%", "无 Service 节点");
        }

        double rate = Math.min(1.0, (double) resolvedCalls / totalServices);
        return new Metric("Service→Mapper 调用解析率", rate, resolvedCalls,
                "≥60%", resolvedCalls + " CALLS→Mapper / " + totalServices + " Service");
    }

    /**
     * 3. 端到端链路数：Controller→…→Table 完整路径条数
     * 近似实现：统计有 READS/WRITES 边的 Table 节点中，能通过反向路径到达 Controller 的数量
     */
    private Metric endToEndPathCount(String projectId, String versionId) {
        List<GraphNode> tables = graphDao.queryNodes(
                projectId, versionId, NodeType.Table.name(), null, null, null, 0);
        int e2ePaths = 0;
        for (GraphNode table : tables) {
            // 从 Table 反向查找路径，看能否到达 Controller
            List<Neo4jGraphDao.GraphPath> paths = graphDao.findPathsDirected(
                    projectId, versionId,
                    table.getNodeKey(),
                    List.of(EdgeType.READS.name(), EdgeType.WRITES.name(),
                            EdgeType.EXECUTES.name(), EdgeType.CONTAINS.name(),
                            EdgeType.CALLS.name(), EdgeType.HANDLED_BY.name()),
                    io.github.legacygraph.common.FlowDirection.INBOUND,
                    6, 1);
            if (paths != null && !paths.isEmpty()) {
                // 检查路径中是否包含 Controller 节点
                for (Neo4jGraphDao.GraphPath path : paths) {
                    if (path.nodes() != null && path.nodes().stream()
                            .anyMatch(n -> NodeType.Controller.name().equals(n.getNodeType()))) {
                        e2ePaths++;
                        break;
                    }
                }
            }
        }
        return new Metric("端到端链路数", e2ePaths, e2ePaths, "≥5",
                e2ePaths + " 条 Controller→Table 路径");
    }

    /**
     * 4. 孤立节点率 = 无任何边的节点 / 总节点
     */
    private Metric orphanNodeRate(String projectId, String versionId) {
        long totalNodes = graphDao.countNodes(projectId, versionId, null);
        if (totalNodes == 0) {
            return new Metric("孤立节点率", 0, 0, "≤15%", "无节点");
        }
        List<GraphNode> orphans = graphDao.queryDisconnectedNodes(projectId, versionId, 0);
        int orphanCount = orphans != null ? orphans.size() : 0;
        double rate = (double) orphanCount / totalNodes;
        return new Metric("孤立节点率", rate, orphanCount, "≤15%",
                orphanCount + "/" + totalNodes + " 孤立节点");
    }

    /**
     * 5. BusinessDomain 连通率 = 有出边的 BusinessDomain / 总 BusinessDomain
     */
    private Metric businessDomainConnectivity(String projectId, String versionId) {
        List<GraphNode> domains = graphDao.queryNodes(
                projectId, versionId, "BusinessDomain", null, null, null, 0);
        int total = domains.size();
        if (total == 0) {
            return new Metric("BusinessDomain 连通率", 0, 0, "≥50%", "无 BusinessDomain 节点");
        }
        int connected = 0;
        for (GraphNode domain : domains) {
            // 查找从该 BusinessDomain 出发的边
            List<GraphEdge> outbound = graphDao.queryEdges(
                    projectId, versionId, null, null, domain.getId(), null, null, 1);
            if (outbound != null && !outbound.isEmpty()) {
                connected++;
            }
        }
        double rate = (double) connected / total;
        return new Metric("BusinessDomain 连通率", rate, connected,
                "≥50%", connected + "/" + total + " BusinessDomain 有出边");
    }

    /**
     * 6. 低置信边比例 = PENDING_CONFIRM 边 / 总边
     */
    private Metric lowConfidenceEdgeRate(String projectId, String versionId) {
        long totalEdges = graphDao.countEdges(projectId, versionId, null);
        if (totalEdges == 0) {
            return new Metric("低置信边比例", 0, 0, "≤30%", "无边");
        }
        long pendingEdges = graphDao.countEdges(projectId, versionId, NodeStatus.PENDING_CONFIRM.name());
        double rate = (double) pendingEdges / totalEdges;
        return new Metric("低置信边比例", rate, (int) pendingEdges,
                "≤30%", pendingEdges + "/" + totalEdges + " PENDING_CONFIRM");
    }

    /**
     * 7. SqlStatement→Table 覆盖率 = 已连表(READS/WRITES)的 SqlStatement / 总 SqlStatement
     */
    private Metric sqlStatementTableCoverage(String projectId, String versionId) {
        List<GraphNode> sqlNodes = graphDao.queryNodes(
                projectId, versionId, NodeType.SqlStatement.name(), null, null, null, 0);
        int total = sqlNodes.size();
        if (total == 0) {
            return new Metric("SqlStatement→Table 覆盖率", 0, 0, "≥90%", "无 SqlStatement 节点");
        }
        int connected = 0;
        for (GraphNode sql : sqlNodes) {
            // 查找从 SqlStatement 出发的 READS/WRITES 边
            List<GraphEdge> reads = graphDao.queryEdges(
                    projectId, versionId, EdgeType.READS.name(), null, sql.getId(), null, null, 1);
            List<GraphEdge> writes = graphDao.queryEdges(
                    projectId, versionId, EdgeType.WRITES.name(), null, sql.getId(), null, null, 1);
            if ((reads != null && !reads.isEmpty()) || (writes != null && !writes.isEmpty())) {
                connected++;
            }
        }
        double rate = (double) connected / total;
        return new Metric("SqlStatement→Table 覆盖率", rate, connected,
                "≥90%", connected + "/" + total + " SqlStatement 已连表");
    }

    /**
     * 8. 外部验证覆盖率 = sourceType=EXTERNAL_VERIFY 且 status=CONFIRMED 的边 / 总边
     * <p>
     * Neo4jGraphDao.countEdges 仅支持按 status 过滤，故先按 status=CONFIRMED 拉取边，
     * 再在 Java 端过滤 sourceType=EXTERNAL_VERIFY。
     * </p>
     */
    private Metric externalVerificationCoverage(String projectId, String versionId) {
        long totalEdges = graphDao.countEdges(projectId, versionId, null);

        List<GraphEdge> confirmedEdges = graphDao.queryEdges(
                projectId, versionId, null, null, null, null,
                NodeStatus.CONFIRMED.name(), 0);
        int verifiedEdges = (int) confirmedEdges.stream()
                .filter(e -> "EXTERNAL_VERIFY".equals(e.getSourceType()))
                .count();

        if (verifiedEdges == 0) {
            return new Metric("外部验证覆盖率", 0.0, 0, "≥30%", "未启用外部验证");
        }

        double rate = totalEdges > 0 ? (double) verifiedEdges / totalEdges : 0.0;
        return new Metric("外部验证覆盖率", rate, verifiedEdges, "≥30%",
                String.format("已验证 %d / 总计 %d 条边", verifiedEdges, totalEdges));
    }

    // ==================== 辅助 ====================

    private String resolveVersionId(String projectId, String versionId) {
        if (versionId != null && !versionId.isBlank()) {
            return versionId;
        }
        List<ScanVersion> latest = scanVersionRepository.lambdaQuery()
                .eq(ScanVersion::getProjectId, projectId)
                .orderByDesc(ScanVersion::getCreatedAt)
                .last("LIMIT 1")
                .list();
        return latest.isEmpty() ? null : latest.get(0).getId();
    }

    // ==================== 结果模型 ====================

    @Data
    public static class AuditResult {
        private final String projectId;
        private final String versionId;
        private final String error;
        private final List<Metric> metrics;

        public boolean hasError() {
            return error != null;
        }

        public int passedCount() {
            if (metrics == null) return 0;
            return (int) metrics.stream().filter(Metric::isPassed).count();
        }

        public int totalCount() {
            return metrics != null ? metrics.size() : 0;
        }
    }

    @Data
    public static class Metric {
        private final String name;
        private final double value;
        private final int rawCount;
        private final String target;
        private final String detail;

        public boolean isPassed() {
            if (target == null) return true;
            if (target.startsWith("≥")) {
                double threshold = Double.parseDouble(target.substring(1).replace("%", ""));
                return value * 100 >= threshold;
            }
            if (target.startsWith("≤")) {
                double threshold = Double.parseDouble(target.substring(1).replace("%", ""));
                return value * 100 <= threshold;
            }
            return value >= Double.parseDouble(target.replace("≥", "").replace("%", ""));
        }
    }
}
