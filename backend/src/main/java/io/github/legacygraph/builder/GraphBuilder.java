package io.github.legacygraph.builder;

import io.github.legacygraph.agent.SqlAdvisorAgent;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.SqlAdvisorResult;
import io.github.legacygraph.entity.*;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.extractors.ServiceCallExtractor;
import io.github.legacygraph.extractors.SqlTableExtractor;
import io.github.legacygraph.model.ApiFact;
import io.github.legacygraph.model.MapperSqlFact;
import io.github.legacygraph.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 统一图谱构建器
 * 将抽取的事实转换为图谱节点和关系，直写 Neo4j（不再经 PostgreSQL）
 */
@Slf4j
@Component
public class GraphBuilder {

    private final Neo4jGraphDao neo4jGraphDao;
    private final EvidenceRepository evidenceRepository;
    private final NodeEvidenceRepository nodeEvidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;
    private final SqlAdvisorAgent sqlAdvisorAgent;
    private final ReviewRecordRepository reviewRecordRepository;

    public GraphBuilder(Neo4jGraphDao neo4jGraphDao,
                       EvidenceRepository evidenceRepository,
                       NodeEvidenceRepository nodeEvidenceRepository,
                       EdgeEvidenceRepository edgeEvidenceRepository,
                       SqlAdvisorAgent sqlAdvisorAgent,
                       ReviewRecordRepository reviewRecordRepository) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.evidenceRepository = evidenceRepository;
        this.nodeEvidenceRepository = nodeEvidenceRepository;
        this.edgeEvidenceRepository = edgeEvidenceRepository;
        this.sqlAdvisorAgent = sqlAdvisorAgent;
        this.reviewRecordRepository = reviewRecordRepository;
    }

    /**
     * 构建API接口图谱
     */
    @Transactional
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

            // 创建Method节点
            String methodNodeKey = controllerNodeKey + "." + api.getMethodName();
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
                    NodeStatus.CONFIRMED
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
    @Transactional
    public void buildMapperSqlGraph(String projectId, String versionId, MapperSqlFact mapperFact) {
        // 创建Mapper节点
        String mapperKey = mapperFact.getNamespace();
        GraphNode mapperNode = findOrCreateNode(
                projectId, versionId,
                NodeType.Mapper.name(),
                mapperKey,
                mapperFact.getMapperInterface() != null ? mapperFact.getMapperInterface() : mapperKey,
                null,
                null,
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

            // 解析SQL表关系
            SqlTableExtractor.SqlTableResult tableResult = new SqlTableExtractor().extractTables(stmt.getSql());
            analyzeSqlAndCreateReview(projectId, versionId, sqlKey, sqlNode, stmt, tableResult);

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

    private void analyzeSqlAndCreateReview(String projectId, String versionId, String sqlKey, GraphNode sqlNode,
                                           MyBatisXmlExtractor.SqlStatement stmt,
                                           SqlTableExtractor.SqlTableResult tableResult) {
        if (sqlAdvisorAgent == null || stmt.getSql() == null || stmt.getSql().isBlank()) {
            return;
        }
        try {
            SqlAdvisorResult result = sqlAdvisorAgent.analyze(projectId, sqlKey,
                    stmt.getExpandedSql() != null && !stmt.getExpandedSql().isBlank()
                            ? stmt.getExpandedSql() : stmt.getSql(),
                    buildSchemaInfo(tableResult));
            if (result == null || result.getIssues() == null || result.getIssues().isEmpty()) {
                return;
            }
            long exists = reviewRecordRepository.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReviewRecord>()
                            .eq(ReviewRecord::getProjectId, projectId)
                            .eq(ReviewRecord::getTargetId, sqlNode.getId())
                            .eq(ReviewRecord::getStatus, "PENDING"));
            if (exists > 0) {
                return;
            }
            ReviewRecord review = new ReviewRecord();
            review.setId(UUID.randomUUID().toString());
            review.setProjectId(projectId);
            review.setVersionId(versionId);
            review.setTargetType(NodeType.SqlStatement.name());
            review.setTargetId(sqlNode.getId());
            review.setTargetName(sqlNode.getDisplayName());
            review.setGraphType("SQL");
            review.setConfidence(sqlNode.getConfidence() != null ? sqlNode.getConfidence().doubleValue() : 1.0);
            review.setPriority(toPriority(result));
            review.setStatus("PENDING");
            review.setComment(buildSqlAdvisorComment(result));
            review.setCreatedAt(LocalDateTime.now());
            reviewRecordRepository.insert(review);
        } catch (Exception e) {
            log.warn("SQL advisor failed for {}: {}", sqlKey, e.getMessage());
        }
    }

    private String buildSchemaInfo(SqlTableExtractor.SqlTableResult tableResult) {
        if (tableResult == null) {
            return "（无表结构信息）";
        }
        return "readTables=" + tableResult.getReadTables()
                + ", writeTables=" + tableResult.getWriteTables()
                + ", joinTables=" + tableResult.getJoinTables();
    }

    private String toPriority(SqlAdvisorResult result) {
        if (result.getIssues().stream().anyMatch(i -> "HIGH".equalsIgnoreCase(i.getSeverity()))
                || "HIGH".equalsIgnoreCase(result.getOverallRisk())) {
            return "HIGH";
        }
        if (result.getIssues().stream().anyMatch(i -> "MEDIUM".equalsIgnoreCase(i.getSeverity()))
                || "MEDIUM".equalsIgnoreCase(result.getOverallRisk())) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String buildSqlAdvisorComment(SqlAdvisorResult result) {
        StringBuilder comment = new StringBuilder("SQL 性能顾问: ")
                .append(result.getSummary() != null ? result.getSummary() : "发现 SQL 优化问题");
        for (SqlAdvisorResult.SqlIssue issue : result.getIssues()) {
            comment.append("\n- ")
                    .append(issue.getIssueType() != null ? issue.getIssueType() : "SQL_ISSUE")
                    .append(": ")
                    .append(issue.getDescription() != null ? issue.getDescription() : "")
                    .append(" 建议: ")
                    .append(issue.getSuggestion() != null ? issue.getSuggestion() : "");
        }
        if (result.getOptimizedSql() != null && !result.getOptimizedSql().isBlank()) {
            comment.append("\n优化后 SQL: ").append(result.getOptimizedSql());
        }
        return comment.toString();
    }

    /**
     * 构建数据库表和字段图谱
     */
    @Transactional
    public void buildDatabaseGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.DatabaseMetadataExtractor.TableMetadata> tables) {
        for (var tableMeta : tables) {
            String tableKey = tableMeta.getTableSchema() + "." + tableMeta.getTableName();
            GraphNode tableNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.Table.name(),
                    tableKey,
                    tableMeta.getTableName(),
                    tableMeta.getTableName(),
                    tableMeta.getTableComment(),
                    SourceType.DB_METADATA.name(),
                    null,
                    null,
                    null,
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );

            // 创建字段节点
            for (var colMeta : tableMeta.getColumns()) {
                String colKey = tableKey + "." + colMeta.getColumnName();
                GraphNode colNode = findOrCreateNode(
                        projectId, versionId,
                        NodeType.Column.name(),
                        colKey,
                        colMeta.getColumnName(),
                        colMeta.getColumnName(),
                        colMeta.getColumnComment(),
                        SourceType.DB_METADATA.name(),
                        null,
                        null,
                        null,
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED
                );

                // Table -HAS_COLUMN-> Column
                createEdge(projectId, versionId,
                        tableNode.getId(), colNode.getId(),
                        EdgeType.HAS_COLUMN.name(),
                        tableKey + "->has_column->" + colKey,
                        SourceType.DB_METADATA.name(),
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED
                );

                // 如果推断为外键，添加关系
                if (Boolean.TRUE.equals(colMeta.getForeignKey()) && colMeta.getReferencedTableName() != null) {
                    // 推断引用表
                    String refTableKey;
                    if (tableMeta.getTableSchema() != null) {
                        refTableKey = tableMeta.getTableSchema() + "." + colMeta.getReferencedTableName();
                    } else {
                        refTableKey = colMeta.getReferencedTableName();
                    }
                    GraphNode refTable = findOrCreateTableNode(projectId, versionId, colMeta.getReferencedTableName());
                    // 外键关系已经通过JOINS在SQL中处理，这里不重复
                }
            }
        }
    }

    /**
     * 查找或创建节点
     */
    private GraphNode findOrCreateNode(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status) {
        // Neo4j 中查找是否已存在
        Optional<GraphNode> existing = neo4jGraphDao.findNode(projectId, versionId, nodeType, nodeKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        GraphNode node = new GraphNode();
        node.setId(UUID.randomUUID().toString());
        node.setProjectId(projectId);
        node.setVersionId(versionId);
        node.setNodeType(nodeType);
        node.setNodeKey(nodeKey);
        node.setNodeName(nodeName);
        node.setDisplayName(displayName);
        node.setDescription(description);
        node.setSourceType(sourceType);
        node.setSourcePath(sourcePath);
        node.setStartLine(startLine);
        node.setEndLine(endLine);
        node.setConfidence(confidence);
        node.setStatus(status.name());
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());

        neo4jGraphDao.createNode(node);

        // 创建证据并关联
        if (sourcePath != null && !sourcePath.isEmpty()) {
            createEvidenceForNode(node, sourceType, sourcePath, startLine, endLine);
        }

        return node;
    }

    /**
     * 为节点创建证据记录并建立关联
     */
    private void createEvidenceForNode(GraphNode node, String sourceType, String sourcePath,
            Integer startLine, Integer endLine) {
        Evidence evidence = new Evidence();
        evidence.setId(UUID.randomUUID().toString());
        evidence.setProjectId(node.getProjectId());
        evidence.setVersionId(node.getVersionId());
        evidence.setEvidenceType(mapSourceTypeToEvidenceType(sourceType));
        evidence.setSourcePath(sourcePath);
        evidence.setSourceName(node.getDisplayName());
        evidence.setStartLine(startLine);
        evidence.setEndLine(endLine);
        evidence.setCreatedAt(LocalDateTime.now());
        evidenceRepository.insert(evidence);

        // 创建节点-证据关联
        NodeEvidence nodeEvidence = new NodeEvidence();
        nodeEvidence.setId(UUID.randomUUID().toString());
        nodeEvidence.setNodeId(node.getId());
        nodeEvidence.setEvidenceId(evidence.getId());
        nodeEvidence.setRelationType("PRIMARY_SOURCE");
        nodeEvidence.setCreatedAt(LocalDateTime.now());
        nodeEvidenceRepository.insert(nodeEvidence);
    }

    /**
     * 将源码类型映射为证据类型
     */
    private String mapSourceTypeToEvidenceType(String sourceType) {
        if (sourceType == null) return "unknown";
        return switch (sourceType) {
            case "CODE_AST" -> "code";
            case "MYBATIS_XML", "SQL_PARSE" -> "sql";
            case "FRONTEND_AST" -> "ui";
            case "DB_METADATA" -> "db";
            case "DOCUMENT" -> "doc";
            default -> sourceType.toLowerCase();
        };
    }

    /**
     * 查找或创建表节点（便捷方法）
     */
    private GraphNode findOrCreateTableNode(String projectId, String versionId, String tableName) {
        return findOrCreateNode(
                projectId, versionId,
                NodeType.Table.name(),
                tableName,
                tableName,
                tableName,
                null,
                SourceType.SQL_PARSE.name(),
                null,
                null,
                null,
                BigDecimal.valueOf(0.95),
                NodeStatus.CONFIRMED
        );
    }

    /**
     * 创建边（去重：按 fromNodeId+toNodeId+edgeType+edgeKey 查找，已存在则直接返回）
     */
    private GraphEdge createEdge(String projectId, String versionId,
            String fromNodeId, String toNodeId,
            String edgeType, String edgeKey,
            String sourceType, BigDecimal confidence,
            NodeStatus status) {
        // 先去重检查
        Optional<GraphEdge> existing = neo4jGraphDao.findEdge(fromNodeId, toNodeId, edgeType, edgeKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        GraphEdge edge = new GraphEdge();
        edge.setId(UUID.randomUUID().toString());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setEdgeType(edgeType);
        edge.setEdgeKey(edgeKey);
        edge.setSourceType(sourceType);
        edge.setConfidence(confidence);
        edge.setStatus(status.name());
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());

        neo4jGraphDao.createEdge(edge);

        // 从源节点继承证据（创建关联）
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NodeEvidence> neQuery =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        neQuery.eq(NodeEvidence::getNodeId, fromNodeId);
        List<NodeEvidence> nodeEvidences = nodeEvidenceRepository.selectList(neQuery);
        for (NodeEvidence ne : nodeEvidences) {
            EdgeEvidence edgeEvidence = new EdgeEvidence();
            edgeEvidence.setId(UUID.randomUUID().toString());
            edgeEvidence.setEdgeId(edge.getId());
            edgeEvidence.setEvidenceId(ne.getEvidenceId());
            edgeEvidence.setRelationType("INHERITED");
            edgeEvidence.setCreatedAt(LocalDateTime.now());
            edgeEvidenceRepository.insert(edgeEvidence);
        }

        return edge;
    }

    /**
     * API路径归一化
     */
    public static String normalizeApiKey(String httpMethod, String path) {
        return httpMethod + " " + normalizePath(path);
    }

    /**
     * 归一化路径，将不同参数风格统一为 {param} 形式
     */
    public static String normalizePath(String path) {
        // /user/:id -> /user/{id}
        // /user/${id} -> /user/{id}
        // /user/123 -> /user/{id} (无法确定参数名保持原样)
        String normalized = path;
        normalized = normalized.replaceAll("/:[^/]+", "/{id}");
        normalized = normalized.replaceAll("/\\$\\{[^}]+\\}", "/{id}");
        // 数字路径段保留，不做归一化，因为可能不是参数
        return normalized;
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

            // 查找或创建被调用方节点（targetClass 可能为 null，此时跳过该调用关系）
            String targetClassKey = call.getTargetClass();
            if (targetClassKey == null || targetClassKey.isEmpty()) {
                log.debug("Skipping call relation with null targetClass: caller={}, calledMethod={}",
                        callerClassKey, call.getCalledMethod());
                continue;
            }
            NodeType targetNodeType = inferNodeType(targetClassKey);
            GraphNode targetNode = findOrCreateNodeByClass(
                    projectId, versionId, targetNodeType, targetClassKey, call.getSourcePath());

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
                    String callerMethodKey = callerClassKey + "." + call.getCallerMethod();
                    GraphNode callerMethodNode = findExistingNode(projectId, versionId, NodeType.Method.name(), callerMethodKey);
                    String targetMethodKey = targetClassKey + "." + call.getTargetMethod();
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
}
