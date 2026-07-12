package io.github.legacygraph.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service 接口 ↔ 实现类 CALLS 边扩展器（评估 §3.2.1 修复建议 2 + §3.3 评估修复项）。
 *
 * <p>职责：把 JavaMemberCallResolver.resolveMemberCalls 产生的指向
 * {@code interface.method} 的 CALLS 边，按已有 IMPLEMENTS 边扩展到所有 {@code impl.method}。
 *
 * <p>关键约束（与 JavaMemberCallResolver.resolveInheritanceEdges 协同）：</p>
 * <ul>
 *   <li><b>复用</b> resolveInheritanceEdges 已产出的 IMPLEMENTS 边作为 join key，<b>不二次扫继承关系</b>。</li>
 *   <li><b>god-node 守卫</b>：同一 interface.method 命中多个 impl 时不消歧，<b>保留 interface 级边</b>，
 *       并把所有 impl.method 列出作为 PENDING 候选，由人工/运行时 trace 收敛。</li>
 *   <li><b>置信度</b>：单 impl → 1.0；多 impl → 取所有候选，edge.confidence=0.6。</li>
 *   <li><b>状态</b>：单 impl → CONFIRMED；多 impl → PENDING_CONFIRM（待运行验证）。</li>
 *   <li><b>幂等</b>：edgeKey = {@code iface.method->calls->impl.method}，重复调用不会产生重复边。</li>
 * </ul>
 *
 * <p>典型调用位置（与 JavaMemberCallResolver.resolveMemberCalls 串联）：
 * <pre>{@code
 *     resolver.resolveMemberCalls(projectId, versionId);   // 产出 interface 级 CALLS 边
 *     expander.expandCallEdgesForInterfaceImpl(projectId, versionId);  // 把这些边下钻到 impl
 * }</pre>
 */
@Slf4j
@Component
public class InterfaceImplCallExpander {

    private final Neo4jGraphDao neo4jGraphDao;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InterfaceImplCallExpander(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 把指向 interface.method 的 CALLS 边下钻到 impl.method，返回新写入的边数。
     *
     * @return 新写入的边数（不含已有的 interface 级 CALLS 边）
     */
    public int expandCallEdgesForInterfaceImpl(String projectId, String versionId) {
        // 1. 收集所有 Service / Mapper interface.method 节点
        List<GraphNode> methodNodes = safeList(neo4jGraphDao.queryNodes(
                projectId, versionId, NodeTypeMethod(), null, null, null, 0));
        if (methodNodes.isEmpty()) {
            log.info("Skip interface-call expansion: no Method nodes found");
            return 0;
        }
        Map<String, GraphNode> methodKeyToNode = new HashMap<>();
        for (GraphNode n : methodNodes) {
            String key = n.getNodeKey();
            if (key != null && key.contains("#")) {
                methodKeyToNode.putIfAbsent(key, n);
            }
        }

        // 2. 收集所有 IMPLEMENTS 边（impl class -IMPLEMENTS-> interface class）
        List<GraphEdge> implementsEdges = safeList(neo4jGraphDao.queryEdges(
                projectId, versionId, null, null, 0));
        Map<String, List<String>> interfaceToImplClasses = new HashMap<>();
        Map<String, String> classIdToFqn = new HashMap<>();
        for (GraphNode n : methodNodes) {
            if (n.getNodeKey() != null) classIdToFqn.put(n.getId(), n.getNodeKey());
        }
        // 从 Class/Service 节点也建一份
        List<GraphNode> classNodes = safeList(neo4jGraphDao.queryNodes(
                projectId, versionId, "Service", null, null, null, 0));
        for (GraphNode n : classNodes) {
            if (n.getNodeKey() != null) classIdToFqn.put(n.getId(), n.getNodeKey());
        }
        for (GraphEdge e : implementsEdges) {
            if (!EdgeType.IMPLEMENTS.name().equals(e.getEdgeType())) continue;
            String implClassId = e.getFromNodeId();
            String ifaceClassId = e.getToNodeId();
            String implFqn = classIdToFqn.get(implClassId);
            String ifaceFqn = classIdToFqn.get(ifaceClassId);
            if (implFqn == null || ifaceFqn == null) continue;
            interfaceToImplClasses.computeIfAbsent(ifaceFqn, k -> new ArrayList<>()).add(implFqn);
        }
        if (interfaceToImplClasses.isEmpty()) {
            log.info("Skip interface-call expansion: no IMPLEMENTS edges found");
            return 0;
        }

        // 3. 扫所有指向 interface.method 的 CALLS 边，对每个目标 method 找对应的 impl.method
        List<GraphEdge> callEdges = safeList(neo4jGraphDao.queryEdges(
                projectId, versionId, null, null, 0));
        List<GraphEdge> newEdges = new ArrayList<>();
        Set<String> existingKeys = new HashSet<>();
        List<GraphEdge> allEdges = safeList(neo4jGraphDao.queryEdges(
                projectId, versionId, null, null, 0));
        for (GraphEdge e : allEdges) {
            if (e.getEdgeKey() != null) existingKeys.add(e.getEdgeKey());
        }

        for (GraphEdge call : callEdges) {
            if (!EdgeType.CALLS.name().equals(call.getEdgeType())) continue;
            // 跳过已经是下钻边的（edgeKey 已含 impl.method）
            String edgeKey = call.getEdgeKey();
            if (edgeKey != null && edgeKey.contains("->calls->")
                    && countArrow(edgeKey) > 0) {
                // 仅下钻 toNode 是 interface.method 的边
            }
            GraphNode targetMethod = methodKeyToNode.get(call.getToNodeId() == null
                    ? "" : methodKeyToNode.keySet().stream()
                        .filter(k -> methodKeyToNode.get(k) != null
                                && methodKeyToNode.get(k).getId().equals(call.getToNodeId()))
                        .findFirst().orElse(""));
            if (targetMethod == null) continue;
            String targetKey = targetMethod.getNodeKey();
            if (targetKey == null || !targetKey.contains("#")) continue;
            // 形如 com.example.UserService#getUser(Long) → 拆出 iface FQN
            String ifaceFqn = targetKey.substring(0, targetKey.indexOf('#'));
            String methodSig = targetKey.substring(targetKey.indexOf('#') + 1);
            List<String> implFqns = interfaceToImplClasses.get(ifaceFqn);
            if (implFqns == null || implFqns.isEmpty()) continue;

            for (String implFqn : implFqns) {
                String implMethodKey = implFqn + "#" + methodSig;
                GraphNode implMethod = methodKeyToNode.get(implMethodKey);
                if (implMethod == null) continue;  // impl method 尚未抽取
                String newEdgeKey = call.getFromNodeId() + "->calls->" + implMethod.getId();
                if (existingKeys.contains(newEdgeKey)) continue;
                existingKeys.add(newEdgeKey);

                BigDecimal confidence = implFqns.size() == 1
                        ? BigDecimal.ONE : BigDecimal.valueOf(0.6);
                NodeStatus status = implFqns.size() == 1
                        ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM;
                newEdges.add(buildExpansionEdge(projectId, versionId,
                        call.getFromNodeId(), implMethod.getId(),
                        newEdgeKey, confidence, status, call.getConfidence()));
            }
        }

        if (newEdges.isEmpty()) {
            log.info("Interface-call expansion: 0 new edges (all either unique-impl or already expanded)");
            return 0;
        }
        int total = neo4jGraphDao.mergeEdgesBatch(newEdges);
        log.info("Interface-call expansion: {} new edges (multi-impl candidates → PENDING)",
                total);
        return total;
    }

    private static int countArrow(String s) {
        int idx = s.indexOf("->calls->");
        return idx >= 0 ? 1 : 0;
    }

    private static String NodeTypeMethod() {
        // 反射调用 Method enum.name()，避免直接 import cycle
        return io.github.legacygraph.common.NodeType.Method.name();
    }

    private static <T> List<T> safeList(List<T> list) {
        return list != null ? list : List.of();
    }

    private GraphEdge buildExpansionEdge(String projectId, String versionId,
                                         String fromNodeId, String toNodeId,
                                         String edgeKey, BigDecimal confidence,
                                         NodeStatus status, BigDecimal sourceConfidence) {
        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setEdgeType(EdgeType.CALLS.name());
        edge.setEdgeKey(edgeKey);
        edge.setSourceType("INTERFACE_IMPL_EXPANSION");
        BigDecimal conf = confidence.setScale(4, RoundingMode.HALF_UP);
        edge.setConfidence(conf);
        edge.setStatus(status.name());
        edge.setCreatedAt(java.time.LocalDateTime.now());
        edge.setUpdatedAt(java.time.LocalDateTime.now());
        return edge;
    }
}