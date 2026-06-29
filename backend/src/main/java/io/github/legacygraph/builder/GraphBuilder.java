package io.github.legacygraph.builder;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.entity.*;
import io.github.legacygraph.extractors.MyBatisXmlExtractor;
import io.github.legacygraph.extractors.ServiceCallExtractor;
import io.github.legacygraph.extractors.SqlTableExtractor;
import io.github.legacygraph.model.ApiFact;
import io.github.legacygraph.model.MapperSqlFact;
import io.github.legacygraph.repository.*;
import io.github.legacygraph.service.Neo4jSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 统一图谱构建器
 * 将抽取的事实转换为图谱节点和关系，并写入PostgreSQL和Neo4j
 */
@Slf4j
@Component
public class GraphBuilder {

    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;
    private final EvidenceRepository evidenceRepository;
    private final NodeEvidenceRepository nodeEvidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;
    private final Neo4jSyncService neo4jSyncService;

    public GraphBuilder(GraphNodeRepository graphNodeRepository,
                       GraphEdgeRepository graphEdgeRepository,
                       EvidenceRepository evidenceRepository,
                       NodeEvidenceRepository nodeEvidenceRepository,
                       EdgeEvidenceRepository edgeEvidenceRepository,
                       Neo4jSyncService neo4jSyncService) {
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
        this.evidenceRepository = evidenceRepository;
        this.nodeEvidenceRepository = nodeEvidenceRepository;
        this.edgeEvidenceRepository = edgeEvidenceRepository;
        this.neo4jSyncService = neo4jSyncService;
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
        // 查询是否已存在
        GraphNode existing = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getNodeType, nodeType)
                .eq(GraphNode::getNodeKey, nodeKey)
                .oneOpt()
                .orElse(null);

        if (existing != null) {
            return existing;
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

        graphNodeRepository.insert(node);

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
     * 创建边
     */
    private GraphEdge createEdge(String projectId, String versionId,
            String fromNodeId, String toNodeId,
            String edgeType, String edgeKey,
            String sourceType, BigDecimal confidence,
            NodeStatus status) {
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

        graphEdgeRepository.insert(edge);

        // 从源节点继承证据（创建关联）
        // 找到源节点的证据，关联到这条边
        List<NodeEvidence> nodeEvidences = nodeEvidenceRepository.lambdaQuery()
                .eq(NodeEvidence::getNodeId, fromNodeId)
                .list();
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
            NodeType callerNodeType = inferNodeType(call.getCallerClass());
            GraphNode callerNode = findOrCreateNodeByClass(
                    projectId, versionId, callerNodeType, callerClassKey, call.getSourcePath());

            // 查找或创建被调用方节点
            String targetClassKey = call.getTargetClass();
            NodeType targetNodeType = inferNodeType(call.getTargetClass());
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
        // 先查找是否已存在
        GraphNode existing = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getNodeType, nodeType.name())
                .eq(GraphNode::getNodeKey, classFullName)
                .oneOpt()
                .orElse(null);

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
        return graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getNodeType, nodeType)
                .eq(GraphNode::getNodeKey, nodeKey)
                .oneOpt()
                .orElse(null);
    }

    /**
     * 同步所有节点和关系到Neo4j
     */
    public void syncToNeo4j(String projectId, String versionId) {
        neo4jSyncService.syncGraph(projectId, versionId);
    }
}
