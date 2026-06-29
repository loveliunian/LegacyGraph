package io.github.legacygraph.service;

import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.types.Path;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GraphQueryService {

    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;
    private final Driver neo4jDriver;

    public GraphQueryService(GraphNodeRepository graphNodeRepository,
                            GraphEdgeRepository graphEdgeRepository,
                            Driver neo4jDriver) {
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
        this.neo4jDriver = neo4jDriver;
    }

    /**
     * 查询接口完整调用链: ApiEndpoint -> Controller -> Method -> Service -> Mapper -> SQL -> Table
     */
    public List<Map<String, Object>> getApiCallChain(String versionId, String apiKey) {
        String cypher = """
                MATCH p = (api:ApiEndpoint {nodeKey: $apiKey})-[:HANDLED_BY|CALLS|EXECUTES|READS|WRITES*1..8]->(n)
                RETURN p
                """;

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, Map.of("apiKey", apiKey));
            List<Map<String, Object>> response = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                Path path = record.get("p").asPath();
                Map<String, Object> pathMap = new HashMap<>();
                List<Map<String, Object>> nodes = new ArrayList<>();
                for (var node : path.nodes()) {
                    Map<String, Object> nodeMap = new HashMap<>();
                    nodeMap.put("id", node.id());
                    nodeMap.put("labels", node.labels());
                    nodeMap.put("properties", node.asMap());
                    nodes.add(nodeMap);
                }
                pathMap.put("nodes", nodes);
                response.add(pathMap);
            }
            return response;
        }
    }

    /**
     * 查询表被哪些接口影响
     */
    public List<Map<String, Object>> getTableImpact(String versionId, String tableName) {
        String cypher = """
                MATCH p = (api:ApiEndpoint)-[:HANDLED_BY|CALLS|EXECUTES|WRITES*1..8]->(t:Table {nodeName: $tableName})
                RETURN api, p
                """;

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, Map.of("tableName", tableName));
            List<Map<String, Object>> response = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                response.add(record.asMap());
            }
            return response;
        }
    }

    /**
     * 获取功能图谱视图
     * 根据模块名称查询完整的功能调用链路图谱
     */
    public Map<String, Object> getFeatureView(String versionId, String module) {
        String cypher = """
                MATCH (n)
                WHERE n.versionId = $versionId
                  AND any(label IN labels(n) WHERE label IN ['Feature', 'ApiEndpoint', 'Service', 'Repository'])
                  AND n.module = $module
                MATCH p = (n)-[:EXPOSED_BY|CALLS|HANDLED_BY|EXECUTES|READS|WRITES*1..10]->(m)
                RETURN DISTINCT p
                """;

        Map<String, Object> result = new HashMap<>();
        try (Session session = neo4jDriver.session()) {
            Result queryResult = session.run(cypher, Map.of(
                    "versionId", versionId,
                    "module", module
            ));

            Set<String> nodeIds = new HashSet<>();
            List<Map<String, Object>> nodes = new ArrayList<>();
            List<Map<String, Object>> edges = new ArrayList<>();

            while (queryResult.hasNext()) {
                Record record = queryResult.next();
                Path path = record.get("p").asPath();

                for (var node : path.nodes()) {
                    String nodeId = node.elementId();
                    if (!nodeIds.contains(nodeId)) {
                        nodeIds.add(nodeId);
                        Map<String, Object> nodeMap = new HashMap<>();
                        nodeMap.put("id", nodeId);
                        nodeMap.put("labels", node.labels());
                        nodeMap.put("properties", node.asMap());
                        nodes.add(nodeMap);
                    }
                }

                for (var rel : path.relationships()) {
                    Map<String, Object> relMap = new HashMap<>();
                    relMap.put("id", rel.elementId());
                    relMap.put("type", rel.type().toString());
                    relMap.put("startNodeId", rel.startNodeElementId());
                    relMap.put("endNodeId", rel.endNodeElementId());
                    relMap.put("properties", rel.asMap());
                    edges.add(relMap);
                }
            }

            result.put("module", module);
            result.put("versionId", versionId);
            result.put("nodes", nodes);
            result.put("edges", edges);
            result.put("nodeCount", nodes.size());
            result.put("edgeCount", edges.size());
        }

        return result;
    }

    /**
     * 获取业务图谱视图
     * 根据业务域查询完整的业务对象、流程、规则图谱
     */
    public Map<String, Object> getBusinessView(String versionId, String domain) {
        String cypher = """
                MATCH (n)
                WHERE n.versionId = $versionId
                  AND any(label IN labels(n) WHERE label IN ['BusinessDomain', 'BusinessProcess', 'BusinessObject', 'BusinessRule'])
                  AND (n.businessDomain = $domain OR n.domain = $domain)
                OPTIONAL MATCH p = (n)-[:CONTAINS|USES|DEFINES|REFERENCES*1..8]->(m)
                RETURN DISTINCT n, p
                """;

        Map<String, Object> result = new HashMap<>();
        try (Session session = neo4jDriver.session()) {
            Result queryResult = session.run(cypher, Map.of(
                    "versionId", versionId,
                    "domain", domain
            ));

            Set<String> nodeIds = new HashSet<>();
            List<Map<String, Object>> nodes = new ArrayList<>();
            List<Map<String, Object>> edges = new ArrayList<>();

            while (queryResult.hasNext()) {
                Record record = queryResult.next();
                org.neo4j.driver.Value pValue = record.get("p");
                if (!pValue.isNull()) {
                    Path path = pValue.asPath();

                    for (var node : path.nodes()) {
                        String nodeId = node.elementId();
                        if (!nodeIds.contains(nodeId)) {
                            nodeIds.add(nodeId);
                            Map<String, Object> nodeMap = new HashMap<>();
                            nodeMap.put("id", nodeId);
                            nodeMap.put("labels", node.labels());
                            nodeMap.put("properties", node.asMap());
                            nodes.add(nodeMap);
                        }
                    }

                    for (var rel : path.relationships()) {
                        Map<String, Object> relMap = new HashMap<>();
                        relMap.put("id", rel.elementId());
                        relMap.put("type", rel.type().toString());
                        relMap.put("startNodeId", rel.startNodeElementId());
                        relMap.put("endNodeId", rel.endNodeElementId());
                        relMap.put("properties", rel.asMap());
                        edges.add(relMap);
                    }
                }
            }

            result.put("domain", domain);
            result.put("versionId", versionId);
            result.put("nodes", nodes);
            result.put("edges", edges);
            result.put("nodeCount", nodes.size());
            result.put("edgeCount", edges.size());
        }

        return result;
    }

    /**
     * 获取统一图谱全量数据
     * 查询指定扫描版本的所有节点和边，按置信度过滤后返回
     */
    public Map<String, Object> getUnifiedGraph(String versionId, Double minConfidence) {
        // 从PostgreSQL查询，获取完整的统一图谱数据
        List<GraphNode> nodes = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getVersionId, versionId)
                .ge(GraphNode::getConfidence, minConfidence)
                .list();

        List<GraphEdge> edges = graphEdgeRepository.lambdaQuery()
                .eq(GraphEdge::getVersionId, versionId)
                .ge(GraphEdge::getConfidence, minConfidence)
                .list();

        Map<String, Object> result = new HashMap<>();
        result.put("versionId", versionId);
        result.put("nodes", nodes.stream().map(node -> {
            Map<String, Object> nodeMap = new HashMap<>();
            nodeMap.put("id", node.getId());
            nodeMap.put("key", node.getNodeKey());
            nodeMap.put("label", node.getDisplayName());
            nodeMap.put("type", node.getNodeType());
            nodeMap.put("confidence", node.getConfidence());
            nodeMap.put("status", node.getStatus());
            nodeMap.put("description", node.getDescription());
            nodeMap.put("sourcePath", node.getSourcePath());
            return nodeMap;
        }).toList());
        result.put("edges", edges.stream().map(edge -> {
            Map<String, Object> edgeMap = new HashMap<>();
            edgeMap.put("id", edge.getId());
            edgeMap.put("source", edge.getFromNodeId());
            edgeMap.put("target", edge.getToNodeId());
            edgeMap.put("type", edge.getEdgeType());
            edgeMap.put("label", getEdgeLabel(edge.getEdgeType()));
            edgeMap.put("confidence", edge.getConfidence());
            return edgeMap;
        }).toList());
        result.put("nodeCount", nodes.size());
        result.put("edgeCount", edges.size());

        return result;
    }

    /**
     * 获取项目扫描版本列表，包含节点和边统计
     */
    public List<Map<String, Object>> getScanVersions(String projectId) {
        List<Map<String, Object>> result = new ArrayList<>();
        // 由于GraphQueryService没有依赖ScanVersionRepository
        // 这里只返回空列表，实际由projectApi获取版本列表
        return result;
    }

    /**
     * 获取关系类型显示标签
     */
    private String getEdgeLabel(String edgeType) {
        return switch (edgeType) {
            case "CONTAINS" -> "包含";
            case "CALLS" -> "调用";
            case "HANDLED_BY" -> "处理";
            case "EXECUTES" -> "执行";
            case "READS" -> "读取";
            case "WRITES" -> "写入";
            case "HAS_COLUMN" -> "字段";
            default -> edgeType;
        };
    }
}
