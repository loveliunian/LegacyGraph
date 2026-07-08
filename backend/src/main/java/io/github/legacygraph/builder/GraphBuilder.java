package io.github.legacygraph.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.DatabaseConstraintExtractor;
import io.github.legacygraph.extractors.DatabaseMetadataExtractor.ColumnMetadata;
import io.github.legacygraph.extractors.JavaStructureExtractor;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.extractors.ServiceCallExtractor;
import io.github.legacygraph.extractors.SqlTableExtractor;
import io.github.legacygraph.model.ApiFact;
import io.github.legacygraph.model.MapperSqlFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import io.github.legacygraph.util.IdUtil;

/**
 * 统一图谱构建器
 * 将抽取的事实转换为图谱节点和关系，直写 Neo4j（不再经 PostgreSQL）
 */
@Slf4j
@Component
public class GraphBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Neo4jGraphDao neo4jGraphDao;
    private final EvidenceGraphWriter writer;

    public GraphBuilder(Neo4jGraphDao neo4jGraphDao,
                       EvidenceGraphWriter writer) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.writer = writer;
    }

    /**
     * 构建API接口图谱
     */
    public List<GraphNode> buildApiNodes(String projectId, String versionId, List<ApiFact> apiFacts, String sourcePath) {
        List<GraphNode> nodes = new ArrayList<>();

        for (ApiFact api : apiFacts) {
            // 创建Controller节点（如果不存在）
            String controllerNodeKey = api.getControllerPackage() + "." + api.getControllerClass();
            GraphNode controllerNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.Controller.name(),
                    controllerNodeKey,
                    api.getControllerClass(),
                    api.getControllerClass(),
                    null,
                    SourceType.CODE_AST.name(),
                    api.getSourcePath(),
                    null,
                    null,
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );
            nodes.add(controllerNode);

            // 创建ApiEndpoint节点
            String apiNodeKey = normalizeApiKey(api.getHttpMethod(), api.getFullPath());
            String displayName = api.getHttpMethod() + " " + api.getFullPath();
            GraphNode apiNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.ApiEndpoint.name(),
                    apiNodeKey,
                    displayName,
                    displayName,
                    "API接口: " + displayName,
                    SourceType.CODE_AST.name(),
                    api.getSourcePath(),
                    api.getStartLine(),
                    api.getEndLine(),
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );
            nodes.add(apiNode);

            // 创建Method节点 - 使用 methodSignature 对齐 JavaStructureExtractor
            String methodNodeKey;
            if (api.getMethodSignature() != null && !api.getMethodSignature().isEmpty()) {
                // 优先使用带参数签名的 key: packageName.className.methodName(paramType1, paramType2)
                methodNodeKey = controllerNodeKey + "." + api.getMethodSignature();
            } else {
                // 回退到简单方法名
                methodNodeKey = controllerNodeKey + "." + api.getMethodName();
            }
            GraphNode methodNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.Method.name(),
                    methodNodeKey,
                    api.getMethodName(),
                    api.getMethodName(),
                    null,
                    SourceType.CODE_AST.name(),
                    api.getSourcePath(),
                    api.getStartLine(),
                    api.getEndLine(),
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED,
                    "CODE_SCAN",
                    controllerNodeKey
            );
            nodes.add(methodNode);

            // 创建关系: ApiEndpoint -HANDLED_BY-> Controller.Method
            createEdge(projectId, versionId,
                    apiNode.getId(), methodNode.getId(),
                    EdgeType.HANDLED_BY.name(),
                    apiNodeKey + "->handled_by->" + methodNodeKey,
                    SourceType.CODE_AST.name(),
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );

            // Controller -CONTAINS-> Method
            createEdge(projectId, versionId,
                    controllerNode.getId(), methodNode.getId(),
                    EdgeType.CONTAINS.name(),
                    controllerNodeKey + "->contains->" + methodNodeKey,
                    SourceType.CODE_AST.name(),
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );

            // 处理权限
            if (api.getPermissions() != null && !api.getPermissions().isEmpty()) {
                for (String perm : api.getPermissions()) {
                    GraphNode permNode = findOrCreateNode(
                            projectId, versionId,
                            NodeType.Permission.name(),
                            perm,
                            perm,
                            perm,
                            null,
                            SourceType.CODE_AST.name(),
                            api.getSourcePath(),
                            api.getStartLine(),
                            api.getEndLine(),
                            BigDecimal.ONE,
                            NodeStatus.CONFIRMED
                    );
                    createEdge(projectId, versionId,
                            apiNode.getId(), permNode.getId(),
                            EdgeType.REQUIRES_PERMISSION.name(),
                            apiNodeKey + "->requires_permission->" + perm,
                            SourceType.CODE_AST.name(),
                            BigDecimal.ONE,
                            NodeStatus.CONFIRMED
                    );
                    nodes.add(permNode);
                }
            }
        }

        return nodes;
    }

    /**
     * 构建Mapper和SQL图谱
     */
    public void buildMapperSqlGraph(String projectId, String versionId, MapperSqlFact mapperFact) {
        // 创建Mapper节点
        String mapperKey = mapperFact.getNamespace();
        String mapperName = (mapperFact.getMapperInterface() != null && !mapperFact.getMapperInterface().isBlank())
                ? mapperFact.getMapperInterface()
                : (mapperKey != null && !mapperKey.isBlank() ? mapperKey : "未命名Mapper");
        String mapperDesc = mapperFact.getNamespace() != null
                ? "MyBatis Mapper: " + mapperFact.getNamespace() : null;
        GraphNode mapperNode = findOrCreateNode(
                projectId, versionId,
                NodeType.Mapper.name(),
                mapperKey,
                mapperName,
                mapperName,
                mapperDesc,
                SourceType.MYBATIS_XML.name(),
                mapperFact.getSourcePath(),
                null,
                null,
                BigDecimal.ONE,
                NodeStatus.CONFIRMED
        );

        // 为每个SQL语句创建节点和关系
        for (MyBatisXmlExtractor.SqlStatement stmt : mapperFact.getStatements()) {
            String sqlKey = mapperKey + "." + stmt.getId();
            GraphNode sqlNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.SqlStatement.name(),
                    sqlKey,
                    stmt.getId(),
                    stmt.getType().toUpperCase() + " " + stmt.getId(),
                    null,
                    SourceType.MYBATIS_XML.name(),
                    mapperFact.getSourcePath(),
                    stmt.getStartLine(),
                    stmt.getEndLine(),
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );

            // Mapper -CONTAINS-> SqlStatement
            createEdge(projectId, versionId,
                    mapperNode.getId(), sqlNode.getId(),
                    EdgeType.CONTAINS.name(),
                    mapperKey + "->contains->" + sqlKey,
                    SourceType.MYBATIS_XML.name(),
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );

            // 解析SQL表关系 - 优先使用展开include后的SQL
            String sqlToParse = (stmt.getExpandedSql() != null && !stmt.getExpandedSql().isBlank()) 
                    ? stmt.getExpandedSql() : stmt.getSql();
            SqlTableExtractor.SqlTableResult tableResult = new SqlTableExtractor().extractTables(sqlToParse);
            // SQL 性能顾问（LLM）已移出扫描主链路，改由 LlmAgentController 独立入口按需触发，避免逐 SQL 同步调用拖慢扫描。

            // 建立读写关系
            for (String readTable : tableResult.getReadTables()) {
                String tableKey = readTable; // schema.table already
                GraphNode tableNode = findOrCreateTableNode(projectId, versionId, readTable);
                createEdge(projectId, versionId,
                        sqlNode.getId(), tableNode.getId(),
                        EdgeType.READS.name(),
                        sqlKey + "->reads->" + tableKey,
                        SourceType.SQL_PARSE.name(),
                        BigDecimal.valueOf(0.95),
                        NodeStatus.CONFIRMED
                );
            }

            for (String writeTable : tableResult.getWriteTables()) {
                String tableKey = writeTable;
                GraphNode tableNode = findOrCreateTableNode(projectId, versionId, writeTable);
                createEdge(projectId, versionId,
                        sqlNode.getId(), tableNode.getId(),
                        EdgeType.WRITES.name(),
                        sqlKey + "->writes->" + tableKey,
                        SourceType.SQL_PARSE.name(),
                        BigDecimal.valueOf(0.95),
                        NodeStatus.CONFIRMED
                );
            }

            for (String joinTable : tableResult.getJoinTables()) {
                String tableKey = joinTable;
                GraphNode tableNode = findOrCreateTableNode(projectId, versionId, joinTable);
                createEdge(projectId, versionId,
                        sqlNode.getId(), tableNode.getId(),
                        EdgeType.JOINS.name(),
                        sqlKey + "->joins->" + tableKey,
                        SourceType.SQL_PARSE.name(),
                        BigDecimal.valueOf(0.95),
                        NodeStatus.CONFIRMED
                );
            }

            // 字段级血缘：SqlStatement → Column (READS/WRITES)
            // Column nodeKey 格式为 schema.tableName.columnName，SQL 中仅提取字段简单名，
            // 将其关联到本 SQL 涉及的所有表（单表查询最常见，多表 JOIN 按保守策略全关联）。
            Set<String> allTables = new HashSet<>();
            allTables.addAll(tableResult.getReadTables());
            allTables.addAll(tableResult.getWriteTables());
            for (String colName : tableResult.getReadColumns()) {
                for (String tbl : allTables) {
                    GraphNode colNode = findOrCreateColumnNode(projectId, versionId, tbl, colName);
                    createEdge(projectId, versionId,
                            sqlNode.getId(), colNode.getId(),
                            EdgeType.READS.name(),
                            sqlKey + "->reads->" + tbl + "." + colName,
                            SourceType.SQL_PARSE.name(),
                            BigDecimal.valueOf(0.85),
                            NodeStatus.CONFIRMED
                    );
                }
            }
            for (String colName : tableResult.getWriteColumns()) {
                for (String tbl : allTables) {
                    GraphNode colNode = findOrCreateColumnNode(projectId, versionId, tbl, colName);
                    createEdge(projectId, versionId,
                            sqlNode.getId(), colNode.getId(),
                            EdgeType.WRITES.name(),
                            sqlKey + "->writes->" + tbl + "." + colName,
                            SourceType.SQL_PARSE.name(),
                            BigDecimal.valueOf(0.85),
                            NodeStatus.CONFIRMED
                    );
                }
            }

            // Mapper -EXECUTES-> SqlStatement
            createEdge(projectId, versionId,
                    mapperNode.getId(), sqlNode.getId(),
                    EdgeType.EXECUTES.name(),
                    mapperKey + "->executes->" + sqlKey,
                    SourceType.MYBATIS_XML.name(),
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );
        }
    }

    /**
     * 构建数据库表和字段图谱。
     * <p>使用批量 UNWIND MERGE 替代逐条 MERGE，将 1000+ 次 Neo4j 往返压缩为 ~5 次。</p>
     */
    public void buildDatabaseGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.DatabaseMetadataExtractor.TableMetadata> tables) {
        if (tables == null || tables.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        // 构建表名映射，用于启发式推断
        Map<String, String> tableNameToKey = new HashMap<>();
        for (var tableMeta : tables) {
            String tableKey = tableMeta.getTableSchema() + "." + tableMeta.getTableName();
            tableNameToKey.put(tableMeta.getTableName().toLowerCase(), tableKey);
            // 也存储不带 schema 的映射
            tableNameToKey.put(tableMeta.getTableName().toLowerCase(), tableKey);
        }

        // 第一遍：创建所有表节点和字段节点
        Map<String, GraphNode> tableNodes = new HashMap<>();
        for (var tableMeta : tables) {
            String tableKey = tableMeta.getTableSchema() + "." + tableMeta.getTableName();
            String tableId = IdUtil.fastUUID();

            // 表节点
            GraphNode tableNode = buildNode(projectId, versionId, tableId,
                    NodeType.Table.name(), tableKey, tableMeta.getTableName(),
                    tableMeta.getTableName(), tableMeta.getTableComment(),
                    SourceType.DB_METADATA.name(), null, BigDecimal.ONE, NodeStatus.CONFIRMED,
                    "DATABASE_SCAN");
            allNodes.add(tableNode);
            tableNodes.put(tableKey, tableNode);

            // 字段节点 + HAS_COLUMN 边
            for (var colMeta : tableMeta.getColumns()) {
                String colKey = tableKey + "." + colMeta.getColumnName();
                String colId = IdUtil.fastUUID();

                GraphNode colNode = buildNode(projectId, versionId, colId,
                        NodeType.Column.name(), colKey, colMeta.getColumnName(),
                        colMeta.getColumnName(), colMeta.getColumnComment(),
                        SourceType.DB_METADATA.name(), null, BigDecimal.ONE, NodeStatus.CONFIRMED,
                        "DATABASE_SCAN");
                colNode.setProperties(buildColumnProperties(colMeta));
                allNodes.add(colNode);

                // HAS_COLUMN 边
                allEdges.add(buildEdge(projectId, versionId,
                        tableId, colId,
                        EdgeType.HAS_COLUMN.name(),
                        tableKey + "->has_column->" + colKey,
                        SourceType.DB_METADATA.name(), BigDecimal.ONE, NodeStatus.CONFIRMED));
            }
        }

        // 第二遍：创建 REFERENCES 边（真实外键 + 启发式推断）
        int realFkCount = 0, inferredFkCount = 0;
        for (var tableMeta : tables) {
            String tableKey = tableMeta.getTableSchema() + "." + tableMeta.getTableName();
            GraphNode tableNode = tableNodes.get(tableKey);

            for (var colMeta : tableMeta.getColumns()) {
                // 1. 真实外键约束
                if (Boolean.TRUE.equals(colMeta.getForeignKey()) && colMeta.getReferencedTableName() != null) {
                    String refTableKey = tableMeta.getTableSchema() != null
                            ? tableMeta.getTableSchema() + "." + colMeta.getReferencedTableName()
                            : colMeta.getReferencedTableName();
                    GraphNode refTable = tableNodes.get(refTableKey);
                    if (refTable == null) {
                        refTable = findOrCreateTableNode(projectId, versionId, colMeta.getReferencedTableName());
                    }
                    String fkEdgeKey = tableKey + "->references->" + refTableKey + "." + colMeta.getColumnName();
                    createEdge(projectId, versionId,
                            tableNode.getId(), refTable.getId(),
                            EdgeType.REFERENCES.name(),
                            fkEdgeKey,
                            SourceType.DB_METADATA.name(),
                            BigDecimal.valueOf(0.9),
                            NodeStatus.CONFIRMED);
                    realFkCount++;
                }
                // 2. 启发式推断：列名模式 {table}_id, {table}Id, parent_id
                else if (!Boolean.TRUE.equals(colMeta.getForeignKey())) {
                    String inferredTable = inferReferencedTable(colMeta.getColumnName(), tableMeta.getTableName(), tableNameToKey);
                    if (inferredTable != null) {
                        GraphNode refTable = tableNodes.get(inferredTable);
                        if (refTable != null) {
                            String fkEdgeKey = tableKey + "->references->" + inferredTable + "." + colMeta.getColumnName();
                            allEdges.add(buildEdge(projectId, versionId,
                                    tableNode.getId(), refTable.getId(),
                                    EdgeType.REFERENCES.name(),
                                    fkEdgeKey,
                                    SourceType.DB_METADATA.name(),
                                    BigDecimal.valueOf(0.7),  // 较低置信度表示启发式
                                    NodeStatus.PENDING_CONFIRM));
                            inferredFkCount++;
                        }
                    }
                }
            }
        }

        // 批量写入所有节点和边
        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Batch merged DB graph: {} nodes, {} edges (real FK: {}, inferred FK: {}, projectId={}, versionId={})",
                nodeCount, edgeCount, realFkCount, inferredFkCount, projectId, versionId);
    }

    private String buildColumnProperties(ColumnMetadata colMeta) {
        Map<String, Object> properties = new LinkedHashMap<>();
        putIfNotNull(properties, "dataType", colMeta.getDataType());
        putIfNotNull(properties, "typeName", colMeta.getTypeName());
        putIfNotNull(properties, "columnSize", colMeta.getColumnSize());
        putIfNotNull(properties, "nullable", colMeta.getNullable());
        putIfNotNull(properties, "columnDefault", colMeta.getColumnDefault());
        putIfNotNull(properties, "primaryKey", colMeta.getPrimaryKey());
        putIfNotNull(properties, "foreignKey", colMeta.getForeignKey());
        putIfNotNull(properties, "referencedTableName", colMeta.getReferencedTableName());
        putIfNotNull(properties, "referencedColumnName", colMeta.getReferencedColumnName());
        putIfNotNull(properties, "semanticType", colMeta.getSemanticType());
        if (properties.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(properties);
        } catch (Exception e) {
            log.debug("Failed to serialize column properties for {}: {}", colMeta.getColumnName(), e.getMessage());
            return "{}";
        }
    }

    private void putIfNotNull(Map<String, Object> properties, String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    /**
     * 启发式推断：根据列名模式推断引用的表名。
     * 模式：{table}_id, {table}Id, parent_id（自引用）
     */
    private String inferReferencedTable(String columnName, String currentTableName, Map<String, String> tableNameToKey) {
        if (columnName == null) return null;
        String colLower = columnName.toLowerCase();

        // 模式 1: parent_id → 自引用
        if (colLower.equals("parent_id") || colLower.equals("parentid")) {
            return tableNameToKey.get(currentTableName.toLowerCase());
        }

        // 模式 2: {table}_id
        if (colLower.endsWith("_id") && colLower.length() > 3) {
            String inferredName = colLower.substring(0, colLower.length() - 3);
            // 查找匹配的表名（忽略大小写）
            for (var entry : tableNameToKey.entrySet()) {
                if (entry.getKey().equals(inferredName)) {
                    return entry.getValue();
                }
            }
        }

        // 模式 3: {table}Id (camelCase)
        if (colLower.endsWith("id") && colLower.length() > 2 && Character.isLowerCase(colLower.charAt(colLower.length() - 3))) {
            String inferredName = colLower.substring(0, colLower.length() - 2);
            for (var entry : tableNameToKey.entrySet()) {
                if (entry.getKey().equals(inferredName)) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    /**
     * 构建数据库真实约束图谱：外键、索引和唯一约束。
     */
    public void buildDatabaseConstraintGraph(String projectId, String versionId, String schema,
            List<DatabaseConstraintExtractor.ForeignKeyInfo> foreignKeys,
            List<DatabaseConstraintExtractor.IndexInfo> indexes) {
        if (foreignKeys != null) {
            for (DatabaseConstraintExtractor.ForeignKeyInfo fk : foreignKeys) {
                if (fk.getFkTableName() == null || fk.getPkTableName() == null) {
                    continue;
                }
                String fkTableKey = buildQualifiedTableKey(schema, fk.getFkTableName());
                String pkTableKey = buildQualifiedTableKey(schema, fk.getPkTableName());
                GraphNode fkTable = findOrCreateDatabaseTableNode(projectId, versionId, fkTableKey, fk.getFkTableName());
                GraphNode pkTable = findOrCreateDatabaseTableNode(projectId, versionId, pkTableKey, fk.getPkTableName());
                String edgeKey = fkTableKey + "->references->" + pkTableKey + "." + nullToEmpty(fk.getFkColumnName());
                if (fk.getFkName() != null && !fk.getFkName().isBlank()) {
                    edgeKey = edgeKey + "#" + fk.getFkName();
                }
                // 增强语义：外键级联规则写入 properties
                String fkProperties = String.format(
                        "{\"fkName\":\"%s\",\"pkColumn\":\"%s\",\"fkColumn\":\"%s\",\"updateRule\":\"%s\",\"deleteRule\":\"%s\"}",
                        fk.getFkName() != null ? fk.getFkName() : "",
                        fk.getPkColumnName() != null ? fk.getPkColumnName() : "",
                        fk.getFkColumnName() != null ? fk.getFkColumnName() : "",
                        cascadeRuleToString(fk.getUpdateRule()),
                        cascadeRuleToString(fk.getDeleteRule()));
                writer.upsertEdge(GraphEdgeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .fromNodeId(fkTable.getId())
                        .toNodeId(pkTable.getId())
                        .edgeType(EdgeType.REFERENCES.name())
                        .edgeKey(edgeKey)
                        .sourceType(SourceType.DB_METADATA.name())
                        .confidence(BigDecimal.ONE)
                        .status(NodeStatus.CONFIRMED.name())
                        .properties(fkProperties)
                        .build());
            }
        }

        if (indexes != null) {
            for (DatabaseConstraintExtractor.IndexInfo idx : indexes) {
                if (idx.getTableName() == null || idx.getIndexName() == null) {
                    continue;
                }
                String tableKey = buildQualifiedTableKey(schema, idx.getTableName());
                GraphNode tableNode = findOrCreateDatabaseTableNode(projectId, versionId, tableKey, idx.getTableName());
                String indexKey = tableKey + "." + idx.getIndexName();
                GraphNode indexNode = findOrCreateNode(
                        projectId, versionId,
                        NodeType.Index.name(),
                        indexKey,
                        idx.getIndexName(),
                        idx.getIndexName(),
                        idx.isUnique() ? "唯一索引" : "普通索引",
                        SourceType.DB_METADATA.name(),
                        null,
                        null,
                        null,
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED);

                createEdge(projectId, versionId,
                        tableNode.getId(), indexNode.getId(),
                        EdgeType.HAS_INDEX.name(),
                        tableKey + "->has_index->" + indexKey,
                        SourceType.DB_METADATA.name(),
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED);

                if (idx.isUnique() && idx.getColumnNames() != null) {
                    for (String columnName : idx.getColumnNames()) {
                        if (columnName == null || columnName.isBlank()) continue;
                        String columnKey = tableKey + "." + columnName;
                        GraphNode columnNode = findOrCreateNode(
                                projectId, versionId,
                                NodeType.Column.name(),
                                columnKey,
                                columnName,
                                columnName,
                                null,
                                SourceType.DB_METADATA.name(),
                                null,
                                null,
                                null,
                                BigDecimal.ONE,
                                NodeStatus.CONFIRMED);
                        createEdge(projectId, versionId,
                                indexNode.getId(), columnNode.getId(),
                                EdgeType.UNIQUE_ON.name(),
                                indexKey + "->unique_on->" + columnKey,
                                SourceType.DB_METADATA.name(),
                                BigDecimal.ONE,
                                NodeStatus.CONFIRMED);
                    }
                }
            }
        }
    }

    /**
     * 查找或创建节点（委托给 EvidenceGraphWriter）。
     * ⚠️ B-M3：本方法与 FrontendGraphBuilder/BusinessGraphBuilder 中的同名方法均为 thin wrapper，
     * 可进一步收敛为 Builder 直接调用 writer.upsertNode()。
     */
    private GraphNode findOrCreateNode(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status,
            String scanType, String className) {
        return writer.upsertNode(GraphNodeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .nodeType(nodeType)
                .nodeKey(nodeKey)
                .nodeName(nodeName)
                .displayName(displayName)
                .description(description)
                .sourceType(sourceType)
                .sourcePath(sourcePath)
                .startLine(startLine)
                .endLine(endLine)
                .confidence(confidence)
                .status(status != null ? status.name() : null)
                .scanType(scanType)
                .className(className)
                .build());
    }

    /**
     * 查找或创建节点（便捷方法，不带 scanType 和 className）
     */
    private GraphNode findOrCreateNode(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status) {
        return findOrCreateNode(projectId, versionId, nodeType, nodeKey, nodeName,
                displayName, description, sourceType, sourcePath,
                startLine, endLine, confidence, status, null, null);
    }

    /**
     * 查找或创建表节点（便捷方法）
     */
    private GraphNode findOrCreateTableNode(String projectId, String versionId, String tableName) {
        // 统一小写：SQL 提取的表名与 DB metadata 的 Table nodeKey（PostgreSQL 默认小写）对齐，
        // 避免 CALL_JMZX_LOG vs call_jmzx_log 被当作两张不同的表。
        String normalized = tableName != null ? tableName.toLowerCase() : tableName;
        return findOrCreateNode(
                projectId, versionId,
                NodeType.Table.name(),
                normalized,
                normalized,
                normalized,
                null,
                SourceType.SQL_PARSE.name(),
                null,
                null,
                null,
                BigDecimal.valueOf(0.95),
                NodeStatus.CONFIRMED,
                "DATABASE_SCAN",
                null
        );
    }

    /** 查找或创建 Column 节点（字段级血缘），nodeKey = tableName.columnName */
    private GraphNode findOrCreateColumnNode(String projectId, String versionId, String tableName, String columnName) {
        String normalizedTable = tableName != null ? tableName.toLowerCase() : "unknown";
        String normalizedCol = columnName != null ? columnName.toLowerCase() : "unknown";
        String colKey = normalizedTable + "." + normalizedCol;
        return findOrCreateNode(
                projectId, versionId,
                NodeType.Column.name(),
                colKey,
                normalizedCol,
                normalizedCol,
                null,
                SourceType.SQL_PARSE.name(),
                null,
                null,
                null,
                BigDecimal.valueOf(0.85),
                NodeStatus.CONFIRMED,
                null,
                null
        );
    }

    /**
     * SQL 提取字段 ↔ DB 元数据字段交叉对比。
     * DB 不可用时仅依赖 SQL 字段（代码即真相）；DB 可用时标记差异：SQL 有 DB 无→可能已删字段，
     * DB 有 SQL 无→可能未用字段。差异写入 Column 节点的 properties，前端可据此展示字段使用情况。
     *
     * @return 差异统计 [sqlOnly, dbOnly, matched]
     */
    public int[] crossValidateSqlVsDbColumns(String projectId, String versionId) {
        // 收集所有 Column 节点，按 tableName 分组，区分 sourceType
        List<GraphNode> allCols = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Column.name(), null, null, null, 0);
        if (allCols == null || allCols.isEmpty()) {
            log.info("Cross-validate columns: 0 Column nodes found");
            return new int[]{0, 0, 0};
        }

        Map<String, Set<String>> sqlColsByTable = new HashMap<>(); // table -> {col1, col2}
        Map<String, Set<String>> dbColsByTable = new HashMap<>();
        Map<String, GraphNode> colNodeMap = new HashMap<>(); // table.col -> node

        for (GraphNode col : allCols) {
            String key = col.getNodeKey(); // "tableName.columnName"
            if (key == null) continue;
            int dot = key.indexOf('.');
            if (dot < 0) continue;
            String tbl = key.substring(0, dot);
            String colName = key.substring(dot + 1);
            colNodeMap.put(tbl + "." + colName, col);

            if ("DB_METADATA".equals(col.getSourceType())) {
                dbColsByTable.computeIfAbsent(tbl, k -> new HashSet<>()).add(colName);
            } else {
                sqlColsByTable.computeIfAbsent(tbl, k -> new HashSet<>()).add(colName);
            }
        }

        int sqlOnly = 0, dbOnly = 0, matched = 0;
        Set<String> allTables = new HashSet<>();
        allTables.addAll(sqlColsByTable.keySet());
        allTables.addAll(dbColsByTable.keySet());

        for (String tbl : allTables) {
            Set<String> sqlCols = sqlColsByTable.getOrDefault(tbl, Set.of());
            Set<String> dbCols = dbColsByTable.getOrDefault(tbl, Set.of());

            for (String col : sqlCols) {
                String fullKey = tbl + "." + col;
                GraphNode node = colNodeMap.get(fullKey);
                if (node == null) continue;
                if (dbCols.contains(col)) {
                    // 双方都有 → 已校验
                    neo4jGraphDao.setNodeProperty(node.getId(), "verifiedByDb", true);
                    matched++;
                } else {
                    // 仅 SQL 有 → 可能 DB 中已删除或字段名不匹配
                    neo4jGraphDao.setNodeProperty(node.getId(), "sqlOnly", true);
                    sqlOnly++;
                }
            }
            for (String col : dbCols) {
                if (!sqlCols.contains(col)) {
                    String fullKey = tbl + "." + col;
                    GraphNode node = colNodeMap.get(fullKey);
                    if (node != null) {
                        neo4jGraphDao.setNodeProperty(node.getId(), "dbOnly", true);
                        dbOnly++;
                    }
                }
            }
        }

        // DB 元数据不可用时特别说明
        if (dbColsByTable.isEmpty() && !sqlColsByTable.isEmpty()) {
            log.info("Cross-validate columns: {} SQL columns, 0 DB columns — DB metadata unavailable, "
                    + "all columns marked as sqlOnly (unverified). Re-scan with DB connection to enable cross-validation.",
                    sqlColsByTable.values().stream().mapToInt(Set::size).sum());
        } else {
            log.info("Cross-validate columns: {} matched, {} sqlOnly, {} dbOnly (projectId={}, versionId={})",
                    matched, sqlOnly, dbOnly, projectId, versionId);
        }
        return new int[]{sqlOnly, dbOnly, matched};
    }

    /**
     * 从 Java 实体类提取表-字段映射（JPA/MyBatis-Plus 注解 + 命名约定）。
     * DB 不可用时作为第三数据源，与 SQL 提取 + DB 元数据三层交叉对比。
     *
     * <p>支持的注解：
     * <ul><li>JPA: @Table(name), @Column(name), @Id, @GeneratedValue</li>
     * <li>MyBatis-Plus: @TableName, @TableField, @TableId</li></ul>
     * 无注解时按字段名 camelCase→snake_case 约定推断。</p>
     *
     * @param projectId 项目 ID
     * @param versionId 版本 ID
     * @param repoRoot 代码仓库根目录（用于读取源文件）
     * @return 提取的表-字段映射数
     */
    public int extractEntityColumns(String projectId, String versionId, Path repoRoot) {
        // 收集已有 Table 节点（从 SQL 提取 + DB 元数据），按 simpleName + nodeKey 建立索引
        List<GraphNode> tables = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Table.name(), null, null, null, 0);
        Map<String, GraphNode> tableByName = new HashMap<>();
        for (GraphNode t : tables != null ? tables : List.of()) {
            String name = t.getNodeName();
            if (name != null) tableByName.put(name.toLowerCase(), t);
            String key = t.getNodeKey();
            if (key != null) {
                int dot = key.indexOf('.');
                tableByName.put((dot > 0 ? key.substring(dot + 1) : key).toLowerCase(), t);
            }
        }

        int totalCols = 0;
        try {
            List<Path> javaFiles = new ArrayList<>();
            try (var stream = Files.walk(repoRoot)) {
                stream.filter(f -> f.toString().endsWith(".java"))
                        .filter(f -> {
                            String path = f.toString().toLowerCase();
                            return path.contains("/model/") || path.contains("/entity/")
                                    || path.contains("/domain/") || path.contains("/pojo/")
                                    || path.contains("/dto/");
                        })
                        .forEach(javaFiles::add);
            }

            com.github.javaparser.JavaParser parser = new com.github.javaparser.JavaParser(
                    new com.github.javaparser.ParserConfiguration()
                            .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17));

            for (Path javaFile : javaFiles) {
                try {
                    var cu = parser.parse(javaFile).getResult().orElse(null);
                    if (cu == null) continue;
                    for (var type : cu.getTypes()) {
                        if (!type.isClassOrInterfaceDeclaration() || type.asClassOrInterfaceDeclaration().isInterface())
                            continue;
                        var clazz = type.asClassOrInterfaceDeclaration();
                        String tableName = extractTableName(clazz);
                        if (tableName == null) continue;

                        GraphNode tableNode = tableByName.get(tableName.toLowerCase());
                        if (tableNode == null) {
                            // 实体类引用的表在 SQL 和 DB 中都未出现 → 创建轻量 Table 节点
                            tableNode = findOrCreateNode(projectId, versionId,
                                    NodeType.Table.name(), tableName, tableName, tableName, null,
                                    "CODE_ENTITY", repoRoot.relativize(javaFile).toString(),
                                    null, null, BigDecimal.valueOf(0.8), NodeStatus.CONFIRMED,
                                    null, null);
                            tableByName.put(tableName.toLowerCase(), tableNode);
                        }

                        for (var field : clazz.getFields()) {
                            for (var var : field.getVariables()) {
                                String colName = extractColumnName(field, var);
                                if (colName == null) continue;
                                String colKey = tableName.toLowerCase() + "." + colName;
                                // 创建或查找 Column 节点
                                GraphNode colNode = findOrCreateNode(projectId, versionId,
                                        NodeType.Column.name(), colKey, colName, colName, null,
                                        "CODE_ENTITY", repoRoot.relativize(javaFile).toString(),
                                        field.getBegin().map(p -> p.line).orElse(null), null,
                                        BigDecimal.valueOf(0.85), NodeStatus.CONFIRMED,
                                        null, null);
                                // Table HAS_COLUMN Column
                                createEdge(projectId, versionId,
                                        tableNode.getId(), colNode.getId(),
                                        EdgeType.HAS_COLUMN.name(),
                                        tableName + "->has_column->" + colName,
                                        "CODE_ENTITY",
                                        BigDecimal.valueOf(0.85),
                                        NodeStatus.CONFIRMED);
                                totalCols++;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Entity extract skipped {}: {}", javaFile.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Entity column extraction failed: {}", e.getMessage());
        }

        log.info("Entity column extraction: {} columns from entity classes (projectId={})", totalCols, projectId);
        return totalCols;
    }

    /**
     * 从 MyBatis XML <resultMap> 中提取字段映射。
     * <result column="user_name" property="userName"/> → Column "user_name"
     */
    public int extractResultMapColumns(String projectId, String versionId, Path repoRoot) {
        // 收集 Table 节点
        List<GraphNode> tables = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Table.name(), null, null, null, 0);
        Map<String, GraphNode> tableByName = new HashMap<>();
        for (GraphNode t : tables != null ? tables : List.of()) {
            String n = t.getNodeName();
            if (n != null) tableByName.put(n.toLowerCase(), t);
            String k = t.getNodeKey();
            if (k != null) {
                int dot = k.indexOf('.');
                tableByName.put((dot > 0 ? k.substring(dot + 1) : k).toLowerCase(), t);
            }
        }

        int totalCols = 0;
        java.util.regex.Pattern resultMapPat = java.util.regex.Pattern.compile(
                "<resultMap\\s[^>]*id\\s*=\\s*\"([^\"]+)\"[^>]*>",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern resultPat = java.util.regex.Pattern.compile(
                "<(?:id|result)\\s[^>]*column\\s*=\\s*\"([^\"]+)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        try {
            List<Path> xmlFiles = new ArrayList<>();
            try (var stream = Files.walk(repoRoot)) {
                stream.filter(f -> f.toString().endsWith(".xml"))
                        .filter(f -> f.toString().toLowerCase().contains("mapper"))
                        .forEach(xmlFiles::add);
            }

            for (Path xmlFile : xmlFiles) {
                try {
                    String content = readFileSafely(xmlFile);
                    if (content == null) continue;
                    // 找到每个 <resultMap>，提取其中的 column 属性
                    var rmMatcher = resultMapPat.matcher(content);
                    while (rmMatcher.find()) {
                        String rmId = rmMatcher.group(1);
                        // 提取 resultMap 结束标签前的内容
                        int start = rmMatcher.start();
                        int end = content.indexOf("</resultMap>", start);
                        if (end < 0) end = content.length();
                        String rmBody = content.substring(start, end);

                        var colMatcher = resultPat.matcher(rmBody);
                        while (colMatcher.find()) {
                            String colName = colMatcher.group(1).toLowerCase();
                            // 尝试推断表名（从 resultMap id 或 namespace）
                            String tableName = inferTableFromResultMap(rmId, content, tableByName);
                            if (tableName == null) continue;

                            GraphNode tableNode = tableByName.get(tableName.toLowerCase());
                            if (tableNode == null) {
                                tableNode = findOrCreateNode(projectId, versionId,
                                        NodeType.Table.name(), tableName, tableName, tableName, null,
                                        "MYBATIS_XML", repoRoot.relativize(xmlFile).toString(),
                                        null, null, BigDecimal.valueOf(0.8), NodeStatus.CONFIRMED,
                                        null, null);
                                tableByName.put(tableName.toLowerCase(), tableNode);
                            }

                            String colKey = tableName.toLowerCase() + "." + colName;
                            findOrCreateNode(projectId, versionId,
                                    NodeType.Column.name(), colKey, colName, colName, null,
                                    "MYBATIS_XML", repoRoot.relativize(xmlFile).toString(),
                                    null, null, BigDecimal.valueOf(0.9), NodeStatus.CONFIRMED,
                                    null, null);
                            totalCols++;
                        }
                    }
                } catch (Exception e) {
                    log.debug("ResultMap extract skipped {}: {}", xmlFile.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("ResultMap extraction failed: {}", e.getMessage());
        }

        log.info("ResultMap extraction: {} columns (projectId={})", totalCols, projectId);
        return totalCols;
    }

    /** 从 JDBC RowMapper / ResultSet 调用中提取字段 */
    public int extractJdbcColumns(String projectId, String versionId, Path repoRoot) {
        List<GraphNode> tables = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Table.name(), null, null, null, 0);
        Map<String, GraphNode> tableByName = new HashMap<>();
        for (GraphNode t : tables != null ? tables : List.of()) {
            String n = t.getNodeName();
            if (n != null) tableByName.put(n.toLowerCase(), t);
            String k = t.getNodeKey();
            if (k != null) {
                int dot = k.indexOf('.');
                tableByName.put((dot > 0 ? k.substring(dot + 1) : k).toLowerCase(), t);
            }
        }

        int totalCols = 0;
        // 匹配 rs.getString("col"), rs.getInt("col"), rs.getObject("col") 等
        java.util.regex.Pattern jdbcPat = java.util.regex.Pattern.compile(
                "\\brs\\.get(?:String|Int|Long|Double|Float|Boolean|Date|Time|Timestamp|Object|BigDecimal|Bytes|Short|Byte)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        try {
            List<Path> javaFiles = new ArrayList<>();
            try (var stream = Files.walk(repoRoot)) {
                stream.filter(f -> f.toString().endsWith(".java"))
                        .filter(f -> {
                            String path = f.toString().toLowerCase();
                            return path.contains("mapper") || path.contains("dao")
                                    || path.contains("repository") || path.contains("service");
                        })
                        .forEach(javaFiles::add);
            }

            for (Path f : javaFiles) {
                try {
                    String content = readFileSafely(f);
                    var m = jdbcPat.matcher(content);
                    while (m.find()) {
                        String colName = m.group(1).toLowerCase();
                        // 无法从 JDBC 调用直接推断表名 → 标记为 unknown 表
                        String tableName = "unknown";
                        String colKey = tableName + "." + colName;
                        findOrCreateNode(projectId, versionId,
                                NodeType.Column.name(), colKey, colName, colName, null,
                                "JDBC_ROW_MAPPER", repoRoot.relativize(f).toString(),
                                null, null, BigDecimal.valueOf(0.7), NodeStatus.PENDING_CONFIRM,
                                null, null);
                        totalCols++;
                    }
                } catch (Exception e) {
                    log.debug("JDBC extract skipped {}: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("JDBC extraction failed: {}", e.getMessage());
        }

        log.info("JDBC extraction: {} column references (projectId={})", totalCols, projectId);
        return totalCols;
    }

    // ─── P1-P4: 节点扫描增强 ───

    /**
     * P1: 从 HTML 文件提取 jQuery AJAX 调用 → Button→ApiEndpoint CALLS 边。
     * 匹配 $.ajax / $.post / $.get / $.getJSON / fetch() 中的 URL，
     * 与已有 ApiEndpoint 节点按路径对齐，创建 Button 节点并连 CALLS 边。
     */
    public int extractHtmlAjaxButtons(String projectId, String versionId, Path repoRoot) {
        List<GraphNode> apis = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.ApiEndpoint.name(), null, null, null, 0);
        if (apis == null || apis.isEmpty()) return 0;
        // API path 索引：归一化路径 → ApiEndpoint 节点
        Map<String, GraphNode> apiByPath = new HashMap<>();
        for (GraphNode api : apis) {
            String name = api.getNodeName(); // "GET nxAccount/getBalance"
            if (name == null) continue;
            int sp = name.indexOf(' ');
            String path = sp > 0 ? name.substring(sp + 1).trim() : name.trim();
            apiByPath.put(normalizeApiPath(path), api);
            apiByPath.put(normalizeApiPath(path.replaceFirst("^/", "")), api);
        }

        // 匹配 $.ajax/$.post/$.get/$.getJSON/fetch 的 URL
        java.util.regex.Pattern ajaxPat = java.util.regex.Pattern.compile(
                "\\$(?:\\.ajax|\\.post|\\.get|\\.getJSON)\\s*\\(\\s*\\{[^}]*url\\s*:\\s*['\"](/[^'\"]+)['\"]",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern simpleAjaxPat = java.util.regex.Pattern.compile(
                "\\$(?:\\.post|\\.get|\\.getJSON)\\s*\\(\\s*['\"](/[^'\"]+)['\"]",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern fetchPat = java.util.regex.Pattern.compile(
                "fetch\\s*\\(\\s*['\"](/[^'\"]+)['\"]",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern onClickPat = java.util.regex.Pattern.compile(
                "onclick\\s*=\\s*\"([^\"]+)\"");

        int buttons = 0;
        try {
            List<Path> htmlFiles = new ArrayList<>();
            try (var stream = Files.walk(repoRoot)) {
                stream.filter(f -> {
                    String n = f.getFileName().toString().toLowerCase();
                    return n.endsWith(".html") || n.endsWith(".htm");
                }).forEach(htmlFiles::add);
            }

            for (Path htmlFile : htmlFiles) {
                try {
                    String content = readFileSafely(htmlFile);
                    String relPath = repoRoot.relativize(htmlFile).toString();
                    java.util.regex.Matcher m;
                    boolean found = false;

                    // $.ajax({url: "/path"})
                    m = ajaxPat.matcher(content);
                    while (m.find()) {
                        createButtonToApi(projectId, versionId, m.group(1), apiByPath,
                                relPath, htmlFile.getFileName().toString());
                        buttons++; found = true;
                    }
                    // $.post("/path", ...)
                    m = simpleAjaxPat.matcher(content);
                    while (m.find()) {
                        createButtonToApi(projectId, versionId, m.group(1), apiByPath,
                                relPath, htmlFile.getFileName().toString());
                        buttons++; found = true;
                    }
                    // fetch("/path")
                    m = fetchPat.matcher(content);
                    while (m.find()) {
                        createButtonToApi(projectId, versionId, m.group(1), apiByPath,
                                relPath, htmlFile.getFileName().toString());
                        buttons++; found = true;
                    }
                    // onclick handlers
                    m = onClickPat.matcher(content);
                    while (m.find()) {
                        String handler = m.group(1);
                        String btnName = handler.replaceAll("[^a-zA-Z0-9_]", "_");
                        if (btnName.length() > 40) btnName = btnName.substring(0, 40);
                        if (!found) {
                            // Only create onclick buttons if no AJAX/fetch was found
                            GraphNode btnNode = findOrCreateNode(projectId, versionId,
                                    NodeType.Button.name(),
                                    "html-btn:" + relPath + "#" + btnName,
                                    btnName, btnName,
                                    "onclick=\"" + handler.substring(0, Math.min(handler.length(), 60)) + "\"",
                                    "FRONTEND_AST", relPath,
                                    null, null, BigDecimal.valueOf(0.7), NodeStatus.PENDING_CONFIRM,
                                    null, null);
                        }
                    }
                } catch (Exception e) {
                    log.debug("AJAX extract skipped {}: {}", htmlFile.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("AJAX extraction failed: {}", e.getMessage());
        }
        log.info("AJAX button extraction: {} buttons (projectId={})", buttons, projectId);
        return buttons;
    }

    private void createButtonToApi(String projectId, String versionId, String url,
                                    Map<String, GraphNode> apiByPath, String sourcePath, String pageName) {
        String normalized = normalizeApiPath(url);
        GraphNode apiNode = apiByPath.get(normalized);
        if (apiNode == null) {
            // 尝试带前缀匹配
            apiNode = apiByPath.get("/" + normalized);
        }
        if (apiNode == null) return;
        String btnName = pageName.replace(".html", "") + "_" + url.replace('/', '_');
        GraphNode btnNode = findOrCreateNode(projectId, versionId,
                NodeType.Button.name(),
                "html-btn:" + sourcePath + "#" + url,
                btnName, btnName,
                "AJAX " + url,
                "FRONTEND_AST", sourcePath,
                null, null, BigDecimal.valueOf(0.8), NodeStatus.PENDING_CONFIRM,
                null, null);
        createEdge(projectId, versionId, btnNode.getId(), apiNode.getId(),
                EdgeType.CALLS.name(),
                btnNode.getNodeKey() + "->calls->" + apiNode.getNodeKey(),
                "FRONTEND_AST", BigDecimal.valueOf(0.8), NodeStatus.PENDING_CONFIRM);
    }

    private static String normalizeApiPath(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    /**
     * P2: @Value ConfigItem 全量扫描。
     * 从 Java 源码中提取所有 @Value("${...}") 注解，创建 ConfigItem 节点。
     */
    public int extractValueConfigItems(String projectId, String versionId, Path repoRoot) {
        java.util.regex.Pattern valuePat = java.util.regex.Pattern.compile(
                "@Value\\s*\\(\\s*\"\\$\\{([^}:]+)(?::[^\"]*)?}\"\\s*\\)");
        int items = 0;
        try {
            List<Path> javaFiles = new ArrayList<>();
            try (var stream = Files.walk(repoRoot)) {
                stream.filter(f -> f.toString().endsWith(".java")).forEach(javaFiles::add);
            }
            for (Path f : javaFiles) {
                try {
                    String content = readFileSafely(f);
                    var m = valuePat.matcher(content);
                    while (m.find()) {
                        String key = m.group(1).trim();
                        String configKey = "config:" + key;
                        findOrCreateNode(projectId, versionId,
                                NodeType.ConfigItem.name(), configKey, key, key,
                                "配置项: ${" + key + "}",
                                "CODE_AST", repoRoot.relativize(f).toString(),
                                null, null, BigDecimal.valueOf(0.9), NodeStatus.CONFIRMED,
                                null, null);
                        items++;
                    }
                } catch (Exception e) { /* skip unparseable files */ }
            }
        } catch (IOException e) {
            log.warn("ConfigItem extraction failed: {}", e.getMessage());
        }
        log.info("@Value ConfigItem extraction: {} items (projectId={})", items, projectId);
        return items;
    }

    /**
     * P3: HttpClient/HttpURLConnection → ExternalSystem CALLS_EXTERNAL 边。
     * 检测 Apache HttpClient / HttpURLConnection / RestTemplate 调用，创建 ExternalSystem 节点。
     */
    public int extractHttpClientSystems(String projectId, String versionId, Path repoRoot) {
        // 使用行内匹配避免跨行回溯：先找类名，再在同行找 URL
        java.util.regex.Pattern urlPattern = java.util.regex.Pattern.compile(
                "(?:HttpURLConnection|CloseableHttpClient|HttpClient|RestTemplate|WebClient)"
                        + "[^\"]*\"(https?://[^\"\\s]+)\"",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        int found = 0;
        try {
            List<Path> javaFiles = new ArrayList<>();
            try (var stream = Files.walk(repoRoot)) {
                stream.filter(f -> f.toString().endsWith(".java")).forEach(javaFiles::add);
            }
            for (Path f : javaFiles) {
                try {
                    String content = readFileSafely(f);
                    var m = urlPattern.matcher(content);
                    while (m.find()) {
                        String url = m.group(1);
                        String host = extractHost(url);
                        String sysKey = "external:" + host;
                        GraphNode sysNode = findOrCreateNode(projectId, versionId,
                                NodeType.ExternalSystem.name(), sysKey, host, host,
                                "外部系统: " + url,
                                "CODE_AST", repoRoot.relativize(f).toString(),
                                null, null, BigDecimal.valueOf(0.8), NodeStatus.CONFIRMED,
                                null, null);
                        found++;
                        // 尝试找最近的 Method 节点连 CALLS_EXTERNAL 边（近似：同文件同版本）
                    }
                } catch (Exception e) { /* skip */ }
            }
        } catch (IOException e) {
            log.warn("HttpClient extraction failed: {}", e.getMessage());
        }
        log.info("HttpClient extraction: {} external systems (projectId={})", found, projectId);
        return found;
    }

    private static String extractHost(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            int start = url.indexOf("://");
            if (start > 0) {
                int end = url.indexOf('/', start + 3);
                return end > 0 ? url.substring(start + 3, end) : url.substring(start + 3);
            }
            return url;
        }
    }

    /**
     * P4: HTML 导航结构提取 → Menu 节点。
     * 从传统 HTML 的 sidebar/导航区域提取菜单项，创建 Menu 节点 + CONTAINS→Page 边。
     */
    public int extractHtmlMenus(String projectId, String versionId, Path repoRoot) {
        // 匹配常见 HTML 导航模式：<a href="..."> 在 sidebar/nav 区域内
        java.util.regex.Pattern menuAPat = java.util.regex.Pattern.compile(
                "<a\\s[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>([^<]+)</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern liMenuPat = java.util.regex.Pattern.compile(
                "<li[^>]*>\\s*<a\\s[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>([^<]+)</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        int menus = 0;
        try {
            List<Path> htmlFiles = new ArrayList<>();
            try (var stream = Files.walk(repoRoot)) {
                stream.filter(f -> {
                    String n = f.getFileName().toString().toLowerCase();
                    return n.endsWith(".html") || n.endsWith(".htm");
                }).forEach(htmlFiles::add);
            }

            for (Path htmlFile : htmlFiles) {
                try {
                    String content = readFileSafely(htmlFile);
                    String relPath = repoRoot.relativize(htmlFile).toString();
                    var m = liMenuPat.matcher(content);
                    while (m.find()) {
                        String href = m.group(1);
                        String label = m.group(2).trim();
                        if (label.isEmpty() || href.startsWith("javascript") || href.startsWith("#")) continue;
                        String menuKey = "menu:" + relPath + "#" + label;
                        GraphNode menuNode = findOrCreateNode(projectId, versionId,
                                NodeType.Menu.name(), menuKey, label, label,
                                "导航链接: " + href,
                                "FRONTEND_AST", relPath,
                                null, null, BigDecimal.valueOf(0.8), NodeStatus.PENDING_CONFIRM,
                                null, null);
                        // 尝试连到 Page 节点
                        GraphNode pageNode = findPageByPath(projectId, versionId, href);
                        if (pageNode != null) {
                            createEdge(projectId, versionId, menuNode.getId(), pageNode.getId(),
                                    EdgeType.CONTAINS.name(),
                                    menuKey + "->contains->" + pageNode.getNodeKey(),
                                    "FRONTEND_AST", BigDecimal.valueOf(0.8), NodeStatus.PENDING_CONFIRM);
                        }
                        menus++;
                    }
                } catch (Exception e) { /* skip */ }
            }
        } catch (IOException e) {
            log.warn("Menu extraction failed: {}", e.getMessage());
        }
        log.info("Menu extraction: {} menus (projectId={})", menus, projectId);
        return menus;
    }

    private GraphNode findPageByPath(String projectId, String versionId, String href) {
        List<GraphNode> pages = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Page.name(), null, null, null, 0);
        if (pages == null) return null;
        String normalized = href.replace('\\', '/');
        for (GraphNode p : pages) {
            String route = p.getNodeKey();
            if (route != null && route.contains(normalized)) return p;
        }
        return null;
    }

    /** 单文件最大读取字符数（超过则跳过，防止大文件拖慢提取） */
    private static final int MAX_FILE_CHARS = 200_000;
    /** 单次提取最大文件数（超过则截断，防止大仓库提取耗时过长） */
    private static final int MAX_FILES_PER_EXTRACT = 500;

    private static String readFileSafely(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_CHARS) return null;
            return readFileSafely(file);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 resultMap id 推断表名（约定：XxxResultMap → xxx, BaseResultMap → 看 namespace 中的实体类名） */
    private String inferTableFromResultMap(String rmId, String content,
                                            Map<String, GraphNode> tableByName) {
        // "BaseResultMap" → 从 namespace 推断
        // "GoldInResultMap" → "gold_in"
        if (rmId.endsWith("ResultMap")) {
            String base = rmId.substring(0, rmId.length() - "ResultMap".length());
            return camelToSnake(base);
        }
        // 直接匹配已知表名
        for (String tn : tableByName.keySet()) {
            if (rmId.toLowerCase().contains(tn)) return tn;
        }
        return camelToSnake(rmId);
    }

    /** 从类注解提取表名。支持 @Table/@TableName/@Entity 的命名参数和单值两种形式。 */
    private String extractTableName(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration clazz) {
        for (var ann : clazz.getAnnotations()) {
            String aName = ann.getNameAsString();
            boolean isTableAnno = "Table".equals(aName) || "TableName".equals(aName) || "Entity".equals(aName);
            if (!isTableAnno) continue;

            // @TableName("table_name") — 单值注解
            if (ann.isSingleMemberAnnotationExpr()) {
                String v = ann.asSingleMemberAnnotationExpr().getMemberValue().toString()
                        .replace("\"", "").trim();
                if (!v.isEmpty()) return v;
            }
            // @Table(name = "table_name") / @TableName(value = "table_name") — 命名参数
            if (ann.isNormalAnnotationExpr()) {
                for (var pair : ann.asNormalAnnotationExpr().getPairs()) {
                    if ("name".equals(pair.getNameAsString()) || "value".equals(pair.getNameAsString())) {
                        String v = pair.getValue().toString().replace("\"", "").trim();
                        if (!v.isEmpty()) return v;
                    }
                }
            }
            // @Entity 无参数 → 类名约定
            if ("Entity".equals(aName)) {
                return camelToSnake(clazz.getNameAsString());
            }
        }
        // 无注解但在 model/entity 包 → 类名约定
        return camelToSnake(clazz.getNameAsString());
    }

    /** 从字段注解提取列名。支持 @Column/@TableField 的命名参数和单值两种形式。 */
    private String extractColumnName(com.github.javaparser.ast.body.FieldDeclaration field,
                                      com.github.javaparser.ast.body.VariableDeclarator var) {
        for (var ann : field.getAnnotations()) {
            String aName = ann.getNameAsString();
            // @TableField("col_name") — 单值注解
            if ("TableField".equals(aName) && ann.isSingleMemberAnnotationExpr()) {
                String v = ann.asSingleMemberAnnotationExpr().getMemberValue().toString()
                        .replace("\"", "").trim();
                if (!v.isEmpty()) return v.toLowerCase();
            }
            // @Column(name = "col_name") / @TableField(value = "col_name") — 命名参数
            if (("Column".equals(aName) || "TableField".equals(aName)) && ann.isNormalAnnotationExpr()) {
                for (var pair : ann.asNormalAnnotationExpr().getPairs()) {
                    if ("name".equals(pair.getNameAsString()) || "value".equals(pair.getNameAsString())) {
                        String v = pair.getValue().toString().replace("\"", "").trim();
                        if (!v.isEmpty()) return v.toLowerCase();
                    }
                }
            }
            // @Id / @TableId — 主键，字段名即列名
            if ("Id".equals(aName) || "TableId".equals(aName)) {
                return var.getNameAsString().toLowerCase();
            }
        }
        // 无注解 → 驼峰转下划线
        return camelToSnake(var.getNameAsString());
    }

    /** camelCase → snake_case */
    private static String camelToSnake(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private GraphNode findOrCreateDatabaseTableNode(String projectId, String versionId, String tableKey, String tableName) {
        return findOrCreateNode(
                projectId, versionId,
                NodeType.Table.name(),
                tableKey,
                tableName,
                tableName,
                null,
                SourceType.DB_METADATA.name(),
                null,
                null,
                null,
                BigDecimal.ONE,
                NodeStatus.CONFIRMED,
                "DATABASE_SCAN",
                null
        );
    }

    private String buildQualifiedTableKey(String schema, String tableName) {
        if (schema == null || schema.isBlank()) {
            return tableName;
        }
        return schema + "." + tableName;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 创建边（委托给 EvidenceGraphWriter，自动去重+证据继承）。
     */
    private GraphEdge createEdge(String projectId, String versionId,
            String fromNodeId, String toNodeId,
            String edgeType, String edgeKey,
            String sourceType, BigDecimal confidence,
            NodeStatus status) {
        return writer.upsertEdge(GraphEdgeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .fromNodeId(fromNodeId)
                .toNodeId(toNodeId)
                .edgeType(edgeType)
                .edgeKey(edgeKey)
                .sourceType(sourceType)
                .confidence(confidence)
                .status(status != null ? status.name() : null)
                .build());
    }

    /**
     * API路径归一化
     */
    public static String normalizeApiKey(String httpMethod, String path) {
        return httpMethod.toUpperCase() + " " + normalizePath(path);
    }

    /**
     * 将 JDBC DatabaseMetaData 的级联规则常量转为可读字符串。
     * @see java.sql.DatabaseMetaData#importedKeyCascade
     */
    private static String cascadeRuleToString(short rule) {
        return switch (rule) {
            case java.sql.DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case java.sql.DatabaseMetaData.importedKeySetNull -> "SET_NULL";
            case java.sql.DatabaseMetaData.importedKeySetDefault -> "SET_DEFAULT";
            case java.sql.DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case java.sql.DatabaseMetaData.importedKeyNoAction -> "NO_ACTION";
            default -> "UNKNOWN";
        };
    }

    /**
     * 归一化路径，将不同参数风格统一为 {param} 形式
     */
    public static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim();
        int queryIndex = normalized.indexOf('?');
        int hashIndex = normalized.indexOf('#');
        int cutIndex = -1;
        if (queryIndex >= 0 && hashIndex >= 0) {
            cutIndex = Math.min(queryIndex, hashIndex);
        } else if (queryIndex >= 0) {
            cutIndex = queryIndex;
        } else if (hashIndex >= 0) {
            cutIndex = hashIndex;
        }
        if (cutIndex >= 0) {
            normalized = normalized.substring(0, cutIndex);
        }
        normalized = normalized.replaceAll("/:[^/]+", "/{id}");
        normalized = normalized.replaceAll("/\\$\\{[^}]+\\}", "/{id}");
        normalized = normalized.replaceAll("\\{[^}/]+\\}", "{id}");
        return normalized;
    }

    /**
     * 构建 Java 类/方法结构图谱。
     */
    @Transactional
    public List<GraphNode> buildJavaStructureGraph(String projectId, String versionId,
            List<JavaStructureExtractor.JavaClassInfo> classes) {
        List<GraphNode> nodes = new ArrayList<>();
        if (classes == null || classes.isEmpty()) {
            return nodes;
        }

        for (JavaStructureExtractor.JavaClassInfo classInfo : classes) {
            if (classInfo.getQualifiedName() == null || classInfo.getQualifiedName().isBlank()) {
                continue;
            }
            NodeType classNodeType = inferNodeType(classInfo.getQualifiedName());
            GraphNode classNode = findOrCreateNode(
                    projectId, versionId,
                    classNodeType.name(),
                    classInfo.getQualifiedName(),
                    classInfo.getClassName(),
                    classInfo.getClassName(),
                    null,
                    SourceType.CODE_AST.name(),
                    classInfo.getSourcePath(),
                    classInfo.getStartLine(),
                    classInfo.getEndLine(),
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED,
                    "CODE_SCAN",
                    classInfo.getQualifiedName()
            );
            nodes.add(classNode);

            if (classInfo.getMethods() == null || classInfo.getMethods().isEmpty()) {
                continue;
            }
            for (JavaStructureExtractor.JavaMethodInfo methodInfo : classInfo.getMethods()) {
                if (methodInfo.getQualifiedName() == null || methodInfo.getQualifiedName().isBlank()) {
                    continue;
                }
                GraphNode methodNode = findOrCreateNode(
                        projectId, versionId,
                        NodeType.Method.name(),
                        methodInfo.getQualifiedName(),
                        methodInfo.getMethodName(),
                        methodInfo.getMethodName(),
                        null,
                        SourceType.CODE_AST.name(),
                        classInfo.getSourcePath(),
                        methodInfo.getStartLine(),
                        methodInfo.getEndLine(),
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED,
                        "CODE_SCAN",
                        classInfo.getQualifiedName()
                );
                nodes.add(methodNode);
                createEdge(projectId, versionId,
                        classNode.getId(), methodNode.getId(),
                        EdgeType.CONTAINS.name(),
                        classInfo.getQualifiedName() + "->contains->" + methodInfo.getQualifiedName(),
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED
                );
            }
        }
        return nodes;
    }

    /**
     * 构建Service调用关系图谱
     * 根据抽取的调用关系创建 CALLS 边连接调用方和被调用方节点
     */
    @Transactional
    public void buildServiceCallGraph(String projectId, String versionId, List<ServiceCallExtractor.CallRelation> calls) {
        for (ServiceCallExtractor.CallRelation call : calls) {
            // 查找或创建调用方节点
            String callerClassKey = call.getCallerClass();
            if (callerClassKey == null || callerClassKey.isEmpty()) {
                continue;
            }
            NodeType callerNodeType = inferNodeType(callerClassKey);
            GraphNode callerNode = findOrCreateNodeByClass(
                    projectId, versionId, callerNodeType, callerClassKey, call.getSourcePath());

            // 查找被调用方节点（targetClass 可能为 null，此时跳过该调用关系）
            String targetClassKey = call.getTargetClass();
            if (targetClassKey == null || targetClassKey.isEmpty()) {
                log.debug("Skipping call relation with null targetClass: caller={}, calledMethod={}",
                        callerClassKey, call.getCalledMethod());
                continue;
            }
            NodeType targetNodeType = inferNodeType(targetClassKey);
            // find-only：targetClass 是简单名，类节点 nodeKey 是 FQN，精确查找通常 miss。
            // 不再用 findOrCreateNodeByClass（会创建重复的简单名节点污染图谱），miss 时 targetNode=null，
            // 下面的 callerNode!=null && targetNode!=null 门会跳过本调用，交给 JavaMemberCallResolver 二次扫描解析。
            GraphNode targetNode = neo4jGraphDao.findNode(
                    projectId, versionId, targetNodeType.name(), targetClassKey).orElse(null);

            // 如果调用方和被调用方都存在，创建 CALLS 边
            if (callerNode != null && targetNode != null) {
                String edgeKey = callerClassKey + "->calls->" + targetClassKey + "." + call.getTargetMethod();
                createEdge(projectId, versionId,
                        callerNode.getId(), targetNode.getId(),
                        EdgeType.CALLS.name(),
                        edgeKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED
                );

                // 如果能找到具体方法，也创建方法级别的调用边
                if (call.getCallerMethod() != null && !call.getCallerMethod().isEmpty()) {
                    // 使用签名对齐 JavaStructureExtractor 生成的 Method key
                    String callerMethodKey;
                    if (call.getCallerMethodSignature() != null && !call.getCallerMethodSignature().isEmpty()) {
                        callerMethodKey = callerClassKey + "." + call.getCallerMethodSignature();
                    } else {
                        callerMethodKey = callerClassKey + "." + call.getCallerMethod();
                    }
                    GraphNode callerMethodNode = findExistingNode(projectId, versionId, NodeType.Method.name(), callerMethodKey);
                    
                    String targetMethodKey;
                    if (call.getCalledMethodSignature() != null && !call.getCalledMethodSignature().isEmpty()) {
                        targetMethodKey = targetClassKey + "." + call.getCalledMethodSignature();
                    } else {
                        targetMethodKey = targetClassKey + "." + call.getTargetMethod();
                    }
                    GraphNode targetMethodNode = findExistingNode(projectId, versionId, NodeType.Method.name(), targetMethodKey);
                    
                    if (callerMethodNode != null && targetMethodNode != null) {
                        String methodEdgeKey = callerMethodKey + "->calls->" + targetMethodKey;
                        createEdge(projectId, versionId,
                                callerMethodNode.getId(), targetMethodNode.getId(),
                                EdgeType.CALLS.name(),
                                methodEdgeKey,
                                SourceType.CODE_AST.name(),
                                BigDecimal.ONE,
                                NodeStatus.CONFIRMED
                        );
                    }
                }
            }
        }
    }

    /**
     * 根据类名推断节点类型
     */
    private NodeType inferNodeType(String className) {
        if (className == null || className.isEmpty()) {
            return NodeType.Service; // 默认作为服务类
        }
        if (className.contains("Controller") || className.contains("controller")) {
            return NodeType.Controller;
        }
        if (className.contains("Service") || className.contains("service")) {
            return NodeType.Service;
        }
        if (className.contains("Mapper") || className.contains("mapper") || className.contains("Dao") || className.contains("dao")) {
            return NodeType.Mapper;
        }
        return NodeType.Service; // 默认作为服务类
    }

    /**
     * 根据类全限定名查找或创建节点
     */
    private GraphNode findOrCreateNodeByClass(String projectId, String versionId,
            NodeType nodeType, String classFullName, String sourcePath) {
        GraphNode existing = neo4jGraphDao.findNode(projectId, versionId, nodeType.name(), classFullName).orElse(null);

        if (existing != null) {
            return existing;
        }

        // 创建新节点
        String simpleName = classFullName.contains(".") ?
                classFullName.substring(classFullName.lastIndexOf('.') + 1) :
                classFullName;

        return findOrCreateNode(
                projectId, versionId,
                nodeType.name(),
                classFullName,
                simpleName,
                simpleName,
                null,
                SourceType.CODE_AST.name(),
                sourcePath,
                null,
                null,
                BigDecimal.ONE,
                NodeStatus.CONFIRMED
        );
    }

    /**
     * 查找已存在的节点
     */
    private GraphNode findExistingNode(String projectId, String versionId, String nodeType, String nodeKey) {
        return neo4jGraphDao.findNode(projectId, versionId, nodeType, nodeKey).orElse(null);
    }

    /**
     * 从 Neo4j 构建数据库 Schema 文本摘要，用于 LLM 分析。
     * 包含表名、注释、列名和列注释。
     */
    public String buildDbSchemaSummary(String projectId, String versionId) {
        StringBuilder sb = new StringBuilder();
        List<GraphNode> tables = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Table.name(),
                null, null, null, 500);
        if (tables.isEmpty()) {
            return null;
        }

        sb.append("数据库 Schema 信息：\n");
        sb.append("共 ").append(tables.size()).append(" 张表\n\n");

        for (GraphNode tableNode : tables) {
            sb.append("- 表: ").append(tableNode.getNodeName());
            if (tableNode.getDescription() != null && !tableNode.getDescription().isBlank()) {
                sb.append(" (").append(tableNode.getDescription()).append(")");
            }
            sb.append("\n");

            // 查询该表的列节点
            List<GraphEdge> columnEdges = neo4jGraphDao.queryEdges(
                    projectId, versionId,
                    EdgeType.HAS_COLUMN.name(),
                    null, tableNode.getId(),
                    null, null, 200);
            for (GraphEdge edge : columnEdges) {
                GraphNode colNode = neo4jGraphDao.findNodeById(edge.getToNodeId()).orElse(null);
                if (colNode != null) {
                    sb.append("  - ").append(colNode.getNodeName());
                    if (colNode.getDescription() != null && !colNode.getDescription().isBlank()) {
                        sb.append(" -- ").append(colNode.getDescription());
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 用 LLM 分析结果丰富数据库图谱节点。
     * 更新 Table 节点的描述信息，创建 BusinessDomain 节点和关系。
     */
    @Transactional
    public void enrichDbGraphWithLLM(String projectId, String versionId,
                                      io.github.legacygraph.dto.DbSchemaAnalysis analysis) {
        if (analysis == null) {
            return;
        }

        // 一次性构建表名→nodeKey 映射
        Map<String, String> tableNameToKey = buildTableNameKeyMap(projectId, versionId);

        // 1. 更新 Table 节点的业务描述和域标签
        if (analysis.getTables() != null) {
            for (var tableInsight : analysis.getTables()) {
                String tableKey = buildTableKey(tableNameToKey, tableInsight.getTableName());
                if (tableKey == null) continue;

                GraphNode tableNode = findExistingNode(projectId, versionId,
                        NodeType.Table.name(), tableKey);
                if (tableNode != null) {
                    boolean updated = false;
                    if (tableInsight.getBusinessLabel() != null
                            && !tableInsight.getBusinessLabel().isBlank()) {
                        tableNode.setDisplayName(
                                tableInsight.getBusinessLabel() + " (" + tableNode.getNodeName() + ")");
                        updated = true;
                    }
                    if (tableInsight.getBusinessDescription() != null
                            && !tableInsight.getBusinessDescription().isBlank()) {
                        String desc = tableInsight.getBusinessDescription();
                        if (tableInsight.getDomain() != null) {
                            desc = "[" + tableInsight.getDomain() + "] " + desc;
                        }
                        tableNode.setDescription(desc);
                        updated = true;
                    }
                    if (updated) {
                        tableNode.setUpdatedAt(LocalDateTime.now());
                        neo4jGraphDao.updateNode(tableNode);
                    }
                }
            }
        }

        // 2. 创建 BusinessDomain 节点
        if (analysis.getDomains() != null) {
            for (var domain : analysis.getDomains()) {
                if (domain.getName() == null || domain.getName().isBlank()) continue;

                GraphNode domainNode = findOrCreateNode(
                        projectId, versionId,
                        NodeType.BusinessDomain.name(),
                        domain.getName(),
                        domain.getName(),
                        domain.getName(),
                        domain.getDescription(),
                        SourceType.DB_METADATA.name(),
                        null, null, null,
                        java.math.BigDecimal.valueOf(0.85),
                        NodeStatus.PENDING_CONFIRM
                );

                // 将属于该域的表关联到 BusinessDomain
                if (domain.getTables() != null) {
                    for (String tableName : domain.getTables()) {
                        String tableKey = buildTableKey(tableNameToKey, tableName);
                        if (tableKey == null) continue;
                        GraphNode tableNode = findExistingNode(projectId, versionId,
                                NodeType.Table.name(), tableKey);
                        if (tableNode != null) {
                            createEdge(projectId, versionId,
                                    domainNode.getId(), tableNode.getId(),
                                    EdgeType.CONTAINS.name(),
                                    domain.getName() + "->contains->" + tableKey,
                                    SourceType.DB_METADATA.name(),
                                    java.math.BigDecimal.valueOf(0.85),
                                    NodeStatus.PENDING_CONFIRM
                            );
                        }
                    }
                }
            }
        }

        // 3. 创建隐式关系
        if (analysis.getImplicitRelations() != null) {
            for (var rel : analysis.getImplicitRelations()) {
                if (rel.getFromTable() == null || rel.getToTable() == null) continue;

                String fromKey = buildTableKey(tableNameToKey, rel.getFromTable());
                String toKey = buildTableKey(tableNameToKey, rel.getToTable());
                if (fromKey == null || toKey == null) continue;

                GraphNode fromNode = findExistingNode(projectId, versionId,
                        NodeType.Table.name(), fromKey);
                GraphNode toNode = findExistingNode(projectId, versionId,
                        NodeType.Table.name(), toKey);
                if (fromNode != null && toNode != null) {
                    createEdge(projectId, versionId,
                            fromNode.getId(), toNode.getId(),
                            EdgeType.REFERENCES.name(),
                            fromKey + "->references->" + toKey,
                            "LLM_SCHEMA_ANALYSIS",
                            java.math.BigDecimal.valueOf(0.7),
                            NodeStatus.PENDING_CONFIRM
                    );
                }
            }
        }

        log.info("LLM DB schema enrichment completed: {} tables, {} domains, {} implicit relations",
                analysis.getTables() != null ? analysis.getTables().size() : 0,
                analysis.getDomains() != null ? analysis.getDomains().size() : 0,
                analysis.getImplicitRelations() != null ? analysis.getImplicitRelations().size() : 0);
    }

    /**
     * 根据表名查找 tableKey（通过表名模糊匹配 Neo4j 中的 Table 节点）。
     */
    private String buildTableKey(Map<String, String> tableNameToKey, String tableName) {
        if (tableName == null || tableName.isBlank()) return null;
        // 精确匹配
        String key = tableNameToKey.get(tableName);
        if (key != null) return key;
        // 表名已包含 schema 前缀，直接使用
        if (tableName.contains(".")) return tableName;
        // 模糊匹配（去掉前缀如 t_ 等）
        for (Map.Entry<String, String> entry : tableNameToKey.entrySet()) {
            if (entry.getKey().endsWith("." + tableName) || entry.getKey().endsWith(tableName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 构建表名 → nodeKey 映射（nodeName 为不带 schema 前缀的表名）。
     */
    private Map<String, String> buildTableNameKeyMap(String projectId, String versionId) {
        Map<String, String> map = new java.util.HashMap<>();
        List<GraphNode> tables = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Table.name(),
                null, null, null, 500);
        for (GraphNode node : tables) {
            String nodeName = node.getNodeName();
            if (nodeName != null && !nodeName.isBlank()) {
                map.put(nodeName, node.getNodeKey());
            }
        }
        return map;
    }

    // ==================== 批量写入辅助方法 ====================

    /** 构建节点 POJO（不写 Neo4j），供批量 mergeNodesBatch 使用 */
    private GraphNode buildNode(String projectId, String versionId, String nodeId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            BigDecimal confidence, NodeStatus status, String scanType) {
        GraphNode node = new GraphNode();
        node.setId(nodeId);
        node.setProjectId(projectId);
        node.setVersionId(versionId);
        node.setNodeType(nodeType);
        node.setNodeKey(nodeKey);
        node.setNodeName(nodeName);
        node.setDisplayName(displayName);
        node.setDescription(description);
        node.setSourceType(sourceType);
        node.setSourcePath(sourcePath);
        node.setConfidence(confidence);
        node.setStatus(status != null ? status.name() : null);
        node.setScanType(scanType != null ? scanType : "");
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        return node;
    }

    /** 构建边 POJO（不写 Neo4j），供批量 mergeEdgesBatch 使用 */
    private GraphEdge buildEdge(String projectId, String versionId,
            String fromNodeId, String toNodeId,
            String edgeType, String edgeKey,
            String sourceType, BigDecimal confidence, NodeStatus status) {
        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setEdgeType(edgeType);
        edge.setEdgeKey(edgeKey);
        edge.setSourceType(sourceType);
        edge.setConfidence(confidence);
        edge.setStatus(status != null ? status.name() : null);
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        return edge;
    }

    private String buildMethodKey(String className, String methodName, String methodSignature) {
        if (methodSignature != null && !methodSignature.isBlank()) {
            return className + "." + methodSignature;
        }
        return className + "." + methodName;
    }

    private String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String inferTestedClassKey(String testClassName) {
        if (testClassName == null || testClassName.isBlank()) {
            return null;
        }
        if (testClassName.endsWith("Tests")) {
            return testClassName.substring(0, testClassName.length() - "Tests".length());
        }
        if (testClassName.endsWith("Test")) {
            return testClassName.substring(0, testClassName.length() - "Test".length());
        }
        return null;
    }

    // ==================== 配置项图谱构建 ====================

    /**
     * 构建配置项图谱：ConfigItem 节点 + Class USES ConfigItem 边
     */
    public void buildConfigItemGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.ConfigItemExtractor.ConfigItemFact> configItems) {
        if (configItems == null || configItems.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var item : configItems) {
            String configKey = "config:" + item.getKey();
            String configId = IdUtil.fastUUID();

            // ConfigItem 节点
            GraphNode configNode = buildNode(projectId, versionId, configId,
                    NodeType.ConfigItem.name(), configKey, item.getKey(),
                    item.getKey(), item.getSourceType(),
                    SourceType.CODE_AST.name(), item.getSourcePath(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(configNode);

            // Class -> ConfigItem USES 边
            if (item.getClassName() != null && !item.getClassName().isBlank()) {
                String classKey = item.getClassName();
                GraphNode classNode = findExistingNode(projectId, versionId,
                        NodeType.Service.name(), classKey);
                if (classNode != null) {
                    allEdges.add(buildEdge(projectId, versionId,
                            classNode.getId(), configId,
                            EdgeType.USES.name(),
                            classKey + "->uses->" + configKey,
                            SourceType.CODE_AST.name(),
                            BigDecimal.ONE, NodeStatus.CONFIRMED));
                }
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built config graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 定时任务图谱构建 ====================

    /**
     * 构建定时任务图谱：ScheduledJob 节点 + HANDLED_BY Method 边
     */
    public void buildScheduledJobGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.ScheduledJobExtractor.ScheduledJobFact> jobs) {
        if (jobs == null || jobs.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var job : jobs) {
            String jobKey = "job:" + job.getClassName() + "." + job.getMethodName();
            String jobId = IdUtil.fastUUID();

            // ScheduledJob 节点
            String description = job.getCronExpression() != null
                    ? "Cron: " + job.getCronExpression()
                    : "Fixed: " + job.getFixedDelay() + "ms";
            GraphNode jobNode = buildNode(projectId, versionId, jobId,
                    NodeType.ScheduledJob.name(), jobKey, job.getMethodName(),
                    job.getClassName() + "." + job.getMethodName(), description,
                    SourceType.CODE_AST.name(), job.getSourcePath(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            jobNode.setStartLine(job.getStartLine());
            jobNode.setEndLine(job.getEndLine());
            allNodes.add(jobNode);

            if (job.getClassName() != null && job.getMethodName() != null) {
                String methodKey = buildMethodKey(job.getClassName(), job.getMethodName(), job.getMethodSignature());
                GraphNode methodNode = buildNode(projectId, versionId, IdUtil.fastUUID(),
                        NodeType.Method.name(), methodKey, job.getMethodName(),
                        job.getMethodName(), "定时任务处理方法",
                        SourceType.CODE_AST.name(), job.getSourcePath(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED,
                        "CODE_SCAN");
                methodNode.setStartLine(job.getStartLine());
                methodNode.setEndLine(job.getEndLine());
                methodNode.setClassName(job.getClassName());
                allNodes.add(methodNode);

                allEdges.add(buildEdge(projectId, versionId,
                        jobId, methodNode.getId(),
                        EdgeType.HANDLED_BY.name(),
                        jobKey + "->handled_by->" + methodKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED));
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built scheduled job graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 消息队列图谱构建 ====================

    /**
     * 构建消息队列图谱：MQConsumer / MQTopic 节点 + 方法级处理与触发边
     */
    public void buildMQGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.MQExtractor.MQConsumerFact> consumers) {
        if (consumers == null || consumers.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var consumer : consumers) {
            String topicName = consumer.getTopic() != null ? consumer.getTopic() : "unknown";
            String consumerKey = "mq-consumer:" + consumer.getAnnotationType() + ":"
                    + nullToUnknown(consumer.getClassName()) + "."
                    + nullToUnknown(consumer.getMethodName()) + ":" + topicName;
            String consumerId = IdUtil.fastUUID();

            GraphNode consumerNode = buildNode(projectId, versionId, consumerId,
                    NodeType.MQConsumer.name(), consumerKey, topicName,
                    topicName,
                    consumer.getAnnotationType() + " 消息消费者",
                    SourceType.CODE_AST.name(), consumer.getSourcePath(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            consumerNode.setStartLine(consumer.getStartLine());
            consumerNode.setEndLine(consumer.getEndLine());
            allNodes.add(consumerNode);

            String topicKey = "mq-topic:" + topicName;
            GraphNode topicNode = buildNode(projectId, versionId, IdUtil.fastUUID(),
                    NodeType.MQTopic.name(), topicKey, topicName,
                    topicName,
                    consumer.getAnnotationType() + " 消息主题",
                    SourceType.CODE_AST.name(), consumer.getSourcePath(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(topicNode);

            allEdges.add(buildEdge(projectId, versionId,
                    consumerNode.getId(), topicNode.getId(),
                    EdgeType.CONSUMES.name(),
                    consumerKey + "->consumes->" + topicKey,
                    SourceType.CODE_AST.name(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED));

            if (consumer.getClassName() != null && consumer.getMethodName() != null) {
                String methodKey = buildMethodKey(consumer.getClassName(), consumer.getMethodName(), consumer.getMethodSignature());
                GraphNode methodNode = buildNode(projectId, versionId, IdUtil.fastUUID(),
                        NodeType.Method.name(), methodKey, consumer.getMethodName(),
                        consumer.getMethodName(), "消息消费处理方法",
                        SourceType.CODE_AST.name(), consumer.getSourcePath(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED,
                        "CODE_SCAN");
                methodNode.setStartLine(consumer.getStartLine());
                methodNode.setEndLine(consumer.getEndLine());
                methodNode.setClassName(consumer.getClassName());
                allNodes.add(methodNode);

                allEdges.add(buildEdge(projectId, versionId,
                        consumerNode.getId(), methodNode.getId(),
                        EdgeType.HANDLED_BY.name(),
                        consumerKey + "->handled_by->" + methodKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED));

                allEdges.add(buildEdge(projectId, versionId,
                        methodNode.getId(), topicNode.getId(),
                        EdgeType.TRIGGERS.name(),
                        methodKey + "->triggers->" + topicKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED));
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built MQ graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 外部系统图谱构建 ====================

    /**
     * 构建外部系统图谱：ExternalSystem / 外部 ApiEndpoint 节点 + CALLS_EXTERNAL 边
     */
    public void buildExternalSystemGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.ExternalSystemExtractor.ExternalCallFact> calls) {
        if (calls == null || calls.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var call : calls) {
            String edgeKeySuffix = call.getServiceName() != null ? call.getServiceName() : call.getBaseUrl();
            String systemKey = "ext:" + call.getClientType() + ":" + (edgeKeySuffix != null ? edgeKeySuffix : "unknown");
            String systemId = IdUtil.fastUUID();

            // ExternalSystem 节点
            GraphNode systemNode = buildNode(projectId, versionId, systemId,
                    NodeType.ExternalSystem.name(), systemKey, edgeKeySuffix,
                    edgeKeySuffix,
                    call.getClientType() + " 外部调用",
                    SourceType.CODE_AST.name(), call.getSourcePath(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            systemNode.setStartLine(call.getStartLine());
            systemNode.setEndLine(call.getEndLine());
            allNodes.add(systemNode);

            if (call.getBaseUrl() != null && !call.getBaseUrl().isBlank()) {
                String apiKey = "external:" + call.getBaseUrl();
                GraphNode externalApiNode = buildNode(projectId, versionId, IdUtil.fastUUID(),
                        NodeType.ApiEndpoint.name(), apiKey, call.getBaseUrl(),
                        call.getBaseUrl(), "外部 API: " + call.getBaseUrl(),
                        SourceType.CODE_AST.name(), call.getSourcePath(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED,
                        "CODE_SCAN");
                externalApiNode.setStartLine(call.getStartLine());
                externalApiNode.setEndLine(call.getEndLine());
                allNodes.add(externalApiNode);

                allEdges.add(buildEdge(projectId, versionId,
                        systemId, externalApiNode.getId(),
                        EdgeType.CALLS_EXTERNAL.name(),
                        systemKey + "->calls_external->" + apiKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED));
            }

            if (call.getClassName() != null && call.getMethodName() != null) {
                String methodKey = buildMethodKey(call.getClassName(), call.getMethodName(), call.getMethodSignature());
                GraphNode methodNode = buildNode(projectId, versionId, IdUtil.fastUUID(),
                        NodeType.Method.name(), methodKey, call.getMethodName(),
                        call.getMethodName(), "外部系统调用方法",
                        SourceType.CODE_AST.name(), call.getSourcePath(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED,
                        "CODE_SCAN");
                methodNode.setStartLine(call.getStartLine());
                methodNode.setEndLine(call.getEndLine());
                methodNode.setClassName(call.getClassName());
                allNodes.add(methodNode);

                allEdges.add(buildEdge(projectId, versionId,
                        methodNode.getId(), systemId,
                        EdgeType.CALLS_EXTERNAL.name(),
                        methodKey + "->calls_external->" + systemKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED));
            } else if (call.getClassName() != null) {
                NodeType classNodeType = inferNodeType(call.getClassName());
                GraphNode classNode = buildNode(projectId, versionId, IdUtil.fastUUID(),
                        classNodeType.name(), call.getClassName(),
                        call.getClassName().substring(call.getClassName().lastIndexOf('.') + 1),
                        call.getClassName().substring(call.getClassName().lastIndexOf('.') + 1),
                        null,
                        SourceType.CODE_AST.name(), call.getSourcePath(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED,
                        "CODE_SCAN");
                allNodes.add(classNode);
                allEdges.add(buildEdge(projectId, versionId,
                        classNode.getId(), systemId,
                        EdgeType.CALLS_EXTERNAL.name(),
                        call.getClassName() + "->calls_external->" + systemKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED));
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built external system graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 测试用例图谱构建 ====================

    /**
     * 构建测试用例图谱：TestCase / Assertion 节点 + 验证关系
     */
    public void buildTestCaseGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.TestCaseExtractor.TestCaseFact> testCases) {
        if (testCases == null || testCases.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var tc : testCases) {
            String testKey = "test:" + tc.getClassName() + "." + tc.getMethodName();
            String testId = IdUtil.fastUUID();

            // TestCase 节点
            GraphNode testNode = buildNode(projectId, versionId, testId,
                    NodeType.TestCase.name(), testKey, tc.getMethodName(),
                    tc.getClassName() + "." + tc.getMethodName(),
                    tc.getAnnotationType() != null ? tc.getAnnotationType() : "Unit Test",
                    SourceType.CODE_AST.name(), tc.getSourcePath(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            testNode.setStartLine(tc.getStartLine());
            testNode.setEndLine(tc.getEndLine());
            allNodes.add(testNode);

            if (tc.getAssertions() != null) {
                int index = 0;
                for (var assertion : tc.getAssertions()) {
                    String assertionLine = assertion.getStartLine() != null
                            ? assertion.getStartLine().toString()
                            : String.valueOf(index);
                    String assertionKey = "assertion:" + testKey + "#"
                            + assertion.getAssertionType() + "#" + assertionLine;
                    GraphNode assertionNode = buildNode(projectId, versionId, IdUtil.fastUUID(),
                            NodeType.Assertion.name(), assertionKey, assertion.getAssertionType(),
                            assertion.getAssertionType(),
                            assertion.getExpectedValue(),
                            SourceType.CODE_AST.name(), tc.getSourcePath(),
                            BigDecimal.ONE, NodeStatus.CONFIRMED,
                            "CODE_SCAN");
                    assertionNode.setStartLine(assertion.getStartLine());
                    assertionNode.setEndLine(assertion.getEndLine());
                    allNodes.add(assertionNode);

                    allEdges.add(buildEdge(projectId, versionId,
                            testId, assertionNode.getId(),
                            EdgeType.CONTAINS.name(),
                            testKey + "->contains->" + assertionKey,
                            SourceType.CODE_AST.name(),
                            BigDecimal.ONE, NodeStatus.CONFIRMED));
                    index++;
                }
            }

            String testedClassKey = inferTestedClassKey(tc.getClassName());
            if (testedClassKey != null) {
                GraphNode classNode = findExistingNode(projectId, versionId,
                        NodeType.Service.name(), testedClassKey);
                if (classNode == null) {
                    classNode = buildNode(projectId, versionId, IdUtil.fastUUID(),
                            NodeType.Service.name(), testedClassKey,
                            testedClassKey.substring(testedClassKey.lastIndexOf('.') + 1),
                            testedClassKey.substring(testedClassKey.lastIndexOf('.') + 1),
                            "测试推断的被测类",
                            SourceType.CODE_AST.name(), tc.getSourcePath(),
                            BigDecimal.valueOf(0.6), NodeStatus.PENDING_CONFIRM,
                            "CODE_SCAN");
                    allNodes.add(classNode);
                }
                allEdges.add(buildEdge(projectId, versionId,
                        classNode.getId(), testId,
                        EdgeType.VERIFIED_BY.name(),
                        testedClassKey + "->verified_by->" + testKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.valueOf(0.6), NodeStatus.PENDING_CONFIRM));
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built test case graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 前端 Button/Permission 建图 ====================

    /**
     * 构建前端按钮和权限图谱
     * 
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param buttons 按钮事实列表（来自 FrontendApiExtractor）
     * @param pageFacts 页面事实列表（包含权限信息）
     */
    public void buildFrontendButtonPermissionGraph(String projectId, String versionId,
            List<io.github.legacygraph.model.FrontendPageFact.FrontendButton> buttons,
            List<io.github.legacygraph.model.FrontendPageFact> pageFacts) {

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        // 1. 创建 Button 节点
        for (var button : buttons) {
            String buttonKey = "button:" + button.getText();
            String buttonId = IdUtil.fastUUID();

            GraphNode buttonNode = buildNode(projectId, versionId, buttonId,
                    NodeType.Button.name(), buttonKey, button.getText(),
                    button.getText(), button.getClickMethod(),
                    SourceType.FRONTEND_AST.name(), null,
                    BigDecimal.ONE, NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(buttonNode);

            // Button -> Permission REQUIRES 边
            if (button.getPermission() != null && !button.getPermission().isBlank()) {
                String permKey = "permission:" + button.getPermission();
                String permId = IdUtil.fastUUID();

                GraphNode permNode = buildNode(projectId, versionId, permId,
                        NodeType.Permission.name(), permKey, button.getPermission(),
                        button.getPermission(), "权限标识",
                        SourceType.FRONTEND_AST.name(), null,
                        BigDecimal.ONE, NodeStatus.CONFIRMED,
                        "CODE_SCAN");
                allNodes.add(permNode);

                allEdges.add(buildEdge(projectId, versionId,
                        buttonId, permId,
                        EdgeType.REQUIRES.name(),
                        buttonKey + "->requires->" + permKey,
                        SourceType.FRONTEND_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED));
            }

            // Button -> ApiEndpoint CALLS 边（如果有 apiUrl）
            if (button.getApiUrl() != null && !button.getApiUrl().isBlank()) {
                String apiKey = "api:" + button.getApiUrl();
                GraphNode apiNode = findExistingNode(projectId, versionId,
                        NodeType.ApiEndpoint.name(), apiKey);
                if (apiNode != null) {
                    allEdges.add(buildEdge(projectId, versionId,
                            buttonId, apiNode.getId(),
                            EdgeType.CALLS.name(),
                            buttonKey + "->calls->" + apiKey,
                            SourceType.FRONTEND_AST.name(),
                            BigDecimal.ONE, NodeStatus.CONFIRMED));
                }
            }
        }

        // 2. 创建 Page -> Permission 边（从页面事实）
        for (var page : pageFacts) {
            if (page.getPermission() != null && !page.getPermission().isBlank()) {
                String pageKey = "page:" + page.getRoutePath();
                GraphNode pageNode = findExistingNode(projectId, versionId,
                        NodeType.Page.name(), pageKey);

                if (pageNode != null) {
                    String permKey = "permission:" + page.getPermission();
                    String permId = IdUtil.fastUUID();

                    GraphNode permNode = buildNode(projectId, versionId, permId,
                            NodeType.Permission.name(), permKey, page.getPermission(),
                            page.getPermission(), "页面访问权限",
                            SourceType.FRONTEND_AST.name(), null,
                            BigDecimal.ONE, NodeStatus.CONFIRMED,
                            "CODE_SCAN");
                    allNodes.add(permNode);

                    allEdges.add(buildEdge(projectId, versionId,
                            pageNode.getId(), permId,
                            EdgeType.REQUIRES.name(),
                            pageKey + "->requires->" + permKey,
                            SourceType.FRONTEND_AST.name(),
                            BigDecimal.ONE, NodeStatus.CONFIRMED));
                }
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built frontend button/permission graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== FeatureModule 建图 ====================

    /**
     * 构建功能模块图谱
     * 
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param modules 功能模块事实列表
     * @param features 功能点事实列表
     */
    public void buildFeatureModuleGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.FeatureModuleExtractor.FeatureModuleFact> modules,
            List<io.github.legacygraph.extractors.FeatureModuleExtractor.FeatureFact> features) {

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();
        Map<String, GraphNode> moduleNodes = new HashMap<>();

        // 1. 创建 FeatureModule 节点
        for (var module : modules) {
            String moduleKey = "module:" + module.getModuleName();
            String moduleId = IdUtil.fastUUID();

            String description = module.getDescription() != null 
                    ? module.getDescription() 
                    : module.getModuleName() + " 模块 (" + module.getPageCount() + " 页面)";

            GraphNode moduleNode = buildNode(projectId, versionId, moduleId,
                    NodeType.FeatureModule.name(), moduleKey, module.getModuleName(),
                    module.getModuleName(), description,
                    SourceType.FRONTEND_AST.name(), module.getModulePath(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(moduleNode);
            moduleNodes.put(moduleKey, moduleNode);
        }

        // 2. 创建 Feature 节点和 FeatureModule -> Feature CONTAINS 边
        for (var feature : features) {
            String featureKey = "feature:" + feature.getModuleName() + "/" + feature.getFeatureName();
            String featureId = IdUtil.fastUUID();

            String description = feature.getDescription() != null 
                    ? feature.getDescription() 
                    : feature.getFeatureName();

            GraphNode featureNode = buildNode(projectId, versionId, featureId,
                    NodeType.Feature.name(), featureKey, feature.getFeatureName(),
                    feature.getFeatureName(), description,
                    SourceType.FRONTEND_AST.name(), feature.getFeaturePath(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(featureNode);

            // FeatureModule -> Feature CONTAINS 边
            String moduleKey = "module:" + feature.getModuleName();
            GraphNode moduleNode = moduleNodes.get(moduleKey);
            if (moduleNode == null) {
                moduleNode = findExistingNode(projectId, versionId,
                        NodeType.FeatureModule.name(), moduleKey);
            }

            if (moduleNode != null) {
                allEdges.add(buildEdge(projectId, versionId,
                        moduleNode.getId(), featureId,
                        EdgeType.CONTAINS.name(),
                        moduleKey + "->contains->" + featureKey,
                        SourceType.FRONTEND_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED));
            }

            // Feature -> Page IMPLEMENTS 边（尝试匹配页面节点）
            String pageKey = "page:/" + feature.getModuleName() + "/" + feature.getFeatureName();
            GraphNode pageNode = findExistingNode(projectId, versionId,
                    NodeType.Page.name(), pageKey);

            if (pageNode != null) {
                allEdges.add(buildEdge(projectId, versionId,
                        featureId, pageNode.getId(),
                        EdgeType.IMPLEMENTS.name(),
                        featureKey + "->implements->" + pageKey,
                        SourceType.FRONTEND_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED));
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built feature module graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== RBAC 角色图谱构建 ====================

    /**
     * 构建 RBAC 角色图谱：Role 节点 + USES 边
     */
    public void buildRbacRoleGraph(String projectId, String versionId,
            List<io.github.legacygraph.model.NodeExtractionResult> roles) {
        if (roles == null || roles.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var role : roles) {
            String roleKey = role.getNodeKey();
            String roleId = IdUtil.fastUUID();

            GraphNode roleNode = buildNode(projectId, versionId, roleId,
                    NodeType.Role.name(), roleKey, role.getDisplayName(),
                    role.getDisplayName(), role.getDescription(),
                    role.getSourceType(), role.getSourcePath(),
                    BigDecimal.valueOf(role.getConfidence()), NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(roleNode);

            // Role -> Service/Controller USES 边
            String context = (String) role.getProperties().get("context");
            if (context != null) {
                String className = context.contains(".") ? context.substring(0, context.indexOf(".")) : context;
                GraphNode classNode = findExistingNode(projectId, versionId,
                        NodeType.Service.name(), className);
                if (classNode == null) {
                    classNode = findExistingNode(projectId, versionId,
                            NodeType.Controller.name(), className);
                }
                if (classNode != null) {
                    allEdges.add(buildEdge(projectId, versionId,
                            roleId, classNode.getId(),
                            EdgeType.USES.name(),
                            roleKey + "->uses->" + className,
                            role.getSourceType(),
                            BigDecimal.valueOf(role.getConfidence()), NodeStatus.CONFIRMED));
                }
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built RBAC role graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 业务域图谱构建 ====================

    /**
     * 构建业务域图谱：BusinessDomain 节点
     */
    public void buildBusinessDomainGraph(String projectId, String versionId,
            List<io.github.legacygraph.model.NodeExtractionResult> domains) {
        if (domains == null || domains.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();

        for (var domain : domains) {
            String domainKey = domain.getNodeKey();
            String domainId = IdUtil.fastUUID();

            GraphNode domainNode = buildNode(projectId, versionId, domainId,
                    NodeType.BusinessDomain.name(), domainKey, domain.getDisplayName(),
                    domain.getDisplayName(), domain.getDescription(),
                    domain.getSourceType(), domain.getSourcePath(),
                    BigDecimal.valueOf(domain.getConfidence()), NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(domainNode);
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        log.info("Built business domain graph: {} nodes (projectId={}, versionId={})",
                nodeCount, projectId, versionId);
    }

    // ==================== 业务对象图谱构建 ====================

    /**
     * 构建业务对象图谱：BusinessObject 节点 + MAPS_TO 边
     */
    public void buildBusinessObjectGraph(String projectId, String versionId,
            List<io.github.legacygraph.model.NodeExtractionResult> objects) {
        if (objects == null || objects.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var obj : objects) {
            String objKey = obj.getNodeKey();
            String objId = IdUtil.fastUUID();

            GraphNode objNode = buildNode(projectId, versionId, objId,
                    NodeType.BusinessObject.name(), objKey, obj.getDisplayName(),
                    obj.getDisplayName(), obj.getDescription(),
                    obj.getSourceType(), obj.getSourcePath(),
                    BigDecimal.valueOf(obj.getConfidence()), NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(objNode);

            // BusinessObject -> Table MAPS_TO 边
            String tableName = (String) obj.getProperties().get("tableName");
            if (tableName != null && !tableName.isEmpty()) {
                GraphNode tableNode = findExistingNode(projectId, versionId,
                        NodeType.Table.name(), tableName);
                if (tableNode != null) {
                    allEdges.add(buildEdge(projectId, versionId,
                            objId, tableNode.getId(),
                            EdgeType.MAPS_TO.name(),
                            objKey + "->maps_to->" + tableName,
                            obj.getSourceType(),
                            BigDecimal.valueOf(obj.getConfidence()), NodeStatus.CONFIRMED));
                }
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built business object graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 业务规则图谱构建 ====================

    /**
     * 构建业务规则图谱：BusinessRule 节点 + APPLIES_TO 边
     */
    public void buildBusinessRuleGraph(String projectId, String versionId,
            List<io.github.legacygraph.model.NodeExtractionResult> rules) {
        if (rules == null || rules.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var rule : rules) {
            String ruleKey = rule.getNodeKey();
            String ruleId = IdUtil.fastUUID();

            GraphNode ruleNode = buildNode(projectId, versionId, ruleId,
                    NodeType.BusinessRule.name(), ruleKey, rule.getDisplayName(),
                    rule.getDisplayName(), rule.getDescription(),
                    rule.getSourceType(), rule.getSourcePath(),
                    BigDecimal.valueOf(rule.getConfidence()), NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(ruleNode);

            // BusinessRule -> BusinessObject APPLIES_TO 边
            String className = (String) rule.getProperties().get("className");
            if (className != null) {
                String objKey = "bo:" + className.toLowerCase();
                GraphNode objNode = findExistingNode(projectId, versionId,
                        NodeType.BusinessObject.name(), objKey);
                if (objNode != null) {
                    allEdges.add(buildEdge(projectId, versionId,
                            ruleId, objNode.getId(),
                            EdgeType.APPLIES_TO.name(),
                            ruleKey + "->applies_to->" + objKey,
                            rule.getSourceType(),
                            BigDecimal.valueOf(rule.getConfidence()), NodeStatus.CONFIRMED));
                }
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built business rule graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 业务流程图谱构建 ====================

    /**
     * 构建业务流程图谱：BusinessProcess 节点 + CALLS 边
     */
    public void buildBusinessProcessGraph(String projectId, String versionId,
            List<io.github.legacygraph.model.NodeExtractionResult> processes) {
        if (processes == null || processes.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var process : processes) {
            String processKey = process.getNodeKey();
            String processId = IdUtil.fastUUID();

            GraphNode processNode = buildNode(projectId, versionId, processId,
                    NodeType.BusinessProcess.name(), processKey, process.getDisplayName(),
                    process.getDisplayName(), process.getDescription(),
                    process.getSourceType(), process.getSourcePath(),
                    BigDecimal.valueOf(process.getConfidence()), NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(processNode);

            // BusinessProcess -> Service CALLS 边
            String className = (String) process.getProperties().get("className");
            if (className != null) {
                GraphNode classNode = findExistingNode(projectId, versionId,
                        NodeType.Service.name(), className);
                if (classNode != null) {
                    allEdges.add(buildEdge(projectId, versionId,
                            processId, classNode.getId(),
                            EdgeType.CALLS.name(),
                            processKey + "->calls->" + className,
                            process.getSourceType(),
                            BigDecimal.valueOf(process.getConfidence()), NodeStatus.CONFIRMED));
                }
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built business process graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }
}
