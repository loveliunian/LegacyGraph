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
    // 不使用 @Transactional：MyBatis-Plus 自带连接管理，Neo4j 写入不需要 PG 事务。
    // 全程内存索引+批量写，耗时 ~10-15s，长事务会导致 HikariCP 连接泄漏告警。
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
        for (NodeType t : new NodeType[]{NodeType.Controller, NodeType.Service, NodeType.Mapper, NodeType.ConfigItem, NodeType.ExternalSystem}) {
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
                    if (edge != null) {
                        forwardResolved++;
                    } else {
                        // 正向匹配失败（索引找不到或歧义），回退到反向方法名匹配
                        edge = resolveByMethodName(projectId, versionId, call,
                                fqnToClassNode, simpleNameToClassNodes, methodNameIndex,
                                methodIndex, methodByExactKey, existingPairs);
                        if (edge != null) reverseResolved++;
                    }
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

        // 5. 批量 MERGE CALLS 边
        int merged = neo4jGraphDao.mergeEdgesBatch(candidateEdges);
        log.info("Member-call resolve: {} edges merged for versionId={} ({} facts processed, forward={} reverse={})",
                merged, versionId, facts.size(), forwardResolved, reverseResolved);

        // 6. 二次解析 EXTENDS/IMPLEMENTS 边（GraphBuilder 首次解析时跨文件父类可能未入库）
        int inheritMerged = resolveInheritanceEdges(projectId, versionId, fqnToClassNode, simpleNameToClassNodes);
        if (inheritMerged > 0) {
            log.info("Inheritance resolve: {} EXTENDS/IMPLEMENTS edges merged for versionId={}", inheritMerged, versionId);
        }

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
        GraphNode targetClassNode;
        if (candidates.size() > 1) {
            // 同名多类消歧：同包优先 → 语义关联
            targetClassNode = disambiguateClassCandidates(candidates, call.callerClass);
            if (targetClassNode == null) {
                return null; // 无法消歧，跳过
            }
        } else {
            targetClassNode = candidates.get(0);
        }
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
            // P2c：多维消歧 — 同包过滤 → 参数个数匹配 → 签名精确匹配
            targetMethodNode = disambiguateCandidates(
                    candidates, call, callerFqn, fqnToClassNode, simpleNameToClassNodes);
            if (targetMethodNode == null) {
                return null; // 歧义，跳过
            }
        }

        // 端点：方法级优先，回退类级（与 resolveOne 一致）
        // 同时查找类级节点用于备选（方法节点不存在时回退）
        String targetFqn = owningFqn(targetMethodNode.getNodeKey());
        GraphNode targetClassNode = fqnToClassNode.get(targetFqn);
        if (targetClassNode == null) {
            String simple = simpleName(targetFqn);
            if (simple != null) {
                List<GraphNode> simpleCands = simpleNameToClassNodes.get(simple);
                if (simpleCands != null && simpleCands.size() == 1) {
                    targetClassNode = simpleCands.get(0);
                }
            }
        }
        // 方法级优先，类级回退
        GraphNode toNode = null;
        if (targetMethodNode != null) {
            toNode = targetMethodNode;
        }
        if (toNode == null && targetClassNode != null) {
            toNode = targetClassNode;
        }
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
     * 类节点消歧策略（用于 resolveOne 中同名多类场景）：
     * 1. 同包过滤（caller 与 candidate 在同一包下）
     * 2. 语义关联（caller 类名与 candidate 类名共享词根）
     */
    private GraphNode disambiguateClassCandidates(List<GraphNode> candidates, String callerFqn) {
        // 1. 同包过滤
        String callerPkg = callerFqn.contains(".") ? callerFqn.substring(0, callerFqn.lastIndexOf('.')) : "";
        List<GraphNode> filtered = new ArrayList<>();
        for (GraphNode c : candidates) {
            String targetFqn = c.getNodeKey();
            if (targetFqn != null && targetFqn.startsWith(callerPkg)) {
                filtered.add(c);
            }
        }
        if (filtered.size() == 1) return filtered.get(0);
        if (filtered.isEmpty()) filtered = new ArrayList<>(candidates);

        // 同名歧义守卫：所有候选简单名完全相同时，语义消歧无意义，判定为歧义
        String firstSimple = simpleName(filtered.get(0).getNodeKey());
        if (firstSimple != null) {
            boolean allSameName = filtered.stream()
                    .allMatch(c -> firstSimple.equals(simpleName(c.getNodeKey())));
            if (allSameName) return null;
        }

        // 2. 语义关联消歧：根据类名的共享词根推断调用意图
        String callerSimple = simpleName(callerFqn);
        if (callerSimple == null) return null;
        GraphNode bestMatch = null;
        int bestScore = 0;
        for (GraphNode c : filtered) {
            String targetSimple = simpleName(c.getNodeKey());
            if (targetSimple == null) continue;
            int score = calculateSemanticScore(callerSimple, targetSimple);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = c;
            }
        }
        if (bestMatch != null && bestScore >= 1) {
            return bestMatch;
        }
        return null; // 无法消歧
    }

    /**
     * P2c：多维消歧策略 — 按优先级依次尝试：
     * 1. 同包过滤（caller 与 candidate 在同一包下）
     * 2. 参数个数匹配（calledMethodSignature 的参数个数 == 候选方法 nodeKey 中的参数个数）
     * 3. 签名精确匹配（calledMethodSignature 完全匹配候选方法的参数类型序列）
     * 4. import 表消歧（caller 类的 import 声明中包含候选类的 FQN）
     * 每一层过滤后若只剩 1 个候选则返回，否则进入下一层；全部无法消歧返回 null。
     */
    private GraphNode disambiguateCandidates(
            List<GraphNode> candidates, CallRelationDto call, String callerFqn,
            Map<String, GraphNode> fqnToClassNode,
            Map<String, List<GraphNode>> simpleNameToClassNodes) {

        // 1. 同包过滤
        String callerPkg = callerFqn.contains(".") ? callerFqn.substring(0, callerFqn.lastIndexOf('.')) : "";
        List<GraphNode> filtered = new ArrayList<>();
        for (GraphNode c : candidates) {
            String targetFqn = owningFqn(c.getNodeKey());
            if (targetFqn != null && targetFqn.startsWith(callerPkg)) {
                filtered.add(c);
            }
        }
        if (filtered.size() == 1) return filtered.get(0);
        if (filtered.isEmpty()) filtered = new ArrayList<>(candidates); // 同包全 miss，回退

        // 2. 参数个数匹配
        if (call.calledMethodSignature != null && !call.calledMethodSignature.isBlank()) {
            int callParamCount = countParams(call.calledMethodSignature);
            if (callParamCount >= 0) {
                List<GraphNode> paramMatched = new ArrayList<>();
                for (GraphNode c : filtered) {
                    int candidateParamCount = countParams(c.getNodeKey());
                    if (candidateParamCount == callParamCount) {
                        paramMatched.add(c);
                    }
                }
                if (paramMatched.size() == 1) return paramMatched.get(0);
                if (!paramMatched.isEmpty()) filtered = paramMatched;
            }
        }

        // 3. 签名精确匹配（参数类型序列完全一致）
        if (call.calledMethodSignature != null && !call.calledMethodSignature.isBlank()) {
            String callParamTypes = extractParamTypes(call.calledMethodSignature);
            if (callParamTypes != null && !callParamTypes.isBlank()) {
                List<GraphNode> sigMatched = new ArrayList<>();
                for (GraphNode c : filtered) {
                    String candidateParamTypes = extractParamTypes(c.getNodeKey());
                    if (callParamTypes.equalsIgnoreCase(candidateParamTypes)) {
                        sigMatched.add(c);
                    }
                }
                if (sigMatched.size() == 1) return sigMatched.get(0);
                if (!sigMatched.isEmpty()) filtered = sigMatched;
            }
        }

        // 4. 语义关联消歧：根据类名的共享词根推断调用意图
        // 匹配策略：前缀匹配 → 共享词根匹配 → 驼峰分词匹配
        if (call.sourcePath != null) {
            String callerSimple = simpleName(callerFqn);
            GraphNode bestMatch = null;
            int bestScore = 0;
            for (GraphNode c : filtered) {
                String targetSimple = simpleName(owningFqn(c.getNodeKey()));
                if (targetSimple == null || callerSimple == null) {
                    continue;
                }
                int score = calculateSemanticScore(callerSimple, targetSimple);
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = c;
                }
            }
            if (bestMatch != null && bestScore >= 1) {
                return bestMatch;
            }
        }

        return null; // 无法消歧
    }

    /**
     * 计算两个类名的语义关联分数：
     * - 前缀匹配（如 UserService → UserMapper）：+2 分
     * - 共享词根匹配（如 OrderService → OrderDetailMapper）：+1 分
     * - 驼峰分词共享词匹配（如 UserServiceImpl → UserMapper）：+1 分
     */
    private static int calculateSemanticScore(String callerSimple, String targetSimple) {
        int score = 0;
        String callerCore = stripSuffix(callerSimple);
        String targetCore = stripSuffix(targetSimple);

        // 前缀匹配（UserService → UserMapper）
        if (callerCore.startsWith(targetCore) || targetCore.startsWith(callerCore)) {
            score += 2;
        }

        // 共享词根匹配（OrderService → OrderDetailMapper）
        if (callerCore.contains(targetCore) || targetCore.contains(callerCore)) {
            score += 1;
        }

        // 驼峰分词共享词匹配
        Set<String> callerWords = camelCaseWords(callerSimple);
        Set<String> targetWords = camelCaseWords(targetSimple);
        for (String w : callerWords) {
            if (targetWords.contains(w) && w.length() >= 3) {
                score += 1;
                break;
            }
        }

        return score;
    }

    /** 剥离常见后缀：Service/ServiceImpl/Mapper/Dao/Controller */
    private static String stripSuffix(String name) {
        if (name == null) return null;
        String[] suffixes = {"ServiceImpl", "Service", "Mapper", "Dao", "DaoImpl", "Controller", "Handler"};
        for (String s : suffixes) {
            if (name.endsWith(s)) {
                return name.substring(0, name.length() - s.length());
            }
        }
        return name;
    }

    /** 驼峰分词：将类名拆分为单词集合 */
    private static Set<String> camelCaseWords(String name) {
        Set<String> words = new HashSet<>();
        if (name == null || name.isEmpty()) {
            return words;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch) && current.length() > 0) {
                words.add(current.toString());
                current = new StringBuilder();
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            words.add(current.toString());
        }
        return words;
    }

    /** 从方法签名中提取参数类型部分（括号内内容），如 "findById(String, int)" → "String, int" */
    private static String extractParamTypes(String signature) {
        if (signature == null) return null;
        int start = signature.indexOf('(');
        int end = signature.lastIndexOf(')');
        if (start < 0 || end < 0 || end <= start) return null;
        return signature.substring(start + 1, end).trim();
    }

    /** 统计方法签名中的参数个数 */
    private static int countParams(String signature) {
        String types = extractParamTypes(signature);
        if (types == null || types.isEmpty()) return 0;
        // 按逗号分割，注意泛型中的逗号不分割（简化处理）
        int count = 0;
        int depth = 0;
        for (int i = 0; i < types.length(); i++) {
            char ch = types.charAt(i);
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (ch == ',' && depth == 0) count++;
        }
        return count + 1; // 最后一个参数后没有逗号
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

    /**
     * 二次解析 EXTENDS/IMPLEMENTS 边 —— 根据类节点 properties 中的继承信息，
     * 在全局类索引中查找父类/接口节点，补充首次解析时遗漏的跨文件继承边。
     */
    private int resolveInheritanceEdges(String projectId, String versionId,
                                        Map<String, GraphNode> fqnToClassNode,
                                        Map<String, List<GraphNode>> simpleNameToClassNodes) {
        List<GraphEdge> inheritEdges = new ArrayList<>();
        Set<String> existingPairs = new HashSet<>();

        List<GraphEdge> existingEdges = neo4jGraphDao.queryEdges(
                projectId, versionId, null, null, 0);
        for (GraphEdge e : existingEdges) {
            if (e.getFromNodeId() != null && e.getToNodeId() != null) {
                existingPairs.add(e.getFromNodeId() + "|" + e.getToNodeId());
            }
        }

        for (GraphNode classNode : fqnToClassNode.values()) {
            String propsJson = classNode.getProperties();
            if (propsJson == null || propsJson.isBlank()) {
                continue;
            }
            try {
                Map<String, Object> props = objectMapper.readValue(propsJson, Map.class);
                List<String> extendedTypes = (List<String>) props.get("extendedTypes");
                List<String> implementedTypes = (List<String>) props.get("implementedTypes");

                String childFqn = classNode.getNodeKey();

                if (extendedTypes != null) {
                    for (String parentSimple : extendedTypes) {
                        GraphNode parentNode = resolveParentNode(parentSimple, simpleNameToClassNodes, childFqn);
                        if (parentNode != null && !parentNode.getId().equals(classNode.getId())) {
                            String pair = classNode.getId() + "|" + parentNode.getId();
                            if (existingPairs.add(pair)) {
                                inheritEdges.add(buildInheritEdge(projectId, versionId, classNode, parentNode, EdgeType.EXTENDS));
                            }
                        }
                    }
                }

                if (implementedTypes != null) {
                    for (String ifaceSimple : implementedTypes) {
                        GraphNode ifaceNode = resolveParentNode(ifaceSimple, simpleNameToClassNodes, childFqn);
                        if (ifaceNode != null && !ifaceNode.getId().equals(classNode.getId())) {
                            String pair = classNode.getId() + "|" + ifaceNode.getId();
                            if (existingPairs.add(pair)) {
                                inheritEdges.add(buildInheritEdge(projectId, versionId, classNode, ifaceNode, EdgeType.IMPLEMENTS));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to parse inherit properties for {}: {}", classNode.getNodeKey(), e.getMessage());
            }
        }

        if (inheritEdges.isEmpty()) {
            return 0;
        }
        return neo4jGraphDao.mergeEdgesBatch(inheritEdges);
    }

    /** 根据简单名解析父类/接口节点，优先同包匹配 */
    private GraphNode resolveParentNode(String simpleName,
                                         Map<String, List<GraphNode>> simpleNameToClassNodes,
                                         String childFqn) {
        List<GraphNode> candidates = simpleNameToClassNodes.get(simpleName);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        String childPkg = childFqn.contains(".") ? childFqn.substring(0, childFqn.lastIndexOf('.')) : "";
        for (GraphNode c : candidates) {
            String fqn = c.getNodeKey();
            if (fqn != null && fqn.startsWith(childPkg)) {
                return c;
            }
        }
        return null;
    }

    /** 构建继承边 POJO */
    private GraphEdge buildInheritEdge(String projectId, String versionId,
                                        GraphNode fromNode, GraphNode toNode, EdgeType edgeType) {
        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(fromNode.getId());
        edge.setToNodeId(toNode.getId());
        edge.setEdgeType(edgeType.name());
        edge.setEdgeKey(fromNode.getNodeKey() + "->" + edgeType.name().toLowerCase() + "->" + toNode.getNodeKey());
        edge.setSourceType(SourceType.CODE_AST.name());
        edge.setConfidence(BigDecimal.ONE);
        edge.setStatus(NodeStatus.CONFIRMED.name());
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        return edge;
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
        private String receiverExpression;  // P2-2：调用点接收者表达式原文
    }
}
