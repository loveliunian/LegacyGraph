package io.github.legacygraph.service.scan;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 架构违规扫描器（v6.0 P7：TECH_DEBT）— 扫描图谱中分层违规与死代码。
 * <p>
 * 违规类型：
 * <ul>
 *   <li>跳层调用：Controller 通过 CALLS 边直达 Mapper，跳过 Service 层</li>
 * </ul>
 * 死代码：
 * <ul>
 *   <li>fanIn=0 且非入口类型（ApiEndpoint/Page）的 Method/Class 节点</li>
 * </ul>
 * 扫描结果写入对应节点的 properties（architectureViolation / deadCode 标记）。
 * </p>
 */
@Slf4j
@Component
public class ArchitectureViolationScanner {

    private final Neo4jGraphDao neo4jGraphDao;

    public ArchitectureViolationScanner(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 架构违规边 — 描述一条 Controller → Mapper 的跳层 CALLS。
     */
    @Data
    public static class ViolationEdge {
        /** Controller 节点 */
        private GraphNode controller;
        /** Mapper 节点 */
        private GraphNode mapper;
        /** 违规 CALLS 边 */
        private GraphEdge edge;
        /** 违规类型描述 */
        private String violationType;

        public ViolationEdge(GraphNode controller, GraphNode mapper, GraphEdge edge, String violationType) {
            this.controller = controller;
            this.mapper = mapper;
            this.edge = edge;
            this.violationType = violationType;
        }
    }

    /**
     * 扫描 Controller → Mapper 跳层调用违规。
     * <p>查询所有 Controller 节点的 CALLS 出边，若目标节点为 Mapper 类型则记为违规。</p>
     *
     * @return 违规边列表
     */
    public List<ViolationEdge> scanArchitectureViolations(String projectId, String versionId) {
        List<ViolationEdge> violations = new ArrayList<>();
        // 查询所有 Controller 节点
        List<GraphNode> controllers = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Controller.name(),
                null, null, null, 0);
        if (controllers == null || controllers.isEmpty()) {
            return violations;
        }

        for (GraphNode controller : controllers) {
            // 查询与该 Controller 关联的 CALLS 边
            List<GraphEdge> callsEdges = neo4jGraphDao.queryEdges(
                    projectId, versionId,
                    "CALLS", null, controller.getId(),
                    null, null, 0);
            if (callsEdges == null || callsEdges.isEmpty()) {
                continue;
            }
            for (GraphEdge edge : callsEdges) {
                // 只看出边（fromNodeId == controller.id）
                if (!controller.getId().equals(edge.getFromNodeId())) {
                    continue;
                }
                // 查目标节点
                GraphNode target = neo4jGraphDao.findNodeById(edge.getToNodeId()).orElse(null);
                if (target == null) {
                    continue;
                }
                // 目标为 Mapper → 跳层违规
                if (NodeType.Mapper.name().equals(target.getNodeType())) {
                    violations.add(new ViolationEdge(
                            controller, target, edge,
                            "LAYER_SKIP: Controller 直接调用 Mapper，跳过 Service 层"));
                }
            }
        }
        log.info("Detected {} architecture violations (Controller->Mapper) for projectId={}, versionId={}",
                violations.size(), projectId, versionId);
        return violations;
    }

    /**
     * 将架构违规标记写入对应节点 properties。
     * <p>在 Controller 节点写入 {@code architectureViolation=true}，
     * 在 Mapper 节点写入 {@code violationReason=layer_skip}。</p>
     *
     * @return 处理的违规数
     */
    public int markViolations(String projectId, String versionId) {
        List<ViolationEdge> violations = scanArchitectureViolations(projectId, versionId);
        for (ViolationEdge v : violations) {
            neo4jGraphDao.setNodeProperty(v.getController().getId(), "architectureViolation", true);
            neo4jGraphDao.setNodeProperty(v.getMapper().getId(), "violationReason", "layer_skip");
        }
        return violations.size();
    }

    /**
     * 扫描死代码（fanIn=0 的孤立节点）并将结果写入节点 properties。
     * <p>查 Method/Class 类型中无入边且非入口类型的节点，写入 {@code deadCode=true}。</p>
     *
     * @return 检测到的死代码节点数
     */
    public int scanAndMarkDeadCode(String projectId, String versionId) {
        int total = 0;
        for (NodeType nt : new NodeType[]{NodeType.Method, NodeType.Service, NodeType.Controller, NodeType.Mapper}) {
            List<GraphNode> isolated = neo4jGraphDao.findIsolatedNodes(projectId, versionId, nt.name());
            if (isolated == null || isolated.isEmpty()) {
                continue;
            }
            for (GraphNode node : isolated) {
                neo4jGraphDao.setNodeProperty(node.getId(), "deadCode", true);
                total++;
            }
        }
        log.info("Detected {} dead code nodes for projectId={}, versionId={}", total, projectId, versionId);
        return total;
    }
}
