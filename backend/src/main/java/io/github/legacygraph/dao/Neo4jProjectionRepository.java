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
                "  AND n.createdAt IS NOT NULL " +
                "RETURN date(substring(toString(n.createdAt), 0, 10)) AS date, " +
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

    /**
     * 查询 Neo4j 中真实 BusinessDomain 节点及其 CONTAINS 关系所连接的目标（Feature 等）。
     * <p>
     * 用于 {@code SystemOverviewIngestService} 从图谱回溯业务域→功能的 Claim，
     * 替代原先仅靠 Controller 名近似业务域的做法，让系统关系总览的"业务域"统计与统一图谱一致。
     * </p>
     * <p>返回字段：domainName / domainDisplayName / features（List&lt;String&gt;，CONTAINS 目标节点名）。</p>
     */
    public List<Map<String, Object>> businessDomainContains(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (bd:BusinessDomain {projectId: $projectId, versionId: $versionId}) " +
                "OPTIONAL MATCH (bd)-[:CONTAINS]->(target) " +
                "WITH bd, target " +
                "WHERE target IS NULL OR target:Feature OR target:BusinessProcess OR target:BusinessObject " +
                "RETURN coalesce(bd.displayName, bd.nodeName) AS domainDisplayName, " +
                "       coalesce(bd.nodeName, bd.nodeKey) AS domainName, " +
                "       [x IN collect(DISTINCT coalesce(nullIf(target.nodeName, ''), target.nodeKey)) " +
                "         WHERE x IS NOT NULL] AS features";
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
     * 窗口查询节点 — 支持游标分页、多类型/来源/状态过滤。
     * 按 nodeKey 排序确保分页稳定性，返回前端投影字段。
     */
    public List<Map<String, Object>> queryNodesWindow(String projectId, String versionId,
                                                       List<String> nodeTypes, List<String> sourceTypes,
                                                       String status, Double minConfidence,
                                                       String cursor, int limit) {
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder("MATCH (n) WHERE n.projectId = $projectId");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            if (versionId != null) {
                cypher.append(" AND n.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            }
            if (nodeTypes != null && !nodeTypes.isEmpty()) {
                cypher.append(" AND labels(n)[0] IN $nodeTypes");
                params.put("nodeTypes", nodeTypes);
            }
            if (sourceTypes != null && !sourceTypes.isEmpty()) {
                cypher.append(" AND n.sourceType IN $sourceTypes");
                params.put("sourceTypes", sourceTypes);
            }
            if (status != null) {
                cypher.append(" AND n.status = $status");
                params.put("status", status);
            }
            if (minConfidence != null) {
                cypher.append(" AND n.confidence >= $minConfidence");
                params.put("minConfidence", minConfidence);
            }
            if (cursor != null && !cursor.isBlank()) {
                cypher.append(" AND n.nodeKey > $cursor");
                params.put("cursor", cursor);
            }
            cypher.append(" RETURN n.id AS id, n.nodeKey AS nodeKey, n.nodeName AS nodeName, ")
                  .append("n.displayName AS displayName, labels(n)[0] AS nodeType, ")
                  .append("n.confidence AS confidence, n.status AS status, n.sourceType AS sourceType ")
                  .append("ORDER BY n.nodeKey ASC LIMIT $limit");
            params.put("limit", (long) limit);

            Result result = session.run(cypher.toString(), params);
            List<Map<String, Object>> nodes = new ArrayList<>();
            while (result.hasNext()) {
                nodes.add(result.next().asMap());
            }
            return nodes;
        }
    }

    /**
     * 查询指定节点集合之间的所有边（投影字段）。
     */
    public List<Map<String, Object>> queryEdgesForNodes(String projectId, String versionId,
                                                         List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return List.of();
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (from)-[r]->(to) " +
                "WHERE r.projectId = $projectId " +
                "AND from.id IN $nodeIds AND to.id IN $nodeIds " +
                "AND r.versionId = $versionId " +
                "RETURN r.id AS id, type(r) AS type, from.id AS source, to.id AS target";
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("versionId", normalizeId(versionId));
            params.put("nodeIds", nodeIds);

            Result result = session.run(cypher, params);
            List<Map<String, Object>> edges = new ArrayList<>();
            while (result.hasNext()) {
                edges.add(result.next().asMap());
            }
            return edges;
        }
    }

    // ==================== 图谱质量评估查询 ====================

    /**
     * 版本级节点类型分布 — 按标签聚合各类型节点数。
     * <p>用于图谱质量评估的完整性指标。</p>
     *
     * @return 每行包含 {nodeType, count}
     */
    public List<Map<String, Object>> nodeTypeDistribution(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "UNWIND labels(n) AS label " +
                "WITH label AS nodeType, count(*) AS cnt " +
                "RETURN nodeType, cnt " +
                "ORDER BY cnt DESC";
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
     * 版本级边类型分布 — 按关系类型聚合各类型边数。
     *
     * @return 每行包含 {edgeType, count}
     */
    public List<Map<String, Object>> edgeTypeDistribution(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH ()-[r]->() WHERE r.projectId = $projectId AND r.versionId = $versionId " +
                "RETURN type(r) AS edgeType, count(*) AS cnt " +
                "ORDER BY cnt DESC";
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
     * 统计孤立节点数（无任何边的节点）。
     */
    public long countIsolatedNodes(String projectId, String versionId) {
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (n) WHERE n.projectId = $projectId AND n.versionId = $versionId " +
                "OPTIONAL MATCH (n)-[r]-() WHERE r.projectId = $projectId AND r.versionId = $versionId " +
                "WITH n, count(r) AS cnt WHERE cnt = 0 " +
                "RETURN count(n) AS total";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "versionId", normalizeId(versionId)));
            if (result.hasNext()) {
                Object v = result.next().get("total");
                if (v instanceof Number n) return n.longValue();
            }
            return 0L;
        }
    }

    /**
     * 统计指定标签中缺少必需边类型的节点数（约束违反计数）。
     * <p>
     * 检查某类节点是否至少有一条指定类型的边（任意方向）。
     * 例如：Method 节点是否至少有一条 CONTAINS 或 BELONGS_TO 边。
     * </p>
     *
     * @param nodeLabel  节点标签（如 "Method", "Column", "ApiEndpoint", "SqlStatement"）
     * @param edgeTypes  必需的边类型列表（满足任意一个即可）
     * @return 缺少所有指定边类型的节点数
     */
    public long countNodesWithoutEdgeTypes(String projectId, String versionId,
                                            String nodeLabel, List<String> edgeTypes) {
        if (edgeTypes == null || edgeTypes.isEmpty()) return 0L;
        try (Session session = neo4jDriver.session()) {
            // 构建关系类型过滤条件：type(r) IN $edgeTypes
            String cypher =
                "MATCH (n:" + CypherCatalog.safeIdentifier(nodeLabel, "nodeLabel") +
                " {projectId: $projectId, versionId: $versionId}) " +
                "OPTIONAL MATCH (n)-[r]-() WHERE r.projectId = $projectId AND r.versionId = $versionId " +
                "AND type(r) IN $edgeTypes " +
                "WITH n, count(r) AS cnt WHERE cnt = 0 " +
                "RETURN count(n) AS total";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "versionId", normalizeId(versionId),
                    "edgeTypes", edgeTypes));
            if (result.hasNext()) {
                Object v = result.next().get("total");
                if (v instanceof Number n) return n.longValue();
            }
            return 0L;
        }
    }

    // ==================== Blast Radius 传播分析查询 ====================

    /**
     * 按 sourcePath 查询文件中包含的所有图谱节点（用于变更影响分析）。
     * <p>versionId 为 null 时不限定版本，查询项目下所有版本中来自该文件的节点。</p>
     *
     * @return 每行包含 {nodeId, nodeKey, nodeName, nodeType, sourcePath}
     */
    public List<Map<String, Object>> findNodesBySourcePath(String projectId, String versionId,
                                                            String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) return List.of();
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder(
                "MATCH (n) WHERE n.projectId = $projectId AND n.sourcePath = $sourcePath");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("sourcePath", sourcePath);
            if (versionId != null) {
                cypher.append(" AND n.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            }
            cypher.append(" RETURN n.id AS nodeId, n.nodeKey AS nodeKey, n.nodeName AS nodeName, ")
                  .append("labels(n)[0] AS nodeType, n.sourcePath AS sourcePath");
            Result result = session.run(cypher.toString(), params);
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                rows.add(result.next().asMap());
            }
            return rows;
        }
    }

    /**
     * 反向依赖查询 — 查找指向目标节点集合的入边源节点（Blast Radius 传播分析核心查询）。
     * <p>给定一组目标节点（变更文件中的节点），查找所有 (source)-[r]->(target) 中
     * target 属于目标集合、且边类型在白名单内的 source 节点，即受变更影响的依赖者。</p>
     * <p>versionId 为 null 时不限定版本。</p>
     *
     * @param targetNodeIds 变更文件中节点的 ID 集合
     * @param edgeTypes     反向遍历的边类型白名单（CALLS/READS/WRITES/BELONGS_TO/DEPENDS_ON/IMPLEMENTS/EXTENDS）
     * @return 每行包含 {sourceId, sourceKey, sourceName, sourceType, targetId, targetKey, edgeType}
     */
    public List<Map<String, Object>> findReverseDependents(String projectId, String versionId,
                                                            Collection<String> targetNodeIds,
                                                            List<String> edgeTypes) {
        if (targetNodeIds == null || targetNodeIds.isEmpty()
                || edgeTypes == null || edgeTypes.isEmpty()) {
            return List.of();
        }
        try (Session session = neo4jDriver.session()) {
            StringBuilder cypher = new StringBuilder(
                "MATCH (source)-[r]->(target) " +
                "WHERE target.id IN $targetNodeIds " +
                "AND r.projectId = $projectId " +
                "AND type(r) IN $edgeTypes");
            Map<String, Object> params = new HashMap<>();
            params.put("projectId", projectId);
            params.put("targetNodeIds", new ArrayList<>(targetNodeIds));
            params.put("edgeTypes", edgeTypes);
            if (versionId != null) {
                cypher.append(" AND r.versionId = $versionId");
                params.put("versionId", normalizeId(versionId));
            }
            cypher.append(" RETURN DISTINCT source.id AS sourceId, source.nodeKey AS sourceKey, ")
                  .append("source.nodeName AS sourceName, labels(source)[0] AS sourceType, ")
                  .append("target.id AS targetId, target.nodeKey AS targetKey, ")
                  .append("type(r) AS edgeType");
            Result result = session.run(cypher.toString(), params);
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                rows.add(result.next().asMap());
            }
            return rows;
        }
    }

    /**
     * 查询指定节点集合之间的所有边（仅按 projectId 过滤，不限定 versionId）。
     * <p>用于 Blast Radius 受影响子图的边集构建。</p>
     *
     * @return 每行包含 {id, type, source, target}
     */
    public List<Map<String, Object>> queryEdgesForNodesByProject(String projectId, List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return List.of();
        try (Session session = neo4jDriver.session()) {
            String cypher =
                "MATCH (from)-[r]->(to) " +
                "WHERE r.projectId = $projectId " +
                "AND from.id IN $nodeIds AND to.id IN $nodeIds " +
                "RETURN r.id AS id, type(r) AS type, from.id AS source, to.id AS target";
            Result result = session.run(cypher, Map.of(
                    "projectId", projectId,
                    "nodeIds", nodeIds));
            List<Map<String, Object>> edges = new ArrayList<>();
            while (result.hasNext()) {
                edges.add(result.next().asMap());
            }
            return edges;
        }
    }

    /**
     * 通用只读 Cypher 查询，返回每行结果 asMap。
     * 供 service 层调用，避免 service 直接依赖 Neo4j Driver。
     */
    public List<Map<String, Object>> executeReadQuery(String cypher, Map<String, Object> params) {
        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, params != null ? params : Map.of());
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                rows.add(result.next().asMap());
            }
            return rows;
        }
    }
}
