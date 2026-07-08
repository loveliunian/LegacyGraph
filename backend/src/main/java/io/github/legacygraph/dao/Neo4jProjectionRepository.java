package io.github.legacygraph.dao;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.*;

import static io.github.legacygraph.dao.Neo4jConversions.*;

/**
 * Neo4j Projection Repository — 负责 map projection 查询和聚合统计。
 */
@Slf4j
@Component
public class Neo4jProjectionRepository {

    private final Driver neo4jDriver;

    public Neo4jProjectionRepository(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
    }

    /**
     * 为统一图谱优化：使用 map projection 减少数据传输量（仅返回前端需要的字段）
     */
    public List<Map<String, Object>> queryNodesProjection(String projectId, String versionId,
                                                          Double minConfidence, String status) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder("MATCH (n) WHERE n.projectId = $projectId");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            if (versionId != null) {
                cypher.append(" AND n.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            }
            if (minConfidence != null) {
                cypher.append(" AND n.confidence >= $minConfidence");
                params.put("minConfidence", minConfidence);
            }
            if (status != null) {
                cypher.append(" AND n.status = $status");
                params.put("status", status);
            }
            cypher.append(" RETURN n.id AS id, n.nodeKey AS key, n.nodeName AS name, ")
                  .append("n.displayName AS label, labels(n)[0] AS type, ")
                  .append("n.confidence AS confidence, n.status AS status, ")
                  .append("n.description AS description, n.sourcePath AS sourcePath, ")
                  .append("n.sourceType AS sourceType, n.verifiedScore AS verifiedScore, ")
                  .append("n.runtimeVerified AS runtimeVerified, n.lastSeenAt AS lastSeenAt, ")
                  .append("n.traceCount AS traceCount ");
            cypher.append("ORDER BY n.confidence DESC");

            Result result = session.run(cypher.toString(), params);
            List<Map<String, Object>> nodes = new ArrayList<>();
            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                Map<String, Object> nodeMap = new HashMap<>();
                nodeMap.put("id", record.get("id").isNull() ? null : record.get("id").asString());
                nodeMap.put("key", record.get("key").isNull() ? null : record.get("key").asString());
                nodeMap.put("name", record.get("name").isNull() ? null : record.get("name").asString());
                nodeMap.put("label", record.get("label").isNull() ? null : record.get("label").asString());
                nodeMap.put("type", record.get("type").isNull() ? null : record.get("type").asString());
                nodeMap.put("confidence", record.get("confidence").isNull() ? null : record.get("confidence").asDouble());
                nodeMap.put("status", record.get("status").isNull() ? null : record.get("status").asString());
                nodeMap.put("description", record.get("description").isNull() ? null : record.get("description").asString());
                nodeMap.put("sourcePath", record.get("sourcePath").isNull() ? null : record.get("sourcePath").asString());
                nodeMap.put("sourceType", record.get("sourceType").isNull() ? null : record.get("sourceType").asString());
                nodeMap.put("verifiedScore", record.get("verifiedScore").isNull() ? null : record.get("verifiedScore").asDouble());
                nodeMap.put("runtimeVerified", record.get("runtimeVerified").isNull() ? null : record.get("runtimeVerified").asBoolean());
                nodeMap.put("lastSeenAt", record.get("lastSeenAt").isNull() ? null : record.get("lastSeenAt").asString());
                nodeMap.put("traceCount", record.get("traceCount").isNull() ? 0 : record.get("traceCount").asLong());
                nodes.add(nodeMap);
            }
            return nodes;
        }
    }

    /**
     * 为统一图谱优化：使用 map projection 减少数据传输量
     */
    public List<Map<String, Object>> queryEdgesProjection(String projectId, String versionId,
                                                           Double minConfidence, String status) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder(
                    "MATCH (from)-[r]->(to) WHERE r.projectId = $projectId");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            if (versionId != null) {
                cypher.append(" AND r.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            }
            if (minConfidence != null) {
                cypher.append(" AND r.confidence >= $minConfidence");
                params.put("minConfidence", minConfidence);
            }
            if (status != null) {
                cypher.append(" AND r.status = $status");
                params.put("status", status);
            }
            cypher.append(" RETURN r.id AS id, from.id AS source, to.id AS target, ")
                  .append("type(r) AS type, r.confidence AS confidence, r.status AS status ");

            Result result = session.run(cypher.toString(), params);
            List<Map<String, Object>> edges = new ArrayList<>();
            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                Map<String, Object> edgeMap = new HashMap<>();
                edgeMap.put("id", record.get("id").isNull() ? null : record.get("id").asString());
                edgeMap.put("source", record.get("source").isNull() ? null : record.get("source").asString());
                edgeMap.put("target", record.get("target").isNull() ? null : record.get("target").asString());
                edgeMap.put("type", record.get("type").isNull() ? null : record.get("type").asString());
                edgeMap.put("confidence", record.get("confidence").isNull() ? null : record.get("confidence").asDouble());
                edgeMap.put("status", record.get("status").isNull() ? null : record.get("status").asString());
                edges.add(edgeMap);
            }
            return edges;
        }
    }

    /**
     * 一次查询获取项目图统计信息（节点数、边数、确认率、置信度等）。
     */
    public Map<String, Object> graphStats(String projectId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId " +
                "WITH count(n) AS totalNodes, " +
                "     count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedNodes, " +
                "     count(CASE WHEN n.status IN ['PENDING', 'PENDING_CONFIRM'] THEN 1 END) AS pendingNodes, " +
                "     coalesce(avg(n.confidence), 0.0) AS avgConfidence, " +
                "     count(CASE WHEN n.evidenceIds IS NOT NULL AND n.evidenceIds <> '' THEN 1 END) AS withEvidenceCount, " +
                "     count(CASE WHEN n.evidenceIds IS NULL OR n.evidenceIds = '' THEN 1 END) AS noEvidenceNodes, " +
                "     count(CASE WHEN n.sourceType IN ['AI_INFERENCE', 'DOC_AI'] THEN 1 END) AS aiOnlyNodes " +
                "OPTIONAL MATCH ()-[r]->() WHERE r.projectId = $projectId " +
                "RETURN totalNodes, confirmedNodes, pendingNodes, avgConfidence, withEvidenceCount, noEvidenceNodes, aiOnlyNodes, " +
                "       count(r) AS totalEdges, " +
                "       count(CASE WHEN r.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedEdges, " +
                "       count(CASE WHEN r.status IN ['PENDING', 'PENDING_CONFIRM'] THEN 1 END) AS pendingEdges, " +
                "       count(CASE WHEN r.evidenceIds IS NULL OR r.evidenceIds = '' THEN 1 END) AS noEvidenceEdges, " +
                "       count(CASE WHEN r.sourceType IN ['AI_INFERENCE', 'AI_FEATURE_MAPPING', 'DOC_AI'] THEN 1 END) AS aiOnlyEdges, " +
                "       count(CASE WHEN r.sourceType = 'RUNTIME_TRACE' THEN 1 END) AS runtimeOnlyEdges";
            Result result = session.run(cypher, Map.of("projectId", projectId));
            if (result.hasNext()) {
                return result.next().asMap();
            }
        }
        Map<String, Object> emptyStats = new LinkedHashMap<>();
        emptyStats.put("totalNodes", 0L);
        emptyStats.put("confirmedNodes", 0L);
        emptyStats.put("pendingNodes", 0L);
        emptyStats.put("avgConfidence", 0.0);
        emptyStats.put("withEvidenceCount", 0L);
        emptyStats.put("noEvidenceNodes", 0L);
        emptyStats.put("aiOnlyNodes", 0L);
        emptyStats.put("totalEdges", 0L);
        emptyStats.put("confirmedEdges", 0L);
        emptyStats.put("pendingEdges", 0L);
        emptyStats.put("noEvidenceEdges", 0L);
        emptyStats.put("aiOnlyEdges", 0L);
        emptyStats.put("runtimeOnlyEdges", 0L);
        return emptyStats;
    }

    /**
     * 按节点类型聚合统计（用于迁移就绪度报告）。
     */
    public List<Map<String, Object>> nodeTypeStats(String projectId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId " +
                "UNWIND labels(n) AS label " +
                "WITH label AS nodeType, " +
                "     count(*) AS total, " +
                "     count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmed, " +
                "     coalesce(avg(n.confidence), 0.0) AS avgConfidence " +
                "RETURN nodeType, total, confirmed, avgConfidence " +
                "ORDER BY total DESC";
            Result result = session.run(cypher, Map.of("projectId", projectId));
            List<Map<String, Object>> stats = new ArrayList<>();
            while (result.hasNext()) {
                stats.add(result.next().asMap());
            }
            return stats;
        }
    }

    /**
     * 版本维度的图统计（类似 graphStats，但限定 versionId）。
     */
    public Map<String, Object> versionGraphStats(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "WITH count(n) AS totalNodes, " +
                "     count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedNodes, " +
                "     count(CASE WHEN n.status IN ['PENDING', 'PENDING_CONFIRM'] THEN 1 END) AS pendingNodes, " +
                "     coalesce(avg(n.confidence), 0.0) AS avgConfidence, " +
                "     count(CASE WHEN n.evidenceIds IS NOT NULL AND n.evidenceIds <> '' THEN 1 END) AS withEvidenceCount, " +
                "     count(CASE WHEN n.evidenceIds IS NULL OR n.evidenceIds = '' THEN 1 END) AS noEvidenceNodes, " +
                "     count(CASE WHEN n.sourceType IN ['AI_INFERENCE', 'DOC_AI'] THEN 1 END) AS aiOnlyNodes, " +
                "     count(CASE WHEN coalesce(n.verifiedScore, 0.0) > 0 THEN 1 END) AS runtimeVerifiedCount " +
                "OPTIONAL MATCH ()-[r]->() WHERE r.projectId = $projectId AND r.versionId = $versionId " +
                "RETURN totalNodes, confirmedNodes, pendingNodes, avgConfidence, withEvidenceCount, noEvidenceNodes, aiOnlyNodes, runtimeVerifiedCount, " +
                "       count(r) AS totalEdges, " +
                "       count(CASE WHEN r.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedEdges, " +
                "       count(CASE WHEN r.status IN ['PENDING', 'PENDING_CONFIRM'] THEN 1 END) AS pendingEdges, " +
                "       count(CASE WHEN r.evidenceIds IS NULL OR r.evidenceIds = '' THEN 1 END) AS noEvidenceEdges, " +
                "       count(CASE WHEN r.sourceType IN ['AI_INFERENCE', 'AI_FEATURE_MAPPING', 'DOC_AI'] THEN 1 END) AS aiOnlyEdges, " +
                "       count(CASE WHEN r.sourceType = 'RUNTIME_TRACE' THEN 1 END) AS runtimeOnlyEdges";
            Result result = session.run(cypher, Map.of("projectId", projectId, "versionId", normalizeId(versionId)));
            if (result.hasNext()) return result.next().asMap();
        }
        Map<String, Object> emptyStats = new LinkedHashMap<>();
        emptyStats.put("totalNodes", 0L);
        emptyStats.put("confirmedNodes", 0L);
        emptyStats.put("pendingNodes", 0L);
        emptyStats.put("avgConfidence", 0.0);
        emptyStats.put("withEvidenceCount", 0L);
        emptyStats.put("noEvidenceNodes", 0L);
        emptyStats.put("aiOnlyNodes", 0L);
        emptyStats.put("runtimeVerifiedCount", 0L);
        emptyStats.put("totalEdges", 0L);
        emptyStats.put("confirmedEdges", 0L);
        emptyStats.put("pendingEdges", 0L);
        emptyStats.put("noEvidenceEdges", 0L);
        emptyStats.put("aiOnlyEdges", 0L);
        emptyStats.put("runtimeOnlyEdges", 0L);
        return emptyStats;
    }

    /**
     * 每日置信度趋势聚合（按节点创建日期分组）。
     */
    public List<Map<String, Object>> confidenceTrendDaily(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "RETURN date(n.createdAt) AS date, " +
                "       coalesce(avg(n.confidence), 0.0) AS avgConfidence, " +
                "       count(*) AS newNodes, " +
                "       count(CASE WHEN n.status IN ['CONFIRMED', 'APPROVED'] THEN 1 END) AS confirmedNodes " +
                "ORDER BY date";
            Result result = session.run(cypher, Map.of("projectId", projectId, "versionId", normalizeId(versionId)));
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) rows.add(result.next().asMap());
            return rows;
        }
    }

    /**
     * 置信度分布直方图（5 个区间: 0-0.2, 0.2-0.4, 0.4-0.6, 0.6-0.8, 0.8-1.0）。
     */
    public List<Map<String, Object>> confidenceDistribution(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "WITH coalesce(n.confidence, 0.0) AS c " +
                "RETURN " +
                "  sum(CASE WHEN c >= 0.0 AND c < 0.2 THEN 1 ELSE 0 END) AS bin0, " +
                "  sum(CASE WHEN c >= 0.2 AND c < 0.4 THEN 1 ELSE 0 END) AS bin1, " +
                "  sum(CASE WHEN c >= 0.4 AND c < 0.6 THEN 1 ELSE 0 END) AS bin2, " +
                "  sum(CASE WHEN c >= 0.6 AND c < 0.8 THEN 1 ELSE 0 END) AS bin3, " +
                "  sum(CASE WHEN c >= 0.8 AND c <= 1.0 THEN 1 ELSE 0 END) AS bin4";
            Result result = session.run(cypher, Map.of("projectId", projectId, "versionId", normalizeId(versionId)));
            if (result.hasNext()) {
                List<Map<String, Object>> list = new ArrayList<>();
                list.add(result.next().asMap());
                return list;
            }
            return List.of(Map.of("bin0", 0L, "bin1", 0L, "bin2", 0L, "bin3", 0L, "bin4", 0L));
        }
    }

    /**
     * 平均节点度数（版本维度）。
     */
    public double averageNodeDegree(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n)-[r]-() WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "AND r.projectId = $projectId AND r.versionId = $versionId " +
                "WITH n, count(r) AS degree " +
                "RETURN coalesce(avg(degree), 0.0) AS avgDegree";
            Result result = session.run(cypher, Map.of("projectId", projectId, "versionId", normalizeId(versionId)));
            if (result.hasNext()) {
                Object v = result.next().get("avgDegree");
                if (v instanceof Number n) return n.doubleValue();
            }
        }
        return 0.0;
    }

    /**
     * 每个 ApiEndpoint 的实现层回溯：Controller / Service / Table。
     * <p>
     * 真实边方向：{@code ApiEndpoint -HANDLED_BY-> Method}，Method 被
     * {@code Controller/Service/Mapper -CONTAINS->} 包含（父→子），Mapper 经
     * {@code SqlStatement -READS/WRITES-> Table} 访问表。因此从 API 出发需双向遍历：
     * 沿 HANDLED_BY 到 Method，再沿 INCOMING CONTAINS 找到 Controller/Service/Mapper，
     * Mapper 沿 EXECUTES→SqlStatement→READS/WRITES 找到 Table；Controller -CALLS-> Service 作为补充。
     * </p>
     * <p>
     * 返回字段：nodeKey / displayName / controllers / services / tables（List&lt;String&gt;）。
     * 仅返回至少有 Controller/Service/Table 之一的 API（无任何实现的 API 跳过）。
     * </p>
     */
    public List<Map<String, Object>> apiImplementationRelations(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (api:ApiEndpoint {projectId: $projectId, versionId: $versionId}) " +
                "MATCH (api)-[:HANDLED_BY]->(m:Method) " +
                "OPTIONAL MATCH (ctrl:Controller)-[:CONTAINS]->(m) " +
                "OPTIONAL MATCH (svc:Service)-[:CONTAINS]->(m) " +
                "OPTIONAL MATCH (mapper:Mapper)-[:CONTAINS]->(m) " +
                "OPTIONAL MATCH (ctrl)-[:CALLS]->(calledSvc:Service) " +
                "OPTIONAL MATCH (mapper)-[:EXECUTES]->(:SqlStatement)-[:READS|WRITES|JOINS]->(tbl:Table) " +
                "WITH api, " +
                "     [x IN collect(DISTINCT ctrl.nodeName) WHERE x IS NOT NULL] AS controllers, " +
                "     [x IN collect(DISTINCT coalesce(svc.nodeName, calledSvc.nodeName)) WHERE x IS NOT NULL] AS services, " +
                "     [x IN collect(DISTINCT tbl.nodeName) WHERE x IS NOT NULL] AS tables " +
                "WHERE size(controllers) > 0 OR size(services) > 0 OR size(tables) > 0 " +
                "RETURN api.nodeKey AS nodeKey, " +
                "       coalesce(api.displayName, api.nodeName, api.nodeKey) AS displayName, " +
                "       controllers, services, tables";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "versionId", normalizeId(versionId)));
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                rows.add(result.next().asMap());
            }
            return rows;
        }
    }

    /**
     * 每个 Mapper 访问的 Table 集合：{@code Mapper -EXECUTES-> SqlStatement -READS/WRITES/JOINS-> Table}。
     * <p>
     * API 锚定的回溯（{@link #apiImplementationRelations}）在 Service↔Mapper 无边的项目里够不到表，
     * 但 Mapper→SqlStatement→Table 这条链通常是通的。本查询直接以 Mapper 为锚补全数据表访问关系，
     * 让「哪些数据库表」这类列举题在向量化后有结构化内容可召回。
     * </p>
     * <p>返回字段：mapperKey / mapperName / tables（List&lt;String&gt;，去重）。</p>
     */
    public List<Map<String, Object>> tableAccessRelations(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (mp:Mapper {projectId: $projectId, versionId: $versionId}) " +
                "MATCH (mp)-[:EXECUTES]->(:SqlStatement)-[:READS|WRITES|JOINS]->(t:Table) " +
                "RETURN mp.nodeKey AS mapperKey, " +
                "       coalesce(nullIf(mp.displayName, ''), nullIf(mp.nodeName, ''), mp.nodeKey) AS mapperName, " +
                "       [x IN collect(DISTINCT coalesce(nullIf(t.nodeName, ''), t.nodeKey)) WHERE x IS NOT NULL] AS tables " +
                "ORDER BY mapperName";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "versionId", normalizeId(versionId)));
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                rows.add(result.next().asMap());
            }
            return rows;
        }
    }
}
