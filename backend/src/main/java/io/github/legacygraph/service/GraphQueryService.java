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
     */
    public Map<String, Object> getFeatureView(String versionId, String module) {
        String cypher = """
                MATCH p = (f:Feature {nodeKey: $featureKey})-[:EXPOSED_BY|CALLS|HANDLED_BY|EXECUTES|READS|WRITES*1..10]->(n)
                RETURN p
                """;

        Map<String, Object> result = new HashMap<>();
        // TODO: 根据module找到feature，然后返回完整链路
        // 简化版本，先返回占位
        result.put("module", module);
        result.put("versionId", versionId);
        return result;
    }

    /**
     * 获取业务图谱视图
     */
    public Map<String, Object> getBusinessView(String versionId, String domain) {
        Map<String, Object> result = new HashMap<>();
        result.put("domain", domain);
        result.put("versionId", versionId);
        return result;
    }
}
