package io.github.legacygraph.builder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.FactRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.legacygraph.util.IdUtil;

/**
 * Java 成员调用二次扫描解析器（参考 graphify v8 C#/Ruby member-call resolver 模式）。
 *
 * <p>逐文件 {@code ServiceCallExtractor} + {@code GraphBuilder.buildServiceCallGraph} 有两个缺陷：
 * <ol>
 *   <li>{@code CallRelation.targetClass} 是简单名，类节点 nodeKey 是 FQN，findOrCreateNodeByClass
 *       精确查找 miss 即创建重复简单名节点，而非连到真节点；</li>
 *   <li>逐文件跑时目标 Method 节点（在别的文件）可能还没抽取，方法级 CALLS 边漏。</li>
 * </ol>
 *
 * <p>本解析器在所有 Java 文件抽取完成后跑：建全局定义索引，把 SERVICE_CALL 事实里的简单名
 * targetClass 解析成 FQN，用 god-node guard（歧义/缺失一律跳过，不留错边）找到目标 Method/类节点，
 * 批量 MERGE CALLS 边。纯增量——只在去重集里没有的 (fromId,toId) 上加边。</p>
 */
@Slf4j
@Service
public class JavaMemberCallResolver {

    private final Neo4jGraphDao neo4jGraphDao;
    private final FactRepository factRepository;
    private final ObjectMapper objectMapper;

    public JavaMemberCallResolver(Neo4jGraphDao neo4jGraphDao,
                                  FactRepository factRepository,
                                  ObjectMapper objectMapper) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.factRepository = factRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 二次解析成员调用，批量 MERGE CALLS 边。返回新合并的边数。
     */
    @Transactional
    public int resolveMemberCalls(String projectId, String versionId) {
        // 1. 加载 SERVICE_CALL 事实
        List<Fact> facts = factRepository.selectList(new LambdaQueryWrapper<Fact>()
                .eq(Fact::getVersionId, versionId)
                .eq(Fact::getFactType, "SERVICE_CALL"));
        if (facts.isEmpty()) {
            log.info("Member-call resolve: no SERVICE_CALL facts for versionId={}", versionId);
            return 0;
        }

        // 2. 建全局索引
        Map<String, List<GraphNode>> simpleNameToClassNodes = new HashMap<>();
        Map<String, GraphNode> fqnToClassNode = new HashMap<>();
        for (NodeType t : new NodeType[]{NodeType.Controller, NodeType.Service, NodeType.Mapper}) {
            List<GraphNode> classNodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, t.name(), null, null, null, 0);
            for (GraphNode n : classNodes) {
                String fqn = n.getNodeKey();
                if (fqn == null || fqn.isBlank()) {
                    continue;
                }
                fqnToClassNode.putIfAbsent(fqn, n);
                String simple = simpleName(fqn);
                if (simple == null || simple.isBlank()) {
                    simple = n.getNodeName();
                }
                if (simple != null && !simple.isBlank()) {
                    simpleNameToClassNodes.computeIfAbsent(simple, k -> new ArrayList<>()).add(n);
                }
            }
        }

        Map<String, List<GraphNode>> methodIndex = new HashMap<>();
        // 精确签名索引：Method nodeKey（FQN.methodName(paramTypes)）→ 方法节点。
        // 避免精确签名快路径逐 fact 调 findNode（~958 次 Neo4j 往返），全走内存。
        Map<String, GraphNode> methodByExactKey = new HashMap<>();
        // 反向索引：方法简单名 → Method 节点列表，用于 targetClass 未解析时的 god-node 消歧。
        Map<String, List<GraphNode>> methodNameIndex = new HashMap<>();
        List<GraphNode> methods = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Method.name(), null, null, null, 0);
        for (GraphNode m : methods) {
            String key = m.getNodeKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            methodByExactKey.putIfAbsent(key, m);
            String owningFqn = owningFqn(key);
            String methodName = methodName(key);
            if (owningFqn == null || methodName == null) {
                continue;
            }
            methodIndex.computeIfAbsent(owningFqn + "|" + normalizeName(methodName), k -> new ArrayList<>()).add(m);
            // 反向索引：methodName → 所有同名方法节点
            methodNameIndex.computeIfAbsent(normalizeName(methodName), k -> new ArrayList<>()).add(m);
        }

        // 3. 去重集：已存在的 CALLS 边 (fromId|toId)
        List<GraphEdge> existing = neo4jGraphDao.queryEdges(
                projectId, versionId, EdgeType.CALLS.name(), null, 0);
        Set<String> existingPairs = new HashSet<>();
        for (GraphEdge e : existing) {
            if (e.getFromNodeId() != null && e.getToNodeId() != null) {
                existingPairs.add(e.getFromNodeId() + "|" + e.getToNodeId());
            }
        }

        // 4. 逐事实解析
        List<GraphEdge> candidateEdges = new ArrayList<>();
        int forwardResolved = 0;
        int reverseResolved = 0;
        for (Fact fact : facts) {
            try {
                CallRelationDto call = parseCallRelation(fact);
                if (call == null) {
                    continue;
                }
                // 跳过合成的 "injects:" 依赖行（callerMethod 为 null）
                if (isBlank(call.callerClass) || isBlank(call.callerMethod)) {
                    continue;
                }
                GraphEdge edge;
                if (!isBlank(call.targetClass)) {
                    // 正向路径：targetClass 已由 ServiceCallExtractor 解析 → god-node 精确匹配
                    edge = resolveOne(projectId, versionId, call,
                            fqnToClassNode, simpleNameToClassNodes, methodIndex, methodByExactKey, existingPairs);
                    if (edge != null) forwardResolved++;
                } else {
                    // P0 反向路径：targetClass 未解析（XML 配置注入等场景）→ 按 calledMethod 名全局搜索
                    edge = resolveByMethodName(projectId, versionId, call,
                            fqnToClassNode, simpleNameToClassNodes, methodNameIndex,
                            methodIndex, methodByExactKey, existingPairs);
                    if (edge != null) reverseResolved++;
                }
                if (edge != null) {
                    candidateEdges.add(edge);
                }
            } catch (Exception e) {
                log.debug("Member-call resolve skipped a fact (factId={}): {}", fact.getId(), e.getMessage());
            }
        }

        if (candidateEdges.isEmpty()) {
            log.info("Member-call resolve: 0 new edges for versionId={} ({} facts processed, forward={} reverse={})",
                    versionId, facts.size(), forwardResolved, reverseResolved);
            return 0;
        }

        // 5. 批量 MERGE
        int merged = neo4jGraphDao.mergeEdgesBatch(candidateEdges);
        log.info("Member-call resolve: {} edges merged for versionId={} ({} facts processed, forward={} reverse={})",
                merged, versionId, facts.size(), forwardResolved, reverseResolved);
        return merged;
    }

    private GraphEdge resolveOne(String projectId, String versionId, CallRelationDto call,
                                 Map<String, GraphNode> fqnToClassNode,
                                 Map<String, List<GraphNode>> simpleNameToClassNodes,
                                 Map<String, List<GraphNode>> methodIndex,
                                 Map<String, GraphNode> methodByExactKey,
                                 Set<String> existingPairs) {
        // Caller
        String callerFqn = call.callerClass;
        GraphNode callerMethodNode = findMethodNode(callerFqn,
                call.callerMethodSignature, call.callerMethod, methodIndex, methodByExactKey);
        GraphNode callerClassNode = fqnToClassNode.get(callerFqn);
        GraphNode fromNode = callerMethodNode != null ? callerMethodNode : callerClassNode;
        if (fromNode == null) {
            return null;
        }

        // Target FQN（god-node guard）
        List<GraphNode> candidates = simpleNameToClassNodes.get(call.targetClass);
        if (candidates == null || candidates.isEmpty()) {
            return null; // 缺失，跳过
        }
        if (candidates.size() > 1) {
            return null; // 歧义（同名多类），跳过，不留错边
        }
        GraphNode targetClassNode = candidates.get(0);
        String targetFqn = targetClassNode.getNodeKey();

        // Target Method（god-node + 精确签名快路径）
        GraphNode targetMethodNode = findMethodNode(targetFqn,
                call.calledMethodSignature, call.calledMethod, methodIndex, methodByExactKey);

        // 端点：方法级优先，回退类级（Ruby method_nid or class_nid）
        GraphNode toNode = targetMethodNode != null ? targetMethodNode : targetClassNode;
        if (fromNode.getId().equals(toNode.getId())) {
            return null; // 自调用跳过
        }

        // 去重
        String pair = fromNode.getId() + "|" + toNode.getId();
        if (!existingPairs.add(pair)) {
            return null; // 已存在
        }

        return buildEdgePOJO(projectId, versionId, fromNode, toNode);
    }

    /**
     * P0 反向匹配：targetClass 未解析时（XML 配置注入等场景），按 calledMethod 名全局搜索。
     *
     * <p>不依赖 {@link CallRelationDto#targetClass} 是否已解析——该方法名在全局 Method 索引中
     * 恰好 1 个定义时直接解析（god-node）；多候选时尝试 caller 同包消歧；否则跳过。</p>
     */
    private GraphEdge resolveByMethodName(String projectId, String versionId, CallRelationDto call,
                                          Map<String, GraphNode> fqnToClassNode,
                                          Map<String, List<GraphNode>> simpleNameToClassNodes,
                                          Map<String, List<GraphNode>> methodNameIndex,
                                          Map<String, List<GraphNode>> methodIndex,
                                          Map<String, GraphNode> methodByExactKey,
                                          Set<String> existingPairs) {
        String calledMethod = call.calledMethod;
        if (isBlank(calledMethod)) {
            return null;
        }
        String normCalled = normalizeName(calledMethod);
        List<GraphNode> candidates = methodNameIndex.get(normCalled);
        if (candidates == null || candidates.isEmpty()) {
            return null; // 全局无此方法 → 可能是 JDK/外部库方法
        }

        // Caller
        String callerFqn = call.callerClass;
        GraphNode callerClassNode = fqnToClassNode.get(callerFqn);
        GraphNode fromNode = findMethodNode(callerFqn,
                call.callerMethodSignature, call.callerMethod, methodIndex, methodByExactKey);
        if (fromNode == null) {
            fromNode = callerClassNode;
        }
        if (fromNode == null) {
            return null;
        }

        // God-node：全局恰好 1 个同名方法
        GraphNode targetMethodNode;
        if (candidates.size() == 1) {
            targetMethodNode = candidates.get(0);
        } else {
            // 多候选 → 尝试 caller 同包消歧
            String callerPkg = callerFqn.contains(".") ? callerFqn.substring(0, callerFqn.lastIndexOf('.')) : "";
            List<GraphNode> samePkg = new ArrayList<>();
            for (GraphNode c : candidates) {
                String targetFqn = owningFqn(c.getNodeKey());
                if (targetFqn != null && targetFqn.startsWith(callerPkg)) {
                    samePkg.add(c);
                }
            }
            if (samePkg.size() == 1) {
                targetMethodNode = samePkg.get(0);
            } else {
                return null; // 歧义，跳过
            }
        }

        // 端点：方法级优先，回退类级
        String targetFqn = owningFqn(targetMethodNode.getNodeKey());
        GraphNode targetClassNode = fqnToClassNode.get(targetFqn);
        if (targetClassNode == null) {
            // 尝试简单名查找
            String simple = simpleName(targetFqn);
            if (simple != null) {
                List<GraphNode> simpleCands = simpleNameToClassNodes.get(simple);
                if (simpleCands != null && simpleCands.size() == 1) {
                    targetClassNode = simpleCands.get(0);
                }
            }
        }
        GraphNode toNode = targetClassNode != null ? targetClassNode : targetMethodNode;
        if (fromNode.getId().equals(toNode.getId())) {
            return null; // 自调用
        }

        // 去重
        String pair = fromNode.getId() + "|" + toNode.getId();
        if (!existingPairs.add(pair)) {
            return null;
        }

        GraphEdge edge = buildEdgePOJO(projectId, versionId, fromNode, toNode);
        // 反向匹配置信度略低于精确 targetClass 解析
        edge.setConfidence(BigDecimal.valueOf(0.85));
        edge.setStatus(NodeStatus.PENDING_CONFIRM.name());
        return edge;
    }

    /**
     * 查找方法节点：精确签名快路径（内存 methodByExactKey：FQN.methodName(paramTypes)）→ methodIndex god-node（恰好1）。
     * 重载（>1）或缺失返回 null（调用方回退类级）。全程内存查，不调 Neo4j findNode，避免逐 fact 往返。
     */
    private GraphNode findMethodNode(String classFqn, String methodSignature, String methodName,
                                     Map<String, List<GraphNode>> methodIndex,
                                     Map<String, GraphNode> methodByExactKey) {
        if (isBlank(classFqn)) {
            return null;
        }
        // 精确签名快路径（内存）
        if (!isBlank(methodSignature)) {
            GraphNode exact = methodByExactKey.get(classFqn + "." + methodSignature);
            if (exact != null) {
                return exact;
            }
        }
        if (isBlank(methodName)) {
            return null;
        }
        List<GraphNode> list = methodIndex.get(classFqn + "|" + normalizeName(methodName));
        if (list == null || list.size() != 1) {
            return null; // 0 或重载（>1）→ 不猜
        }
        return list.get(0);
    }

    private GraphEdge buildEdgePOJO(String projectId, String versionId, GraphNode fromNode, GraphNode toNode) {
        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(fromNode.getId());
        edge.setToNodeId(toNode.getId());
        edge.setEdgeType(EdgeType.CALLS.name());
        edge.setEdgeKey(fromNode.getNodeKey() + "->calls->" + toNode.getNodeKey());
        edge.setSourceType(SourceType.CODE_AST.name());
        edge.setConfidence(BigDecimal.ONE); // 接收方类型来自字段声明，源码显式 = EXTRACTED 档
        edge.setStatus(NodeStatus.CONFIRMED.name());
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        return edge;
    }

    private CallRelationDto parseCallRelation(Fact fact) {
        String data = fact.getNormalizedData();
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(data, CallRelationDto.class);
        } catch (Exception e) {
            log.debug("Failed to deserialize CallRelation from fact {}: {}", fact.getId(), e.getMessage());
            return null;
        }
    }

    /** 简单名：FQN 最后一个 '.' 之后（剥泛型）。 */
    private static String simpleName(String fqn) {
        if (fqn == null) {
            return null;
        }
        String s = fqn;
        int lt = s.indexOf('<');
        if (lt > 0) {
            s = s.substring(0, lt);
        }
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** 从 Method nodeKey（形如 com.x.Svc.method(p1, p2)）取 owning FQN。 */
    private static String owningFqn(String methodNodeKey) {
        int paren = methodNodeKey.indexOf('(');
        String head = paren > 0 ? methodNodeKey.substring(0, paren) : methodNodeKey;
        int dot = head.lastIndexOf('.');
        return dot > 0 ? head.substring(0, dot) : null;
    }

    /** 从 Method nodeKey 取方法名（最后一个 '.' 与 '(' 之间）。 */
    private static String methodName(String methodNodeKey) {
        int paren = methodNodeKey.indexOf('(');
        String head = paren > 0 ? methodNodeKey.substring(0, paren) : methodNodeKey;
        int dot = head.lastIndexOf('.');
        return dot >= 0 ? head.substring(dot + 1) : null;
    }

    /** graphify _key 契约：剥非字母数字 + 小写，使 findById(String)/findbyid 同 key。 */
    private static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 反序列化 POJO（CallRelation 有 final 字段+无无参构造，Jackson 直接反序列化不安全，故用 DTO）。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallRelationDto {
        private String callerClass;
        private String callerMethod;
        private String calledMethod;
        private String targetClass;
        private String targetMethod;
        private String sourcePath;
        private Integer lineNumber;
        private String callerMethodSignature;
        private String calledMethodSignature;
    }
}
