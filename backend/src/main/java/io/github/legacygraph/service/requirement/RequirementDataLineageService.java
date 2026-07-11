package io.github.legacygraph.service.requirement;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.FlowDirection;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.requirement.DataLineageResponse;
import io.github.legacygraph.entity.GraphNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 需求-表/字段反向溯源服务（G-16）。
 * <p>从需求 ID 出发反向追溯受影响的数据库表和字段，聚合为 {@link DataLineageResponse}。</p>
 *
 * <p>追溯链路：
 * <ol>
 *   <li>通过 requirementId 查找关联的 RequirementItem 图谱节点
 *       （nodeKey 形如 {@code req:{requirementId}:{code}}）</li>
 *   <li>沿 AFFECTS 出边找到目标节点（Table / Column / Method / Service 等）</li>
 *   <li>从每个目标节点沿 CALLS / READS / WRITES / MAPS_TO / HAS_COLUMN 等边的
 *       <b>反向</b>（INBOUND）路径查找 Table 与 Column 节点</li>
 *   <li>聚合为 TableImpact 列表 + Summary（含表数、字段数、最大风险分数）</li>
 * </ol>
 * </p>
 *
 * <p>风险分数规则：直接 AFFECTS 命中 Table=1.0；反向追溯命中 Table=0.7；
 * Column 直接命中=1.0（提升父表至 1.0），反向追溯命中 Column=0.5。</p>
 */
@Slf4j
@Service
public class RequirementDataLineageService {

    /** 反向追溯最大跳数 */
    private static final int MAX_DEPTH = 4;

    /** 每个起点的路径数上限 */
    private static final int MAX_PATHS = 100;

    /** RequirementItem 节点 nodeKey 前缀：req:{requirementId}: */
    private static final String ITEM_KEY_PREFIX = "req:";

    /** 反向追溯边类型白名单（覆盖 Method/Mapper/SqlStatement→Table 与 Table→Column） */
    private static final List<String> TRACE_EDGE_WHITELIST = List.of(
            EdgeType.CALLS.name(),
            EdgeType.READS.name(),
            EdgeType.WRITES.name(),
            EdgeType.MAPS_TO.name(),
            EdgeType.HAS_COLUMN.name(),
            EdgeType.BELONGS_TO.name(),
            EdgeType.DATA_FLOW.name(),
            EdgeType.IMPLEMENTED_BY.name(),
            EdgeType.EXPOSED_BY.name(),
            EdgeType.DEPENDS_ON.name(),
            EdgeType.JOINS.name(),
            EdgeType.REFERENCES.name());

    /** 直接命中 AFFECTS 目标为 Table 的风险分数 */
    private static final double RISK_DIRECT_TABLE = 1.0;
    /** 直接命中 AFFECTS 目标为 Column 的风险分数（提升父表） */
    private static final double RISK_DIRECT_COLUMN = 1.0;
    /** 反向追溯命中 Table 的风险分数 */
    private static final double RISK_TRACE_TABLE = 0.7;
    /** 反向追溯命中 Column 的风险分数（父表取 0.5） */
    private static final double RISK_TRACE_COLUMN = 0.5;

    private final Neo4jGraphDao neo4jGraphDao;

    public RequirementDataLineageService(Neo4jGraphDao neo4jGraphDao) {
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 从需求出发反向追溯受影响的表和字段。
     *
     * @param projectId     项目 ID
     * @param versionId     扫描版本 ID（可为 null，跨版本查询）
     * @param requirementId 需求 ID
     * @return 数据血缘响应；若未找到 RequirementItem 节点或无 AFFECTS 边则返回空结果
     */
    public DataLineageResponse traceDataLineage(String projectId, String versionId, String requirementId) {
        if (projectId == null || projectId.isBlank() || requirementId == null || requirementId.isBlank()) {
            return emptyResponse();
        }

        // 1. 通过 requirementId 查找关联的 RequirementItem 图谱节点
        List<GraphNode> itemNodes = findRequirementItemNodes(projectId, versionId, requirementId);
        if (itemNodes.isEmpty()) {
            log.warn("RequirementDataLineage: no RequirementItem nodes for projectId={}, requirementId={}",
                    projectId, requirementId);
            return emptyResponse();
        }
        log.debug("RequirementDataLineage: found {} RequirementItem nodes for requirementId={}",
                itemNodes.size(), requirementId);

        // 2. 沿 AFFECTS 出边找到目标节点
        Set<String> targetNodeIds = findAffectsTargets(itemNodes);
        if (targetNodeIds.isEmpty()) {
            log.info("RequirementDataLineage: no AFFECTS targets for requirementId={}", requirementId);
            return emptyResponse();
        }

        // 3. 反向追溯 Table / Column
        Map<String, TableAggregator> tableMap = new LinkedHashMap<>();
        for (String targetNodeId : targetNodeIds) {
            Optional<GraphNode> targetOpt = neo4jGraphDao.findNodeById(targetNodeId);
            if (targetOpt.isEmpty()) {
                continue;
            }
            GraphNode target = targetOpt.get();
            handleTargetNode(projectId, versionId, target, tableMap);
        }

        // 4. 聚合为 DataLineageResponse
        return buildResponse(tableMap);
    }

    // ==================== Step 1: 查找 RequirementItem 节点 ====================

    /**
     * 查找需求关联的 RequirementItem 图谱节点。
     * <p>RequirementItem 节点的 nodeKey 形如 {@code req:{requirementId}:{code}}，
     * 使用 Cypher {@code STARTS WITH} 前缀匹配查询。返回标量字段避免 Node 类型转换。</p>
     */
    private List<GraphNode> findRequirementItemNodes(String projectId, String versionId, String requirementId) {
        String cypher = "MATCH (n) WHERE n.projectId=$projectId " +
                "AND ($versionId IS NULL OR n.versionId=$versionId) " +
                "AND n.nodeType=$nodeType " +
                "AND n.nodeKey STARTS WITH $prefix " +
                "RETURN n.id AS id, n.nodeKey AS nodeKey, n.nodeName AS nodeName, " +
                "n.nodeType AS nodeType LIMIT 100";
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("versionId", versionId);
        params.put("nodeType", NodeType.RequirementItem.name());
        params.put("prefix", ITEM_KEY_PREFIX + requirementId + ":");
        try {
            List<Map<String, Object>> rows = neo4jGraphDao.executeReadQuery(cypher, params);
            List<GraphNode> nodes = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                GraphNode node = new GraphNode();
                node.setId(asString(row.get("id")));
                node.setNodeKey(asString(row.get("nodeKey")));
                node.setNodeName(asString(row.get("nodeName")));
                node.setNodeType(asString(row.get("nodeType")));
                node.setProjectId(projectId);
                node.setVersionId(versionId);
                if (node.getId() != null) {
                    nodes.add(node);
                }
            }
            return nodes;
        } catch (Exception e) {
            log.warn("findRequirementItemNodes failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== Step 2: 沿 AFFECTS 出边查找目标节点 ====================

    /**
     * 沿 AFFECTS 出边查找目标节点 ID 集合。
     * <p>AFFECTS 边方向：RequirementItem → 目标节点（由 {@link RequirementLinkingService#createAffectsEdge} 创建）。</p>
     */
    private Set<String> findAffectsTargets(List<GraphNode> itemNodes) {
        Set<String> targetNodeIds = new LinkedHashSet<>();
        for (GraphNode itemNode : itemNodes) {
            if (itemNode.getId() == null) {
                continue;
            }
            try {
                String cypher = "MATCH (n)-[:AFFECTS]->(m) WHERE n.id=$nodeId " +
                        "RETURN m.id AS targetId LIMIT 200";
                Map<String, Object> params = new HashMap<>();
                params.put("nodeId", itemNode.getId());
                List<Map<String, Object>> rows = neo4jGraphDao.executeReadQuery(cypher, params);
                for (Map<String, Object> row : rows) {
                    String tid = asString(row.get("targetId"));
                    if (tid != null && !tid.isBlank()) {
                        targetNodeIds.add(tid);
                    }
                }
            } catch (Exception e) {
                log.warn("findAffectsTargets failed for nodeId={}: {}", itemNode.getId(), e.getMessage());
            }
        }
        return targetNodeIds;
    }

    // ==================== Step 3: 反向追溯 Table / Column ====================

    /**
     * 处理单个 AFFECTS 目标节点：
     * <ul>
     *   <li>若目标本身就是 Table → 直接加入 tableMap（risk=1.0）</li>
     *   <li>若目标本身就是 Column → 找父 Table 并加入其 impactedColumns（risk=1.0）</li>
     *   <li>否则沿白名单边 INBOUND 反向追溯 Table / Column</li>
     * </ul>
     */
    private void handleTargetNode(String projectId, String versionId,
                                  GraphNode target, Map<String, TableAggregator> tableMap) {
        String nodeType = target.getNodeType();
        if (NodeType.Table.name().equals(nodeType)) {
            // 直接命中 Table
            upsertTable(tableMap, target, RISK_DIRECT_TABLE);
            return;
        }
        if (NodeType.Column.name().equals(nodeType)) {
            // 直接命中 Column → 找父 Table 并提升风险
            addColumnToParentTable(tableMap, target, RISK_DIRECT_COLUMN, true);
            return;
        }
        // 反向追溯：沿白名单边 INBOUND
        if (target.getNodeKey() == null || target.getNodeKey().isBlank()) {
            return;
        }
        try {
            List<Neo4jGraphDao.GraphPath> paths = neo4jGraphDao.findPathsDirected(
                    projectId, versionId, target.getNodeKey(),
                    TRACE_EDGE_WHITELIST, FlowDirection.INBOUND, MAX_DEPTH, MAX_PATHS);
            for (Neo4jGraphDao.GraphPath path : paths) {
                collectFromPath(path, tableMap);
            }
        } catch (Exception e) {
            log.warn("traceBackToTables failed for target {}: {}",
                    target.getNodeKey(), e.getMessage());
        }
    }

    /**
     * 从单条反向追溯路径中收集 Table / Column 节点。
     */
    private void collectFromPath(Neo4jGraphDao.GraphPath path, Map<String, TableAggregator> tableMap) {
        List<GraphNode> nodes = path.nodes();
        if (nodes == null) {
            return;
        }
        for (GraphNode n : nodes) {
            if (n == null || n.getNodeKey() == null) {
                continue;
            }
            String type = n.getNodeType();
            if (NodeType.Table.name().equals(type)) {
                upsertTable(tableMap, n, RISK_TRACE_TABLE);
            } else if (NodeType.Column.name().equals(type)) {
                addColumnToParentTable(tableMap, n, RISK_TRACE_COLUMN, false);
            }
        }
    }

    /**
     * 将 Column 节点追加到其所属 Table 的 impactedColumns。
     * <p>通过 HAS_COLUMN 反向边查找父 Table：{@code (t:Table)-[:HAS_COLUMN]->(c:Column)}。</p>
     *
     * @param columnNode      Column 节点
     * @param risk            Column 风险分数
     * @param promoteParentRisk 若为 true，将父表风险提升至该风险值（直接命中场景）
     */
    private void addColumnToParentTable(Map<String, TableAggregator> tableMap,
                                        GraphNode columnNode, double risk, boolean promoteParentRisk) {
        if (columnNode.getId() == null) {
            return;
        }
        try {
            String cypher = "MATCH (t:Table)-[:HAS_COLUMN]->(c:Column) " +
                    "WHERE c.id=$columnId " +
                    "RETURN t.nodeKey AS tableKey, t.nodeName AS tableName, " +
                    "t.evidenceIds AS evidenceIds LIMIT 1";
            Map<String, Object> params = new HashMap<>();
            params.put("columnId", columnNode.getId());
            List<Map<String, Object>> rows = neo4jGraphDao.executeReadQuery(cypher, params);
            if (rows.isEmpty()) {
                return;
            }
            Map<String, Object> row = rows.get(0);
            String tableKey = asString(row.get("tableKey"));
            if (tableKey == null || tableKey.isBlank()) {
                return;
            }
            String rawTableName = asString(row.get("tableName"));
            final String tableName = (rawTableName == null || rawTableName.isBlank()) ? tableKey : rawTableName;
            final String evidenceIds = asString(row.get("evidenceIds"));
            TableAggregator agg = tableMap.computeIfAbsent(tableKey, k -> {
                TableAggregator a = new TableAggregator();
                a.tableKey = tableKey;
                a.tableName = tableName;
                a.impactedColumns = new ArrayList<>();
                a.maxRisk = 0.0;
                a.rawEvidenceIds = evidenceIds;
                return a;
            });
            // 追加 Column 名称
            String colName = columnNode.getNodeName() != null
                    ? columnNode.getNodeName() : columnNode.getNodeKey();
            if (colName != null && !agg.impactedColumns.contains(colName)) {
                agg.impactedColumns.add(colName);
            }
            // 提升风险
            if (promoteParentRisk) {
                agg.maxRisk = Math.max(agg.maxRisk, risk);
            }
        } catch (Exception e) {
            log.debug("addColumnToParentTable failed for column {}: {}", columnNode.getId(), e.getMessage());
        }
    }

    /**
     * 在 tableMap 中 upsert 一张 Table 节点，并刷新其风险分数与证据 ID。
     */
    private void upsertTable(Map<String, TableAggregator> tableMap, GraphNode tableNode, double risk) {
        String key = tableNode.getNodeKey();
        if (key == null || key.isBlank()) {
            return;
        }
        TableAggregator agg = tableMap.computeIfAbsent(key, k -> {
            TableAggregator a = new TableAggregator();
            a.tableKey = key;
            a.tableName = tableNode.getNodeName() != null
                    ? tableNode.getNodeName() : key;
            a.impactedColumns = new ArrayList<>();
            a.maxRisk = 0.0;
            a.rawEvidenceIds = tableNode.getEvidenceIds();
            return a;
        });
        agg.maxRisk = Math.max(agg.maxRisk, risk);
        // 同步证据 ID（覆盖更全的字段）
        if (tableNode.getEvidenceIds() != null && !tableNode.getEvidenceIds().isBlank()) {
            agg.rawEvidenceIds = tableNode.getEvidenceIds();
        }
    }

    // ==================== Step 4: 构建响应 ====================

    /**
     * 将聚合 Map 转换为响应对象，按风险分数降序排列表，并解析证据 ID 字符串。
     */
    private DataLineageResponse buildResponse(Map<String, TableAggregator> tableMap) {
        List<DataLineageResponse.TableImpact> tables = new ArrayList<>();
        int columnCount = 0;
        double maxRisk = 0.0;
        for (TableAggregator agg : tableMap.values()) {
            List<String> columns = agg.impactedColumns;
            columnCount += columns.size();
            maxRisk = Math.max(maxRisk, agg.maxRisk);
            tables.add(DataLineageResponse.TableImpact.builder()
                    .tableKey(agg.tableKey)
                    .tableName(agg.tableName)
                    .impactedColumns(new ArrayList<>(columns))
                    .riskScore(agg.maxRisk)
                    .evidenceIds(parseEvidenceIds(agg.rawEvidenceIds))
                    .build());
        }
        // 按风险分数降序排列，再按 tableKey 升序（保证输出稳定）
        tables.sort(Comparator
                .comparingDouble(DataLineageResponse.TableImpact::getRiskScore).reversed()
                .thenComparing(DataLineageResponse.TableImpact::getTableKey));

        DataLineageResponse.Summary summary = DataLineageResponse.Summary.builder()
                .tableCount(tables.size())
                .columnCount(columnCount)
                .maxRiskScore(maxRisk)
                .build();
        return DataLineageResponse.builder()
                .tables(tables)
                .summary(summary)
                .build();
    }

    /**
     * 解析 GraphNode.evidenceIds 字段（JSON 数组字符串或逗号分隔字符串）为 List。
     */
    private List<String> parseEvidenceIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = raw.trim();
        List<String> result = new ArrayList<>();
        if (trimmed.startsWith("[")) {
            // JSON 数组格式，简单解析（避免引入 ObjectMapper 依赖）
            String inner = trimmed.substring(1, trimmed.length() - 1);
            for (String part : inner.split(",")) {
                String s = part.replaceAll("\"", "").trim();
                if (!s.isEmpty()) {
                    result.add(s);
                }
            }
        } else {
            for (String part : trimmed.split(",")) {
                String s = part.trim();
                if (!s.isEmpty()) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    /**
     * 空响应。
     */
    private DataLineageResponse emptyResponse() {
        return DataLineageResponse.builder()
                .tables(Collections.emptyList())
                .summary(DataLineageResponse.Summary.builder()
                        .tableCount(0)
                        .columnCount(0)
                        .maxRiskScore(0.0)
                        .build())
                .build();
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    /** 表聚合中间结构（在反向追溯过程中累积） */
    private static class TableAggregator {
        String tableKey;
        String tableName;
        List<String> impactedColumns;
        double maxRisk;
        String rawEvidenceIds;
    }
}
