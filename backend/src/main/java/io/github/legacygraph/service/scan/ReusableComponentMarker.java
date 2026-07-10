package io.github.legacygraph.service.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可复用组件标记服务。
 *
 * <p>在扫描完成后统计每个 Class 节点被 EXTENDS 的次数，超过阈值的类在
 * {@code GraphNode.properties} JSON 里标记为可复用组件，供 QA 召回/重排阶段做分数加权。
 *
 * <p>标记格式：
 * <pre>{@code
 * {"reusable": true, "reuseType": "BASE_ENTITY|UTIL|RESULT_WRAPPER|CONFIG|COMPONENT", "usageCount": 42}
 * }</pre>
 *
 * <p>触发模式参考 {@link ScanArtifactPublisher}：由 {@code ProjectScanner} 在扫描完成后调用，
 * 失败不阻塞主扫描流程。
 */
@Slf4j
@Service
public class ReusableComponentMarker {

    /** 可复用阈值：被 EXTENDS 次数达到此值的类才标记为 reusable */
    @Value("${legacygraph.qa.reusable-threshold:2}")
    private int reusableThreshold;

    /** EXTENDS 边单次查询上限，防止超大项目一次性拉取过多边 */
    private static final int EXTENDS_QUERY_LIMIT = 20000;
    /** extendedBy 字段中记录的子类名称上限，避免超大列表 */
    private static final int SAMPLE_NAME_LIMIT = 5;

    private final Neo4jGraphDao neo4jGraphDao;
    private final ObjectMapper objectMapper;

    public ReusableComponentMarker(Neo4jGraphDao neo4jGraphDao, ObjectMapper objectMapper) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.objectMapper = objectMapper;
    }

    /**
     * 统计 EXTENDS 入度并标记可复用组件。
     * 对被继承次数达到 {@link #reusableThreshold} 的类节点，在 properties 中写入
     * {@code reusable=true}、{@code usageCount}、{@code extendedBy}（子类名称列表）。
     *
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     * @return 被标记为 reusable 的节点数量
     */
    public int mark(String projectId, String versionId) {
        if (projectId == null || projectId.isBlank() || versionId == null || versionId.isBlank()) {
            return 0;
        }
        try {
            // 1. 查询本版本所有 EXTENDS 边（toNodeId=null 表示不按目标节点过滤）
            List<GraphEdge> extendsEdges = neo4jGraphDao.queryEdges(
                    projectId, versionId, "EXTENDS", null, EXTENDS_QUERY_LIMIT);
            if (extendsEdges == null || extendsEdges.isEmpty()) {
                log.info("ReusableComponentMarker: no EXTENDS edges for projectId={}, versionId={}, skip",
                        projectId, versionId);
                return 0;
            }

            // 2. 按 toNodeId（被继承的父类）聚合子类列表
            Map<String, List<String>> extendedByMap = new HashMap<>();
            for (GraphEdge edge : extendsEdges) {
                if (edge.getToNodeId() != null && edge.getFromNodeId() != null) {
                    extendedByMap.computeIfAbsent(edge.getToNodeId(), k -> new ArrayList<>())
                            .add(edge.getFromNodeId());
                }
            }

            // 3. 对达到阈值的节点打 reusable 标记
            int marked = 0;
            for (Map.Entry<String, List<String>> entry : extendedByMap.entrySet()) {
                int count = entry.getValue().size();
                if (count < reusableThreshold) {
                    continue;
                }
                if (markNodeReusable(entry.getKey(), count, entry.getValue())) {
                    marked++;
                }
            }
            log.info("ReusableComponentMarker: marked {} reusable nodes (threshold={}, totalExtendsEdges={}) for projectId={}, versionId={}",
                    marked, reusableThreshold, extendsEdges.size(), projectId, versionId);
            return marked;
        } catch (Exception e) {
            log.warn("ReusableComponentMarker: failed to mark reusable components for projectId={}, versionId={}: {}",
                    projectId, versionId, e.getMessage());
            return 0;
        }
    }

    /**
     * 给单个节点写入 reusable 标记（合并进既有 properties，不覆盖其他字段）。
     *
     * @param nodeId       被继承的父类节点ID
     * @param usageCount   被继承次数
     * @param childNodeIds 子类节点ID列表（用于查询子类名称写入 extendedBy）
     * @return true 表示标记成功
     */
    private boolean markNodeReusable(String nodeId, int usageCount, List<String> childNodeIds) {
        Optional<GraphNode> opt = neo4jGraphDao.findNodeById(nodeId);
        if (opt.isEmpty()) {
            log.debug("ReusableComponentMarker: node {} not found, skip", nodeId);
            return false;
        }
        GraphNode node = opt.get();
        // 推断 reuseType：优先用 className，其次 nodeName
        String reuseType = inferReuseType(node.getClassName() != null ? node.getClassName() : node.getNodeName());

        // 收集子类名称列表（最多 SAMPLE_NAME_LIMIT 个，避免超大列表）
        List<String> childNames = collectChildNames(childNodeIds);

        // 合并 properties：保留原有字段（如 extendedTypes），追加 reusable 标记
        Map<String, Object> props = parseProperties(node.getProperties());
        props.put("reusable", true);
        props.put("reuseType", reuseType);
        props.put("usageCount", usageCount);
        props.put("extendedBy", childNames);

        try {
            node.setProperties(objectMapper.writeValueAsString(props));
            node.setUpdatedAt(java.time.LocalDateTime.now());
            neo4jGraphDao.updateNode(node);
            log.debug("ReusableComponentMarker: marked node {} (name={}, type={}, usageCount={}, extendedBy={}) as reusable {}",
                    nodeId, node.getNodeName(), node.getNodeType(), usageCount, childNames, reuseType);
            return true;
        } catch (Exception e) {
            log.warn("ReusableComponentMarker: failed to serialize/write properties for node {}: {}", nodeId, e.getMessage());
            return false;
        }
    }

    /** 查询子类节点名称列表（最多取 SAMPLE_NAME_LIMIT 个） */
    private List<String> collectChildNames(List<String> childNodeIds) {
        if (childNodeIds == null || childNodeIds.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        int limit = Math.min(childNodeIds.size(), SAMPLE_NAME_LIMIT);
        for (int i = 0; i < limit; i++) {
            try {
                Optional<GraphNode> child = neo4jGraphDao.findNodeById(childNodeIds.get(i));
                if (child.isPresent()) {
                    String name = child.get().getClassName();
                    if (name == null || name.isBlank()) {
                        name = child.get().getNodeName();
                    }
                    if (name != null && !name.isBlank()) {
                        names.add(name);
                    }
                }
            } catch (Exception e) {
                log.debug("ReusableComponentMarker: failed to find child node {}: {}", childNodeIds.get(i), e.getMessage());
            }
        }
        return names;
    }

    /**
     * reuseType 启发式推断（基于类名）。
     * <ul>
     *   <li>含 "Base"/"Abstract" → BASE_ENTITY</li>
     *   <li>含 "Util"/"Utils"/"Helper" → UTIL</li>
     *   <li>含 "Result"/"Response"/"Page" → RESULT_WRAPPER</li>
     *   <li>含 "Config"/"Properties" → CONFIG</li>
     *   <li>其他 → COMPONENT</li>
     * </ul>
     */
    String inferReuseType(String name) {
        if (name == null || name.isBlank()) {
            return "COMPONENT";
        }
        // 只取简单类名（去包路径）
        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        if (containsAny(simple, "Base", "Abstract")) {
            return "BASE_ENTITY";
        }
        if (containsAny(simple, "Util", "Utils", "Helper")) {
            return "UTIL";
        }
        if (containsAny(simple, "Result", "Response", "Page")) {
            return "RESULT_WRAPPER";
        }
        if (containsAny(simple, "Config", "Properties")) {
            return "CONFIG";
        }
        return "COMPONENT";
    }

    private boolean containsAny(String src, String... tokens) {
        for (String t : tokens) {
            if (src.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析节点 properties JSON 字符串为可写 Map。解析失败时返回空 Map（不丢失原有数据的风险隔离）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseProperties(String propertiesJson) {
        if (propertiesJson == null || propertiesJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(propertiesJson, Map.class);
            // 包装为可写 Map，保留插入顺序
            return new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            log.debug("ReusableComponentMarker: failed to parse properties '{}', start with empty map: {}",
                    propertiesJson, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 查询指定版本下所有 reusable=true 节点的 sourcePath 集合，供 QA 重排阶段做 boost 匹配。
     *
     * <p>当前实现：查询所有 CODE_AST 来源节点，内存过滤 properties 含 {@code "reusable":true}。
     * Java 类节点（Controller/Service/Mapper）由 CODE_AST 抽取产生，数量可控。
     *
     * @return reusable 节点的 sourcePath 集合（可能为空集，不会为 null）
     */
    public Set<String> findReusableSourcePaths(String projectId, String versionId) {
        try {
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, null, "CODE_AST", null, null, 0);
            if (nodes == null || nodes.isEmpty()) {
                return Set.of();
            }
            return nodes.stream()
                    .filter(n -> isReusable(n.getProperties()))
                    .map(GraphNode::getSourcePath)
                    .filter(p -> p != null && !p.isBlank())
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("ReusableComponentMarker: failed to find reusable source paths for projectId={}, versionId={}: {}",
                    projectId, versionId, e.getMessage());
            return Set.of();
        }
    }

    /** 判断 properties JSON 是否标记了 reusable=true（字符串包含判断，避免逐节点解析 JSON 的开销）。
     *  容忍空格：先去空白再匹配 {@code "reusable":true} */
    private boolean isReusable(String propertiesJson) {
        if (propertiesJson == null) {
            return false;
        }
        String compact = propertiesJson.replaceAll("\\s", "");
        return compact.contains("\"reusable\":true");
    }
}
