package io.github.legacygraph.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dao.Neo4jWriteRepository;
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
import java.util.LinkedHashSet;
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

        // P3a：预先加载 BusinessObject 节点查找表，供 ApiEndpoint USES 匹配（按多别名索引）
        Map<String, GraphNode> boLookup = buildBusinessObjectLookup(projectId, versionId);

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
            Map<String, Object> apiProps = new LinkedHashMap<>();
            putIfNotNull(apiProps, "params", api.getRequestParams());
            putIfNotNull(apiProps, "requestBody", api.getRequestBody());
            putIfNotNull(apiProps, "responseType", api.getResponseType());
            putIfNotNull(apiProps, "summary", api.getSummary());
            String apiPropertiesJson = toJsonProperties(apiProps, apiNodeKey);
            GraphNode apiNode = findOrCreateNodeWithProperties(
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
                    NodeStatus.CONFIRMED,
                    null,
                    null,
                    apiPropertiesJson
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

            // P3a：ApiEndpoint -USES-> BusinessObject（按参数/返回值类型匹配）
            linkApiEndpointToBusinessObjects(projectId, versionId, api, apiNode, boLookup);

            // 处理权限
            if (api.getPermissions() != null && !api.getPermissions().isEmpty()) {
                for (String perm : api.getPermissions()) {
                    GraphNode permNode = findOrCreateNode(
                            projectId, versionId,
                            NodeType.Permission.name(),
                            perm.toLowerCase(),
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

    // ==================== P3a: ApiEndpoint -> BusinessObject USES 边 ====================

    /** 与 BusinessObjectExtractor.ENTITY_SUFFIXES 对齐，用于剥离实体类后缀提升匹配率 */
    private static final Set<String> BO_ENTITY_SUFFIXES = Set.of(
            "Entity", "DO", "PO", "BO", "DTO", "VO", "Model", "Domain");

    /** 基本类型与常见框架/集合类型，匹配时跳过 */
    private static final Set<String> BASIC_TYPES = Set.of(
            "int", "long", "short", "byte", "float", "double", "char", "boolean", "void",
            "String", "Integer", "Long", "Short", "Byte", "Float", "Double", "Character",
            "Boolean", "Object", "Number", "BigDecimal", "BigInteger", "Date", "LocalDate",
            "LocalDateTime", "LocalTime", "Timestamp", "Time", "UUID", "InputStream",
            "OutputStream", "MultipartFile", "HttpServletRequest", "HttpServletResponse",
            "Principal", "Pageable", "Locale", "Class", "Throwable", "Exception", "Runnable",
            "Iterable", "Iterator", "List", "Set", "Map", "Collection", "ArrayList", "HashMap",
            "HashSet", "LinkedList", "Optional", "Stream", "ResponseEntity", "Page", "PageImpl"
    );

    /**
     * 预加载当前 project/version 下的 BusinessObject 节点，按多种别名建立索引。
     * nodeKey 形如 "bo:user"（已剥离实体后缀并小写），nodeName/displayName 形如 "User"。
     */
    private Map<String, GraphNode> buildBusinessObjectLookup(String projectId, String versionId) {
        Map<String, GraphNode> lookup = new HashMap<>();
        List<GraphNode> boNodes = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.BusinessObject.name(),
                null, null, null, 5000);
        if (boNodes == null || boNodes.isEmpty()) {
            return lookup;
        }
        for (GraphNode bo : boNodes) {
            indexBusinessObjectAlias(lookup, bo, bo.getNodeKey());
            indexBusinessObjectAlias(lookup, bo, stripBoPrefix(bo.getNodeKey()));
            indexBusinessObjectAlias(lookup, bo, bo.getNodeName());
            indexBusinessObjectAlias(lookup, bo, bo.getDisplayName());
        }
        return lookup;
    }

    private void indexBusinessObjectAlias(Map<String, GraphNode> lookup, GraphNode bo, String alias) {
        if (alias == null || alias.isBlank()) return;
        String trimmed = alias.trim();
        lookup.putIfAbsent(trimmed, bo);
        lookup.putIfAbsent(trimmed.toLowerCase(), bo);
        String stripped = stripEntitySuffix(trimmed);
        if (stripped != null && !stripped.equals(trimmed)) {
            lookup.putIfAbsent(stripped, bo);
            lookup.putIfAbsent(stripped.toLowerCase(), bo);
        }
    }

    private String stripBoPrefix(String nodeKey) {
        if (nodeKey == null) return null;
        return nodeKey.startsWith("bo:") ? nodeKey.substring(3) : nodeKey;
    }

    /** 去掉实体类后缀，镜像 BusinessObjectExtractor.cleanClassName 行为 */
    private String stripEntitySuffix(String name) {
        if (name == null || name.isBlank()) return name;
        for (String suffix : BO_ENTITY_SUFFIXES) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    /**
     * 将 ApiEndpoint 与其参数/返回值类型对应的 BusinessObject 建立 USES 边。
     * 类型来源：方法签名参数、返回值类型、@RequestBody、其余请求参数。
     */
    private void linkApiEndpointToBusinessObjects(String projectId, String versionId,
            ApiFact api, GraphNode apiNode, Map<String, GraphNode> boLookup) {
        if (boLookup.isEmpty() || apiNode == null) return;

        Set<String> candidateTypes = new LinkedHashSet<>();
        // 1. 方法签名中的参数类型：methodName(paramType1, paramType2)
        collectCandidateTypesFromSignature(api.getMethodSignature(), candidateTypes);
        // 2. 返回值类型（可能含泛型，如 ResponseEntity<UserDTO>）
        collectCandidateTypes(api.getResponseType(), candidateTypes);
        // 3. @RequestBody 类型
        if (api.getRequestBody() != null) {
            collectCandidateTypes(api.getRequestBody().getType(), candidateTypes);
        }
        // 4. 其余请求参数类型
        if (api.getRequestParams() != null) {
            for (ApiFact.ApiParameter param : api.getRequestParams()) {
                collectCandidateTypes(param.getType(), candidateTypes);
            }
        }

        String apiNodeKey = apiNode.getNodeKey();
        Set<String> linkedBoIds = new HashSet<>();
        for (String typeName : candidateTypes) {
            GraphNode bo = findBusinessObjectByType(typeName, boLookup);
            if (bo == null) continue;
            if (!linkedBoIds.add(bo.getId())) continue;
            createEdge(projectId, versionId,
                    apiNode.getId(), bo.getId(),
                    EdgeType.USES.name(),
                    apiNodeKey + "->uses->" + bo.getNodeKey(),
                    SourceType.CODE_AST.name(),
                    BigDecimal.valueOf(0.9),
                    NodeStatus.CONFIRMED
            );
        }
    }

    /** 从 "methodName(paramType1, paramType2)" 签名中抽取参数类型简单名 */
    private void collectCandidateTypesFromSignature(String methodSignature, Set<String> out) {
        if (methodSignature == null || methodSignature.isBlank()) return;
        int parenStart = methodSignature.indexOf('(');
        int parenEnd = methodSignature.lastIndexOf(')');
        if (parenStart < 0 || parenEnd <= parenStart) return;
        String paramsPart = methodSignature.substring(parenStart + 1, parenEnd).trim();
        if (paramsPart.isEmpty()) return;
        for (String p : paramsPart.split(",")) {
            collectCandidateTypes(p.trim(), out);
        }
    }

    /**
     * 从类型字符串中抽取候选类型简单名（处理泛型、数组、包名），跳过基本/常见类型。
     * 例如 "ResponseEntity&lt;UserDTO&gt;" → ["UserDTO"]；"List&lt;String&gt;" → []（String 被跳过）
     */
    private void collectCandidateTypes(String typeStr, Set<String> out) {
        if (typeStr == null || typeStr.isBlank()) return;
        String normalized = typeStr.replaceAll("[<>,\\[\\]]", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) continue;
            String simple = token.contains(".") ? token.substring(token.lastIndexOf('.') + 1) : token;
            if (simple.isBlank() || isPrimitiveOrBasicType(simple)) continue;
            out.add(simple);
        }
    }

    private GraphNode findBusinessObjectByType(String simpleName, Map<String, GraphNode> boLookup) {
        if (simpleName == null || simpleName.isBlank()) return null;
        GraphNode bo = boLookup.get(simpleName);
        if (bo != null) return bo;
        bo = boLookup.get(simpleName.toLowerCase());
        if (bo != null) return bo;
        String stripped = stripEntitySuffix(simpleName);
        if (stripped != null && !stripped.equals(simpleName)) {
            bo = boLookup.get(stripped);
            if (bo != null) return bo;
            bo = boLookup.get(stripped.toLowerCase());
            if (bo != null) return bo;
        }
        return null;
    }

    private boolean isPrimitiveOrBasicType(String simpleName) {
        return BASIC_TYPES.contains(simpleName);
    }

    /**
     * 构建Mapper和SQL图谱
     */
    public void buildMapperSqlGraph(String projectId, String versionId, MapperSqlFact mapperFact) {
        String mapperKey = mapperFact.getNamespace();
        if (mapperKey == null || mapperKey.isBlank()) {
            return;
        }
        String mapperName = (mapperFact.getMapperInterface() != null && !mapperFact.getMapperInterface().isBlank())
                ? mapperFact.getMapperInterface()
                : mapperKey;
        String mapperDesc = mapperFact.getNamespace() != null
                ? "MyBatis Mapper: " + mapperFact.getNamespace() : null;

        List<Neo4jWriteRepository.BatchNodeUpsert> nodeBatch = new ArrayList<>();
        List<Neo4jWriteRepository.BatchEdgeByKeyUpsert> edgeBatch = new ArrayList<>();
        Set<String> seenNodeKeys = new HashSet<>();

        Map<String, Object> mapperProps = new HashMap<>();
        mapperProps.put("displayName", mapperName);
        mapperProps.put("description", mapperDesc);
        mapperProps.put("sourceType", SourceType.MYBATIS_XML.name());
        mapperProps.put("sourcePath", mapperFact.getSourcePath());
        mapperProps.put("confidence", 1.0);
        mapperProps.put("status", NodeStatus.CONFIRMED.name());
        nodeBatch.add(new Neo4jWriteRepository.BatchNodeUpsert(
                NodeType.Mapper.name(), mapperKey, mapperName, mapperProps));
        seenNodeKeys.add(mapperKey);

        for (MyBatisXmlExtractor.SqlStatement stmt : mapperFact.getStatements()) {
            String sqlKey = mapperKey + "." + stmt.getId();
            String sqlName = stmt.getType().toUpperCase() + " " + stmt.getId();

            if (!seenNodeKeys.contains(sqlKey)) {
                Map<String, Object> sqlProps = new HashMap<>();
                sqlProps.put("displayName", sqlName);
                sqlProps.put("sourceType", SourceType.MYBATIS_XML.name());
                sqlProps.put("sourcePath", mapperFact.getSourcePath());
                sqlProps.put("startLine", stmt.getStartLine());
                sqlProps.put("endLine", stmt.getEndLine());
                sqlProps.put("confidence", 1.0);
                sqlProps.put("status", NodeStatus.CONFIRMED.name());
                nodeBatch.add(new Neo4jWriteRepository.BatchNodeUpsert(
                        NodeType.SqlStatement.name(), sqlKey, stmt.getId(), sqlProps));
                seenNodeKeys.add(sqlKey);
            }

            edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                    mapperKey, sqlKey, EdgeType.CONTAINS.name(),
                    mapperKey + "->contains->" + sqlKey,
                    edgeProps(SourceType.MYBATIS_XML.name(), BigDecimal.ONE, NodeStatus.CONFIRMED)));

            edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                    mapperKey, sqlKey, EdgeType.EXECUTES.name(),
                    mapperKey + "->executes->" + sqlKey,
                    edgeProps(SourceType.MYBATIS_XML.name(), BigDecimal.ONE, NodeStatus.CONFIRMED)));

            String sqlToParse = (stmt.getExpandedSql() != null && !stmt.getExpandedSql().isBlank())
                    ? stmt.getExpandedSql() : stmt.getSql();
            SqlTableExtractor.SqlTableResult tableResult = new SqlTableExtractor().extractTables(sqlToParse);

            Set<String> allTables = new HashSet<>();
            allTables.addAll(tableResult.getReadTables());
            allTables.addAll(tableResult.getWriteTables());

            for (String readTable : tableResult.getReadTables()) {
                addTableNodeIfAbsent(nodeBatch, seenNodeKeys, projectId, versionId, readTable);
                // P1-2: SqlStatement→Table 使用 READS_DB（业务关键边）
                edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                        sqlKey, readTable, EdgeType.READS_DB.name(),
                        sqlKey + "->reads_db->" + readTable,
                        edgeProps(SourceType.SQL_PARSE.name(), BigDecimal.valueOf(0.95), NodeStatus.CONFIRMED)));
                // 同时保留通用 READS 边以兼容现有查询
                edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                        sqlKey, readTable, EdgeType.READS.name(),
                        sqlKey + "->reads->" + readTable,
                        edgeProps(SourceType.SQL_PARSE.name(), BigDecimal.valueOf(0.95), NodeStatus.CONFIRMED)));
            }

            for (String writeTable : tableResult.getWriteTables()) {
                addTableNodeIfAbsent(nodeBatch, seenNodeKeys, projectId, versionId, writeTable);
                // P1-2: SqlStatement→Table 使用 WRITES_DB（业务关键边）
                edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                        sqlKey, writeTable, EdgeType.WRITES_DB.name(),
                        sqlKey + "->writes_db->" + writeTable,
                        edgeProps(SourceType.SQL_PARSE.name(), BigDecimal.valueOf(0.95), NodeStatus.CONFIRMED)));
                // 同时保留通用 WRITES 边以兼容现有查询
                edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                        sqlKey, writeTable, EdgeType.WRITES.name(),
                        sqlKey + "->writes->" + writeTable,
                        edgeProps(SourceType.SQL_PARSE.name(), BigDecimal.valueOf(0.95), NodeStatus.CONFIRMED)));
            }

            for (String joinTable : tableResult.getJoinTables()) {
                addTableNodeIfAbsent(nodeBatch, seenNodeKeys, projectId, versionId, joinTable);
                edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                        sqlKey, joinTable, EdgeType.JOINS.name(),
                        sqlKey + "->joins->" + joinTable,
                        edgeProps(SourceType.SQL_PARSE.name(), BigDecimal.valueOf(0.95), NodeStatus.CONFIRMED)));
            }

            if (!tableResult.getReadTables().isEmpty() && !tableResult.getWriteTables().isEmpty()) {
                for (String readTable : tableResult.getReadTables()) {
                    for (String writeTable : tableResult.getWriteTables()) {
                        if (readTable.equalsIgnoreCase(writeTable)) {
                            continue;
                        }
                        edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                                readTable, writeTable, EdgeType.DATA_FLOW.name(),
                                readTable + "->data_flow->" + writeTable,
                                edgeProps(SourceType.SQL_PARSE.name(), BigDecimal.valueOf(0.9), NodeStatus.CONFIRMED)));
                    }
                }
            }

            for (String colName : tableResult.getReadColumns()) {
                for (String tbl : allTables) {
                    String colKey = tbl.toLowerCase() + "." + colName.toLowerCase();
                    addColumnNodeIfAbsent(nodeBatch, seenNodeKeys, tbl, colName);
                    edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                            sqlKey, colKey, EdgeType.READS.name(),
                            sqlKey + "->reads->" + tbl + "." + colName,
                            edgeProps(SourceType.SQL_PARSE.name(), BigDecimal.valueOf(0.85), NodeStatus.CONFIRMED)));
                }
            }
            for (String colName : tableResult.getWriteColumns()) {
                for (String tbl : allTables) {
                    String colKey = tbl.toLowerCase() + "." + colName.toLowerCase();
                    addColumnNodeIfAbsent(nodeBatch, seenNodeKeys, tbl, colName);
                    edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                            sqlKey, colKey, EdgeType.WRITES.name(),
                            sqlKey + "->writes->" + tbl + "." + colName,
                            edgeProps(SourceType.SQL_PARSE.name(), BigDecimal.valueOf(0.85), NodeStatus.CONFIRMED)));
                }
            }
        }

        if (!nodeBatch.isEmpty()) {
            neo4jGraphDao.mergeNodesBatch(projectId, versionId, nodeBatch);
        }
        if (!edgeBatch.isEmpty()) {
            neo4jGraphDao.mergeEdgesByKeyBatch(projectId, versionId, edgeBatch);
        }

        for (MyBatisXmlExtractor.SqlStatement stmt : mapperFact.getStatements()) {
            String sqlKey = mapperKey + "." + stmt.getId();
            GraphNode methodNode = findMapperMethodNode(projectId, versionId, mapperKey, stmt.getId());
            if (methodNode != null) {
                neo4jGraphDao.mergeEdgesByKeyBatch(projectId, versionId, List.of(
                        new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                                methodNode.getNodeKey(), sqlKey, EdgeType.EXECUTES.name(),
                                methodNode.getNodeKey() + "->executes->" + sqlKey,
                                edgeProps(SourceType.CODE_AST.name(), BigDecimal.valueOf(0.95), NodeStatus.CONFIRMED))
                ));
            }
        }
    }

    /**
     * 后置连接：在所有适配器执行完成后，重新执行 Method → SqlStatement 的 EXECUTES 边创建。
     * 补偿并发执行时因 Method 节点尚未创建而遗漏的边。
     */
    public int linkMapperMethodsToSqlStatements(String projectId, String versionId) {
        // 1. 一次性加载所有 Method 节点到内存索引（按 FQN 前缀匹配）
        List<GraphNode> allMethods = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Method.name(), null, null, null, 0);
        if (allMethods == null || allMethods.isEmpty()) {
            log.info("Mapper-SQL link: no Method nodes found for versionId={}", versionId);
            return 0;
        }

        // 2. 查询所有 Mapper → SqlStatement 的 CONTAINS 边（Mapper nodeKey = namespace）
        List<GraphEdge> containsEdges = neo4jGraphDao.queryEdges(
                projectId, versionId, EdgeType.CONTAINS.name(), null, 0);
        if (containsEdges == null || containsEdges.isEmpty()) {
            log.info("Mapper-SQL link: no CONTAINS edges found for versionId={}", versionId);
            return 0;
        }

        // 3. 查询已存在的 EXECUTES 边去重
        List<GraphEdge> existingExecutes = neo4jGraphDao.queryEdges(
                projectId, versionId, EdgeType.EXECUTES.name(), null, 0);
        Set<String> existingPairs = new HashSet<>();
        if (existingExecutes != null) {
            for (GraphEdge e : existingExecutes) {
                if (e.getFromNodeId() != null && e.getToNodeId() != null) {
                    existingPairs.add(e.getFromNodeId() + "|" + e.getToNodeId());
                }
            }
        }

        // 4. 构建 namespace+methodId → Method 节点 的索引
        // Method nodeKey 格式: FQN.methodName(paramTypes)，如 com.example.UserMapper.selectById(Long)
        // SqlStatement nodeKey 格式: namespace.statementId，如 com.example.UserMapper.selectById
        // 匹配: Method nodeKey.startsWith(namespace + "." + statementId + "(")
        int linked = 0;
        List<Neo4jWriteRepository.BatchEdgeByKeyUpsert> edgeBatch = new ArrayList<>();

        for (GraphEdge containsEdge : containsEdges) {
            // containsEdge: Mapper -CONTAINS-> SqlStatement
            // 需要 fromNode 是 Mapper，toNode 是 SqlStatement
            // 但 CONTAINS 边也用于 Class→Method 等，需要按 toNode 的 nodeType 过滤
            // 这里通过 toNodeId 查找 SqlStatement 节点
            String sqlNodeId = containsEdge.getToNodeId();
            if (sqlNodeId == null) continue;

            // 查找 SqlStatement 节点（从 fromNode 获取 namespace）
            GraphNode mapperNode = neo4jGraphDao.findNodeById(containsEdge.getFromNodeId()).orElse(null);
            if (mapperNode == null || !NodeType.Mapper.name().equals(mapperNode.getNodeType())) continue;

            String namespace = mapperNode.getNodeKey(); // Mapper nodeKey = namespace (FQN)
            if (namespace == null || namespace.isBlank()) continue;

            // SqlStatement nodeKey = namespace.statementId
            GraphNode sqlNode = neo4jGraphDao.findNodeById(sqlNodeId).orElse(null);
            if (sqlNode == null || !NodeType.SqlStatement.name().equals(sqlNode.getNodeType())) continue;

            String sqlNodeKey = sqlNode.getNodeKey();
            if (sqlNodeKey == null || !sqlNodeKey.startsWith(namespace + ".")) continue;

            String statementId = sqlNodeKey.substring(namespace.length() + 1);

            // 在内存索引中查找 Method 节点
            String prefix = namespace + "." + statementId;
            GraphNode methodNode = null;
            for (GraphNode m : allMethods) {
                String key = m.getNodeKey();
                if (key != null && key.startsWith(prefix + "(")) {
                    methodNode = m;
                    break; // 取第一个匹配
                }
            }
            if (methodNode == null) continue;

            // 去重检查
            String pair = methodNode.getId() + "|" + sqlNode.getId();
            if (existingPairs.contains(pair)) continue;
            existingPairs.add(pair);

            edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                    methodNode.getNodeKey(), sqlNode.getNodeKey(), EdgeType.EXECUTES.name(),
                    methodNode.getNodeKey() + "->executes->" + sqlNode.getNodeKey(),
                    edgeProps(SourceType.CODE_AST.name(), BigDecimal.valueOf(0.95), NodeStatus.CONFIRMED)));
            linked++;
        }

        if (!edgeBatch.isEmpty()) {
            neo4jGraphDao.mergeEdgesByKeyBatch(projectId, versionId, edgeBatch);
        }
        log.info("Mapper-SQL link: {} new EXECUTES edges created for versionId={}", linked, versionId);
        return linked;
    }

    private Map<String, Object> edgeProps(String sourceType, BigDecimal confidence, NodeStatus status) {
        Map<String, Object> props = new HashMap<>();
        props.put("sourceType", sourceType);
        props.put("confidence", confidence.doubleValue());
        props.put("status", status.name());
        return props;
    }

    private void addTableNodeIfAbsent(List<Neo4jWriteRepository.BatchNodeUpsert> batch, Set<String> seen,
                                       String projectId, String versionId, String tableName) {
        if (seen.contains(tableName)) {
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("displayName", tableName);
        props.put("sourceType", SourceType.SQL_PARSE.name());
        props.put("confidence", 1.0);
        props.put("status", NodeStatus.CONFIRMED.name());
        batch.add(new Neo4jWriteRepository.BatchNodeUpsert(
                NodeType.Table.name(), tableName, tableName, props));
        seen.add(tableName);
    }

    private void addColumnNodeIfAbsent(List<Neo4jWriteRepository.BatchNodeUpsert> batch, Set<String> seen,
                                        String tableName, String colName) {
        String colKey = tableName.toLowerCase() + "." + colName.toLowerCase();
        if (seen.contains(colKey)) {
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("displayName", colName);
        props.put("sourceType", SourceType.SQL_PARSE.name());
        props.put("confidence", 1.0);
        props.put("status", NodeStatus.CONFIRMED.name());
        batch.add(new Neo4jWriteRepository.BatchNodeUpsert(
                NodeType.Column.name(), colKey, colName, props));
        seen.add(colKey);
    }

    /**
     * 构建数据库表和字段图谱。
     * <p>使用批量 UNWIND MERGE 替代逐条 MERGE，将 1000+ 次 Neo4j 往返压缩为 ~5 次。</p>
     */
    public void buildDatabaseGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.DatabaseMetadataExtractor.TableMetadata> tables) {
        if (tables == null || tables.isEmpty()) return;

        // 构建表名映射，用于启发式推断
        Map<String, String> tableNameToKey = new HashMap<>();
        for (var tableMeta : tables) {
            String tableKey = (tableMeta.getTableSchema() + "." + tableMeta.getTableName()).toLowerCase();
            tableNameToKey.put(tableMeta.getTableName().toLowerCase(), tableKey);
            // 也存储不带 schema 的映射
            tableNameToKey.put(tableMeta.getTableName().toLowerCase(), tableKey);
        }

        // 第一遍：创建所有表节点和字段节点
        Map<String, GraphNode> tableNodes = new HashMap<>();
        int nodeCount = 0, edgeCount = 0;
        for (var tableMeta : tables) {
            String tableKey = (tableMeta.getTableSchema() + "." + tableMeta.getTableName()).toLowerCase();

            // 表节点
            GraphNode tableNode = writer.upsertNode(GraphNodeClaim.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .nodeType(NodeType.Table.name())
                    .nodeKey(tableKey)
                    .nodeName(tableMeta.getTableName())
                    .displayName(tableMeta.getTableName())
                    .description(tableMeta.getTableComment())
                    .sourceType(SourceType.DB_METADATA.name())
                    .sourcePath(null)
                    .confidence(BigDecimal.ONE)
                    .status(NodeStatus.CONFIRMED.name())
                    .scanType("DATABASE_SCAN")
                    .build());
            tableNodes.put(tableKey, tableNode);
            nodeCount++;

            // 字段节点 + HAS_COLUMN 边
            for (var colMeta : tableMeta.getColumns()) {
                String colKey = tableMeta.getTableName().toLowerCase() + "." + colMeta.getColumnName().toLowerCase();

                GraphNode colNode = writer.upsertNode(GraphNodeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .nodeType(NodeType.Column.name())
                        .nodeKey(colKey)
                        .nodeName(colMeta.getColumnName())
                        .displayName(colMeta.getColumnName())
                        .description(colMeta.getColumnComment())
                        .sourceType(SourceType.DB_METADATA.name())
                        .sourcePath(null)
                        .confidence(BigDecimal.ONE)
                        .status(NodeStatus.CONFIRMED.name())
                        .scanType("DATABASE_SCAN")
                        .properties(buildColumnProperties(colMeta))
                        .build());
                nodeCount++;

                // HAS_COLUMN 边
                writer.upsertEdge(GraphEdgeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .fromNodeId(tableNode.getId())
                        .toNodeId(colNode.getId())
                        .edgeType(EdgeType.HAS_COLUMN.name())
                        .edgeKey(tableKey + "->has_column->" + colKey)
                        .sourceType(SourceType.DB_METADATA.name())
                        .confidence(BigDecimal.ONE)
                        .status(NodeStatus.CONFIRMED.name())
                        .build());
                edgeCount++;
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
                    edgeCount++;
                }
                // 2. 启发式推断：列名模式 {table}_id, {table}Id, parent_id
                else if (!Boolean.TRUE.equals(colMeta.getForeignKey())) {
                    String inferredTable = inferReferencedTable(colMeta.getColumnName(), tableMeta.getTableName(), tableNameToKey);
                    if (inferredTable != null) {
                        GraphNode refTable = tableNodes.get(inferredTable);
                        if (refTable != null) {
                            String fkEdgeKey = tableKey + "->references->" + inferredTable + "." + colMeta.getColumnName();
                            createEdge(projectId, versionId,
                                    tableNode.getId(), refTable.getId(),
                                    EdgeType.REFERENCES.name(),
                                    fkEdgeKey,
                                    SourceType.DB_METADATA.name(),
                                    BigDecimal.valueOf(0.7),
                                    NodeStatus.PENDING_CONFIRM);
                            inferredFkCount++;
                            edgeCount++;
                        }
                    }
                }
            }
        }

        log.info("Built DB graph with evidence: {} nodes, {} edges (real FK: {}, inferred FK: {}, projectId={}, versionId={})",
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
        // 基于列名和 semanticType 推断敏感字段
        boolean sensitive = isSensitiveColumn(colMeta.getColumnName(), colMeta.getSemanticType());
        properties.put("sensitive", sensitive);
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

    /** 基于列名和语义类型推断是否为敏感字段 */
    private boolean isSensitiveColumn(String columnName, String semanticType) {
        if (columnName == null) return false;
        String lower = columnName.toLowerCase();
        // 1. 语义类型标记为 PII 的
        if ("pii".equalsIgnoreCase(semanticType) || "sensitive".equalsIgnoreCase(semanticType)) {
            return true;
        }
        // 2. 列名匹配敏感字段模式
        return lower.matches(".*(password|passwd|pwd|secret|token|credential|private_key|privatekey|身份证|手机号|phone|mobile|email|mail|id_card|idcard|bank_card|bankcard|salary|wages).*");
    }

    private void putIfNotNull(Map<String, Object> properties, String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    private String toJsonProperties(Map<String, Object> properties, String context) {
        if (properties == null || properties.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(properties);
        } catch (Exception e) {
            log.debug("Failed to serialize properties for {}: {}", context, e.getMessage());
            return null;
        }
    }

    /**
     * P1-1: 合并节点属性模板与调用方传入的 properties。
     * <p>
     * 策略：先填充模板属性（值为 null），再解析 existingPropertiesJson 覆盖到模板上，
     * 确保核心节点类型的必备属性键始终存在，同时保留调用方传入的实际值。
     * </p>
     *
     * @param nodeType            节点类型名称
     * @param existingPropertiesJson 已有的 properties JSON（可为 null）
     * @return 合并后的 properties JSON；非核心节点类型且无 existingPropertiesJson 时返回 null
     */
    private String mergeTemplateProperties(String nodeType, String existingPropertiesJson) {
        Map<String, Object> template = NodeAttributeTemplates.requiredFor(nodeType);
        if (template.isEmpty() && (existingPropertiesJson == null || existingPropertiesJson.isBlank())) {
            return null;
        }
        // 以模板为基础，合并已有属性（已有值覆盖模板 null）
        Map<String, Object> merged = new LinkedHashMap<>(template);
        if (existingPropertiesJson != null && !existingPropertiesJson.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = OBJECT_MAPPER.readValue(existingPropertiesJson, Map.class);
                merged.putAll(existing);
            } catch (Exception e) {
                log.debug("Failed to parse existing properties for {}: {}", nodeType, e.getMessage());
                // 解析失败时保留模板属性
            }
        }
        return toJsonProperties(merged, nodeType);
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
                        String columnKey = idx.getTableName().toLowerCase() + "." + columnName.toLowerCase();
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
     * <p>P1-1：对五类核心节点（Controller/Service/Mapper/ApiEndpoint/Table）自动回填模板属性。</p>
     */
    private GraphNode findOrCreateNode(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status,
            String scanType, String className) {
        // P1-1: 为核心节点类型生成模板属性
        String templateProps = mergeTemplateProperties(nodeType, null);
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
                .properties(templateProps)
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
     * 查找或创建节点（带 properties）。
     * <p>P1-1：properties 会与 {@link NodeAttributeTemplates} 模板属性合并，
     * 确保核心节点类型的必备属性键不丢失（调用方传入的值优先）。</p>
     */
    private GraphNode findOrCreateNodeWithProperties(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status,
            String scanType, String className, String properties) {
        // P1-1: 合并模板属性与调用方传入的 properties
        String mergedProps = mergeTemplateProperties(nodeType, properties);
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
                .properties(mergedProps)
                .build());
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
        for (GraphNode t : tables != null ? tables : java.util.Collections.<GraphNode>emptyList()) {
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
                        .limit(MAX_FILES_PER_EXTRACT)
                        .forEach(javaFiles::add);
            }

            // 复用单实例避免 Metaspace 膨胀；先读内容再解析避免文件 I/O 卡在 parse 内
            com.github.javaparser.JavaParser parser = new com.github.javaparser.JavaParser(
                    new com.github.javaparser.ParserConfiguration()
                            .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17));

            for (Path javaFile : javaFiles) {
                com.github.javaparser.ast.CompilationUnit cu = null;
                try {
                    String code = readFileSafely(javaFile);
                    if (code == null) continue;
                    cu = parser.parse(code).getResult().orElse(null);
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
                                String colKey = tableName.toLowerCase() + "." + colName.toLowerCase();
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
                                        tableName.toLowerCase() + "->has_column->" + colKey,
                                        "CODE_ENTITY",
                                        BigDecimal.valueOf(0.85),
                                        NodeStatus.CONFIRMED);
                                totalCols++;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Entity extract skipped {}: {}", javaFile.getFileName(), e.getMessage());
                } finally {
                    // 显式释放 AST 树（Position/Range/JavaToken 等），降低与 AI 抽取并发时的堆峰值
                    cu = null;
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
        for (GraphNode t : tables != null ? tables : java.util.Collections.<GraphNode>emptyList()) {
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
                        .limit(MAX_FILES_PER_EXTRACT).forEach(xmlFiles::add);
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

                            String colKey = tableName.toLowerCase() + "." + colName.toLowerCase();
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
        for (GraphNode t : tables != null ? tables : java.util.Collections.<GraphNode>emptyList()) {
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
                        .limit(MAX_FILES_PER_EXTRACT).forEach(javaFiles::add);
            }

            for (Path f : javaFiles) {
                try {
                    String content = readFileSafely(f);
                    var m = jdbcPat.matcher(content);
                    while (m.find()) {
                        String colName = m.group(1).toLowerCase();
                        // 无法从 JDBC 调用直接推断表名 → 标记为 unknown 表
                        String tableName = "unknown";
                        String colKey = tableName.toLowerCase() + "." + colName;
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
                }).limit(MAX_FILES_PER_EXTRACT).forEach(htmlFiles::add);
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
                stream.filter(f -> f.toString().endsWith(".java")).limit(MAX_FILES_PER_EXTRACT).forEach(javaFiles::add);
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
                stream.filter(f -> f.toString().endsWith(".java")).limit(MAX_FILES_PER_EXTRACT).forEach(javaFiles::add);
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
                }).limit(MAX_FILES_PER_EXTRACT).forEach(htmlFiles::add);
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
    /** 单次提取最大文件数（超过则截断，防止大仓库提取 OOM） */
    private static final int MAX_FILES_PER_EXTRACT = 200;

    private static String readFileSafely(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_CHARS) return null;
            return Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
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
    public List<GraphNode> buildJavaStructureGraph(String projectId, String versionId,
            List<JavaStructureExtractor.JavaClassInfo> classes) {
        List<GraphNode> nodes = new ArrayList<>();
        if (classes == null || classes.isEmpty()) {
            return nodes;
        }

        List<Neo4jWriteRepository.BatchNodeUpsert> nodeBatch = new ArrayList<>();
        List<Neo4jWriteRepository.BatchEdgeByKeyUpsert> edgeBatch = new ArrayList<>();
        Set<String> seenNodeKeys = new HashSet<>();

        // 第一遍：收集所有节点和 CONTAINS 边，批量写入
        for (JavaStructureExtractor.JavaClassInfo classInfo : classes) {
            if (classInfo.getQualifiedName() == null || classInfo.getQualifiedName().isBlank()) {
                continue;
            }
            NodeType classNodeType = inferNodeType(classInfo.getQualifiedName());
            String classKey = classInfo.getQualifiedName();
            String inheritProps = buildInheritProperties(classInfo);

            if (!seenNodeKeys.contains(classKey)) {
                Map<String, Object> props = new HashMap<>();
                props.put("displayName", classInfo.getClassName());
                props.put("sourceType", SourceType.CODE_AST.name());
                props.put("sourcePath", classInfo.getSourcePath());
                props.put("startLine", classInfo.getStartLine());
                props.put("endLine", classInfo.getEndLine());
                props.put("confidence", 1.0);
                props.put("status", NodeStatus.CONFIRMED.name());
                props.put("scanType", "CODE_SCAN");
                props.put("className", classInfo.getQualifiedName());
                if (inheritProps != null) {
                    props.put("properties", inheritProps);
                }
                nodeBatch.add(new Neo4jWriteRepository.BatchNodeUpsert(
                        classNodeType.name(), classKey, classInfo.getClassName(), props));
                seenNodeKeys.add(classKey);
            }

            if (classInfo.getMethods() != null) {
                for (JavaStructureExtractor.JavaMethodInfo methodInfo : classInfo.getMethods()) {
                    if (methodInfo.getQualifiedName() == null || methodInfo.getQualifiedName().isBlank()) {
                        continue;
                    }
                    String methodKey = methodInfo.getQualifiedName();
                    if (!seenNodeKeys.contains(methodKey)) {
                        Map<String, Object> mProps = new HashMap<>();
                        mProps.put("displayName", methodInfo.getMethodName());
                        mProps.put("sourceType", SourceType.CODE_AST.name());
                        mProps.put("sourcePath", classInfo.getSourcePath());
                        mProps.put("startLine", methodInfo.getStartLine());
                        mProps.put("endLine", methodInfo.getEndLine());
                        mProps.put("confidence", 1.0);
                        mProps.put("status", NodeStatus.CONFIRMED.name());
                        mProps.put("scanType", "CODE_SCAN");
                        mProps.put("className", classInfo.getQualifiedName());
                        nodeBatch.add(new Neo4jWriteRepository.BatchNodeUpsert(
                                NodeType.Method.name(), methodKey, methodInfo.getMethodName(), mProps));
                        seenNodeKeys.add(methodKey);
                    }
                    edgeBatch.add(new Neo4jWriteRepository.BatchEdgeByKeyUpsert(
                            classKey, methodKey, EdgeType.CONTAINS.name(),
                            classKey + "->contains->" + methodKey,
                            edgeProps(SourceType.CODE_AST.name(), BigDecimal.ONE, NodeStatus.CONFIRMED)));
                }
            }
        }

        if (!nodeBatch.isEmpty()) {
            neo4jGraphDao.mergeNodesBatch(projectId, versionId, nodeBatch);
        }
        if (!edgeBatch.isEmpty()) {
            neo4jGraphDao.mergeEdgesByKeyBatch(projectId, versionId, edgeBatch);
        }

        // 第二遍：EXTENDS/IMPLEMENTS 边需要查找已存在节点，保持逐条
        for (JavaStructureExtractor.JavaClassInfo classInfo : classes) {
            if (classInfo.getQualifiedName() == null || classInfo.getQualifiedName().isBlank()) {
                continue;
            }
            NodeType classNodeType = inferNodeType(classInfo.getQualifiedName());
            String classKey = classInfo.getQualifiedName();
            GraphNode classNode = neo4jGraphDao.findNode(projectId, versionId,
                    classNodeType.name(), classKey).orElse(null);
            if (classNode == null) {
                continue;
            }
            nodes.add(classNode);

            if (classInfo.getExtendedTypes() != null) {
                for (String parentSimple : classInfo.getExtendedTypes()) {
                    GraphNode parentNode = findClassNodeBySimpleName(
                            projectId, versionId, parentSimple);
                    if (parentNode != null && !parentNode.getId().equals(classNode.getId())) {
                        createEdge(projectId, versionId,
                                classNode.getId(), parentNode.getId(),
                                EdgeType.EXTENDS.name(),
                                classKey + "->extends->" + parentNode.getNodeKey(),
                                SourceType.CODE_AST.name(),
                                BigDecimal.ONE,
                                NodeStatus.CONFIRMED
                        );
                    }
                }
            }
            if (classInfo.getImplementedTypes() != null) {
                for (String ifaceSimple : classInfo.getImplementedTypes()) {
                    GraphNode ifaceNode = findClassNodeBySimpleName(
                            projectId, versionId, ifaceSimple);
                    if (ifaceNode != null && !ifaceNode.getId().equals(classNode.getId())) {
                        createEdge(projectId, versionId,
                                classNode.getId(), ifaceNode.getId(),
                                EdgeType.IMPLEMENTS.name(),
                                classKey + "->implements->" + ifaceNode.getNodeKey(),
                                SourceType.CODE_AST.name(),
                                BigDecimal.ONE,
                                NodeStatus.CONFIRMED
                        );
                    }
                }
            }

            // P1-3: 嵌套类 → 创建 OuterClass -CONTAINS-> InnerClass 边
            if (classInfo.isNested() && classInfo.getOuterQualifiedName() != null) {
                NodeType outerNodeType = inferNodeType(classInfo.getOuterQualifiedName());
                GraphNode outerNode = neo4jGraphDao.findNode(projectId, versionId,
                        outerNodeType.name(), classInfo.getOuterQualifiedName()).orElse(null);
                if (outerNode != null && !outerNode.getId().equals(classNode.getId())) {
                    createEdge(projectId, versionId,
                            outerNode.getId(), classNode.getId(),
                            EdgeType.CONTAINS.name(),
                            classInfo.getOuterQualifiedName() + "->contains->" + classKey,
                            SourceType.CODE_AST.name(),
                            BigDecimal.ONE,
                            NodeStatus.CONFIRMED
                    );
                }
            }
        }
        return nodes;
    }

    /**
     * 构建Service调用关系图谱
     * 根据抽取的调用关系创建 CALLS 边连接调用方和被调用方节点
     */
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
                // P1-2: 根据 edgeTargetKind 选择业务关键边类型
                String edgeTypeStr = resolveCallEdgeType(call.getEdgeTargetKind(), targetNodeType);
                String edgeKey = callerClassKey + "->" + edgeTypeStr.toLowerCase() + "->" + targetClassKey + "." + call.getTargetMethod();
                createEdge(projectId, versionId,
                        callerNode.getId(), targetNode.getId(),
                        edgeTypeStr,
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
     * 按简单名在 Controller/Service/Mapper 节点中查找（用于继承/实现边解析）。
     * 只在当前已入库的节点中 find-only，不创建新节点。
     */
    private GraphNode findClassNodeBySimpleName(String projectId, String versionId, String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return null;
        }
        for (NodeType t : new NodeType[]{NodeType.Controller, NodeType.Service, NodeType.Mapper, NodeType.ConfigItem, NodeType.ExternalSystem}) {
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, t.name(), null, null, null, 0);
            if (nodes == null) continue;
            for (GraphNode n : nodes) {
                String key = n.getNodeKey();
                if (key != null && key.endsWith("." + simpleName)) {
                    return n;
                }
                if (simpleName.equals(n.getNodeName())) {
                    return n;
                }
            }
        }
        return null;
    }

    /**
     * 构建继承属性 JSON，保存 extendedTypes 和 implementedTypes 供 resolver 二次解析使用。
     */
    private String buildInheritProperties(JavaStructureExtractor.JavaClassInfo classInfo) {
        try {
            Map<String, Object> props = new HashMap<>();
            if (classInfo.getExtendedTypes() != null && !classInfo.getExtendedTypes().isEmpty()) {
                props.put("extendedTypes", classInfo.getExtendedTypes());
            }
            if (classInfo.getImplementedTypes() != null && !classInfo.getImplementedTypes().isEmpty()) {
                props.put("implementedTypes", classInfo.getImplementedTypes());
            }
            if (props.isEmpty()) {
                return null;
            }
            return OBJECT_MAPPER.writeValueAsString(props);
        } catch (Exception e) {
            log.debug("Failed to serialize inherit properties for {}: {}", classInfo.getQualifiedName(), e.getMessage());
            return null;
        }
    }

    /**
     * 查找 Mapper 接口方法节点 —— 按 namespace + methodId 匹配。
     * Method nodeKey 格式为 FQN.methodName(paramTypes)，如 com.example.UserMapper.selectById(Long)
     * 先精确匹配（无参数方法），再按方法名前缀模糊匹配（有参数方法）。
     */
    private GraphNode findMapperMethodNode(String projectId, String versionId, String namespace, String methodId) {
        if (namespace == null || namespace.isBlank() || methodId == null || methodId.isBlank()) {
            return null;
        }
        String prefix = namespace + "." + methodId;
        List<GraphNode> methodNodes = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Method.name(), null, null, null, 0);
        if (methodNodes == null || methodNodes.isEmpty()) {
            return null;
        }
        List<GraphNode> candidates = new ArrayList<>();
        for (GraphNode m : methodNodes) {
            String key = m.getNodeKey();
            if (key == null) continue;
            if (key.startsWith(prefix + "(")) {
                candidates.add(m);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            for (GraphNode c : candidates) {
                if (methodId.equals(c.getNodeName())) {
                    return c;
                }
            }
        }
        return null;
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
     * P1-2: 根据调用目标类型选择业务关键边类型。
     * <p>
     * 优先使用 edgeTargetKind（由 ServiceCallExtractor 标注），
     * 未标注时根据 targetNodeType 推断。
     * </p>
     *
     * @param edgeTargetKind 调用目标类型（SERVICE_CALL / DATABASE_CALL / LOG_CALL / CONFIG_CALL / ENDPOINT_EXPOSE）
     * @param targetNodeType 被调用方节点类型
     * @return EdgeType 名称
     */
    private String resolveCallEdgeType(String edgeTargetKind, NodeType targetNodeType) {
        if (edgeTargetKind != null && !edgeTargetKind.isBlank()) {
            return switch (edgeTargetKind.toUpperCase()) {
                case "DATABASE_CALL" -> EdgeType.CALLS_DB.name();
                case "LOG_CALL" -> EdgeType.WRITES_LOG.name();
                case "CONFIG_CALL" -> EdgeType.READS_CONFIG.name();
                case "ENDPOINT_EXPOSE" -> EdgeType.EXPOSES_ENDPOINT.name();
                default -> EdgeType.CALLS.name();
            };
        }
        // 未标注时按目标节点类型推断
        if (targetNodeType == NodeType.Mapper) {
            return EdgeType.CALLS_DB.name();
        }
        return EdgeType.CALLS.name();
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

    /**
     * 为指定类构建 Method 节点查找表（methodName → GraphNode）。
     * <p>查询图谱中 nodeKey 以 {@code classKey + "."} 开头的 Method 节点，
     * 按 nodeName（方法名）建立索引。同一方法名可能对应多个重载，
     * 取第一个（方法级 VERIFIED_BY 只需知道方法存在即可，不区分钟重载）。</p>
     */
    private Map<String, GraphNode> buildMethodLookupForClass(String projectId, String versionId, String classKey) {
        Map<String, GraphNode> lookup = new HashMap<>();
        try {
            List<GraphNode> methods = neo4jGraphDao.queryNodes(
                    projectId, versionId, NodeType.Method.name(),
                    null, null, null, 500);
            String prefix = classKey + ".";
            for (GraphNode m : methods) {
                String key = m.getNodeKey();
                if (key != null && key.startsWith(prefix)) {
                    String methodName = m.getNodeName();
                    if (methodName != null && !methodName.isBlank()) {
                        lookup.putIfAbsent(methodName, m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to build method lookup for class {}: {}", classKey, e.getMessage());
        }
        return lookup;
    }

    /**
     * 将测试内调用的被测方法匹配到图谱中的 Method 节点。
     * <p>按方法名直接匹配（lookup 已按 testedClassKey 过滤，只含该类的方法）。</p>
     */
    private GraphNode matchMethodNode(Map<String, GraphNode> methodLookup,
                                        io.github.legacygraph.extractors.TestCaseExtractor.InvokedMethodCall invokedCall) {
        if (methodLookup == null || methodLookup.isEmpty()) return null;
        String methodName = invokedCall.getMethodName();
        if (methodName == null || methodName.isBlank()) return null;
        return methodLookup.get(methodName);
    }

    // ==================== 配置项图谱构建 ====================

    /**
     * 构建配置项图谱：ConfigItem 节点 + Class USES ConfigItem 边
     */
    public void buildConfigItemGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.ConfigItemExtractor.ConfigItemFact> configItems) {
        if (configItems == null || configItems.isEmpty()) return;

        int nodeCount = 0, edgeCount = 0;

        for (var item : configItems) {
            String configKey = "config:" + item.getKey();

            // ConfigItem 节点
            Map<String, Object> configProps = new LinkedHashMap<>();
            putIfNotNull(configProps, "value", item.getValue());
            putIfNotNull(configProps, "defaultValue", item.getDefaultValue());
            putIfNotNull(configProps, "className", item.getClassName());
            putIfNotNull(configProps, "fieldName", item.getFieldName());
            String configPropertiesJson = toJsonProperties(configProps, configKey);
            GraphNode configNode = writer.upsertNode(GraphNodeClaim.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .nodeType(NodeType.ConfigItem.name())
                    .nodeKey(configKey)
                    .nodeName(item.getKey())
                    .displayName(item.getKey())
                    .description(item.getSourceType())
                    .sourceType(SourceType.CODE_AST.name())
                    .sourcePath(item.getSourcePath())
                    .confidence(BigDecimal.ONE)
                    .status(NodeStatus.CONFIRMED.name())
                    .scanType("CODE_SCAN")
                    .properties(configPropertiesJson)
                    .build());
            nodeCount++;

            // Class -> ConfigItem USES 边
            if (item.getClassName() != null && !item.getClassName().isBlank()) {
                String classKey = item.getClassName();
                GraphNode classNode = findExistingNode(projectId, versionId,
                        NodeType.Service.name(), classKey);
                if (classNode != null) {
                    createEdge(projectId, versionId,
                            classNode.getId(), configNode.getId(),
                            EdgeType.USES.name(),
                            classKey + "->uses->" + configKey,
                            SourceType.CODE_AST.name(),
                            BigDecimal.ONE, NodeStatus.CONFIRMED);
                    edgeCount++;
                }
            }
        }

        log.info("Built config graph with evidence: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 定时任务图谱构建 ====================

    /**
     * 构建定时任务图谱：ScheduledJob 节点 + HANDLED_BY Method 边
     */
    public void buildScheduledJobGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.ScheduledJobExtractor.ScheduledJobFact> jobs) {
        if (jobs == null || jobs.isEmpty()) return;

        int nodeCount = 0, edgeCount = 0;

        for (var job : jobs) {
            String jobKey = "job:" + job.getClassName() + "." + job.getMethodName();

            // ScheduledJob 节点
            String description = job.getCronExpression() != null
                    ? "Cron: " + job.getCronExpression()
                    : "Fixed: " + job.getFixedDelay() + "ms";
            GraphNode jobNode = writer.upsertNode(GraphNodeClaim.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .nodeType(NodeType.ScheduledJob.name())
                    .nodeKey(jobKey)
                    .nodeName(job.getMethodName())
                    .displayName(job.getClassName() + "." + job.getMethodName())
                    .description(description)
                    .sourceType(SourceType.CODE_AST.name())
                    .sourcePath(job.getSourcePath())
                    .startLine(job.getStartLine())
                    .endLine(job.getEndLine())
                    .confidence(BigDecimal.ONE)
                    .status(NodeStatus.CONFIRMED.name())
                    .scanType("CODE_SCAN")
                    .build());
            nodeCount++;

            if (job.getClassName() != null && job.getMethodName() != null) {
                String methodKey = buildMethodKey(job.getClassName(), job.getMethodName(), job.getMethodSignature());
                GraphNode methodNode = writer.upsertNode(GraphNodeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .nodeType(NodeType.Method.name())
                        .nodeKey(methodKey)
                        .nodeName(job.getMethodName())
                        .displayName(job.getMethodName())
                        .description("定时任务处理方法")
                        .sourceType(SourceType.CODE_AST.name())
                        .sourcePath(job.getSourcePath())
                        .startLine(job.getStartLine())
                        .endLine(job.getEndLine())
                        .confidence(BigDecimal.ONE)
                        .status(NodeStatus.CONFIRMED.name())
                        .scanType("CODE_SCAN")
                        .className(job.getClassName())
                        .build());
                nodeCount++;

                createEdge(projectId, versionId,
                        jobNode.getId(), methodNode.getId(),
                        EdgeType.HANDLED_BY.name(),
                        jobKey + "->handled_by->" + methodKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED);
                edgeCount++;
            }
        }

        log.info("Built scheduled job graph with evidence: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 消息队列图谱构建 ====================

    /**
     * 构建消息队列图谱：MQConsumer / MQTopic 节点 + 方法级处理与触发边
     */
    public void buildMQGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.MQExtractor.MQConsumerFact> consumers) {
        if (consumers == null || consumers.isEmpty()) return;

        int nodeCount = 0, edgeCount = 0;

        for (var consumer : consumers) {
            String topicName = consumer.getTopic() != null ? consumer.getTopic() : "unknown";
            String consumerKey = "mq-consumer:" + consumer.getAnnotationType() + ":"
                    + nullToUnknown(consumer.getClassName()) + "."
                    + nullToUnknown(consumer.getMethodName()) + ":" + topicName;

            GraphNode consumerNode = writer.upsertNode(GraphNodeClaim.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .nodeType(NodeType.MQConsumer.name())
                    .nodeKey(consumerKey)
                    .nodeName(topicName)
                    .displayName(topicName)
                    .description(consumer.getAnnotationType() + " 消息消费者")
                    .sourceType(SourceType.CODE_AST.name())
                    .sourcePath(consumer.getSourcePath())
                    .startLine(consumer.getStartLine())
                    .endLine(consumer.getEndLine())
                    .confidence(BigDecimal.ONE)
                    .status(NodeStatus.CONFIRMED.name())
                    .scanType("CODE_SCAN")
                    .build());
            nodeCount++;

            String topicKey = "mq-topic:" + topicName;
            GraphNode topicNode = writer.upsertNode(GraphNodeClaim.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .nodeType(NodeType.MQTopic.name())
                    .nodeKey(topicKey)
                    .nodeName(topicName)
                    .displayName(topicName)
                    .description(consumer.getAnnotationType() + " 消息主题")
                    .sourceType(SourceType.CODE_AST.name())
                    .sourcePath(consumer.getSourcePath())
                    .confidence(BigDecimal.ONE)
                    .status(NodeStatus.CONFIRMED.name())
                    .scanType("CODE_SCAN")
                    .build());
            nodeCount++;

            createEdge(projectId, versionId,
                    consumerNode.getId(), topicNode.getId(),
                    EdgeType.CONSUMES.name(),
                    consumerKey + "->consumes->" + topicKey,
                    SourceType.CODE_AST.name(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED);
            edgeCount++;

            if (consumer.getClassName() != null && consumer.getMethodName() != null) {
                String methodKey = buildMethodKey(consumer.getClassName(), consumer.getMethodName(), consumer.getMethodSignature());
                GraphNode methodNode = writer.upsertNode(GraphNodeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .nodeType(NodeType.Method.name())
                        .nodeKey(methodKey)
                        .nodeName(consumer.getMethodName())
                        .displayName(consumer.getMethodName())
                        .description("消息消费处理方法")
                        .sourceType(SourceType.CODE_AST.name())
                        .sourcePath(consumer.getSourcePath())
                        .startLine(consumer.getStartLine())
                        .endLine(consumer.getEndLine())
                        .confidence(BigDecimal.ONE)
                        .status(NodeStatus.CONFIRMED.name())
                        .scanType("CODE_SCAN")
                        .className(consumer.getClassName())
                        .build());
                nodeCount++;

                createEdge(projectId, versionId,
                        consumerNode.getId(), methodNode.getId(),
                        EdgeType.HANDLED_BY.name(),
                        consumerKey + "->handled_by->" + methodKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED);
                edgeCount++;

                createEdge(projectId, versionId,
                        methodNode.getId(), topicNode.getId(),
                        EdgeType.TRIGGERS.name(),
                        methodKey + "->triggers->" + topicKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED);
                edgeCount++;
            }
        }

        log.info("Built MQ graph with evidence: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    // ==================== 外部系统图谱构建 ====================

    /**
     * 构建外部系统图谱：ExternalSystem / 外部 ApiEndpoint 节点 + CALLS_EXTERNAL 边
     */
    public void buildExternalSystemGraph(String projectId, String versionId,
            List<io.github.legacygraph.extractors.ExternalSystemExtractor.ExternalCallFact> calls) {
        if (calls == null || calls.isEmpty()) return;

        int nodeCount = 0, edgeCount = 0;

        for (var call : calls) {
            String edgeKeySuffix = call.getServiceName() != null ? call.getServiceName() : call.getBaseUrl();
            String systemKey = "ext:" + call.getClientType() + ":" + (edgeKeySuffix != null ? edgeKeySuffix : "unknown");

            // ExternalSystem 节点
            GraphNode systemNode = writer.upsertNode(GraphNodeClaim.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .nodeType(NodeType.ExternalSystem.name())
                    .nodeKey(systemKey)
                    .nodeName(edgeKeySuffix)
                    .displayName(edgeKeySuffix)
                    .description(call.getClientType() + " 外部调用")
                    .sourceType(SourceType.CODE_AST.name())
                    .sourcePath(call.getSourcePath())
                    .startLine(call.getStartLine())
                    .endLine(call.getEndLine())
                    .confidence(BigDecimal.ONE)
                    .status(NodeStatus.CONFIRMED.name())
                    .scanType("CODE_SCAN")
                    .build());
            nodeCount++;

            if (call.getBaseUrl() != null && !call.getBaseUrl().isBlank()) {
                String apiKey = "external:" + call.getBaseUrl();
                GraphNode externalApiNode = writer.upsertNode(GraphNodeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .nodeType(NodeType.ApiEndpoint.name())
                        .nodeKey(apiKey)
                        .nodeName(call.getBaseUrl())
                        .displayName(call.getBaseUrl())
                        .description("外部 API: " + call.getBaseUrl())
                        .sourceType(SourceType.CODE_AST.name())
                        .sourcePath(call.getSourcePath())
                        .startLine(call.getStartLine())
                        .endLine(call.getEndLine())
                        .confidence(BigDecimal.ONE)
                        .status(NodeStatus.CONFIRMED.name())
                        .scanType("CODE_SCAN")
                        .build());
                nodeCount++;

                createEdge(projectId, versionId,
                        systemNode.getId(), externalApiNode.getId(),
                        EdgeType.CALLS_EXTERNAL.name(),
                        systemKey + "->calls_external->" + apiKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED);
                edgeCount++;
            }

            if (call.getClassName() != null && call.getMethodName() != null) {
                String methodKey = buildMethodKey(call.getClassName(), call.getMethodName(), call.getMethodSignature());
                GraphNode methodNode = writer.upsertNode(GraphNodeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .nodeType(NodeType.Method.name())
                        .nodeKey(methodKey)
                        .nodeName(call.getMethodName())
                        .displayName(call.getMethodName())
                        .description("外部系统调用方法")
                        .sourceType(SourceType.CODE_AST.name())
                        .sourcePath(call.getSourcePath())
                        .startLine(call.getStartLine())
                        .endLine(call.getEndLine())
                        .confidence(BigDecimal.ONE)
                        .status(NodeStatus.CONFIRMED.name())
                        .scanType("CODE_SCAN")
                        .className(call.getClassName())
                        .build());
                nodeCount++;

                createEdge(projectId, versionId,
                        methodNode.getId(), systemNode.getId(),
                        EdgeType.CALLS_EXTERNAL.name(),
                        methodKey + "->calls_external->" + systemKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED);
                edgeCount++;
            } else if (call.getClassName() != null) {
                NodeType classNodeType = inferNodeType(call.getClassName());
                GraphNode classNode = writer.upsertNode(GraphNodeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .nodeType(classNodeType.name())
                        .nodeKey(call.getClassName())
                        .nodeName(call.getClassName().substring(call.getClassName().lastIndexOf('.') + 1))
                        .displayName(call.getClassName().substring(call.getClassName().lastIndexOf('.') + 1))
                        .description(null)
                        .sourceType(SourceType.CODE_AST.name())
                        .sourcePath(call.getSourcePath())
                        .confidence(BigDecimal.ONE)
                        .status(NodeStatus.CONFIRMED.name())
                        .scanType("CODE_SCAN")
                        .build());
                nodeCount++;

                createEdge(projectId, versionId,
                        classNode.getId(), systemNode.getId(),
                        EdgeType.CALLS_EXTERNAL.name(),
                        call.getClassName() + "->calls_external->" + systemKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED);
                edgeCount++;
            }
        }

        log.info("Built external system graph with evidence: {} nodes, {} edges (projectId={}, versionId={})",
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

                // 方法级 VERIFIED_BY（增量）— 解析 TestCase 内对被测方法的调用，
                // 建立 Method--VERIFIED_BY-->TestCase 边。confidence=0.8（高于类级 0.6，
                // 因为是从实际代码调用中解析得出，而非仅靠类名推断）。
                // 保留类级 VERIFIED_BY 作为回退（方法级匹配不到时仍可用类级关联）。
                if (tc.getInvokedMethodCalls() != null && !tc.getInvokedMethodCalls().isEmpty()) {
                    Map<String, GraphNode> methodLookup = buildMethodLookupForClass(
                            projectId, versionId, testedClassKey);
                    for (var invokedCall : tc.getInvokedMethodCalls()) {
                        GraphNode methodNode = matchMethodNode(methodLookup, invokedCall);
                        if (methodNode != null) {
                            allEdges.add(buildEdge(projectId, versionId,
                                    methodNode.getId(), testId,
                                    EdgeType.VERIFIED_BY.name(),
                                    methodNode.getNodeKey() + "->verified_by->" + testKey,
                                    SourceType.CODE_AST.name(),
                                    BigDecimal.valueOf(0.8), NodeStatus.CONFIRMED));
                        }
                    }
                }
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

        int nodeCount = 0, edgeCount = 0;

        // 1. 创建 Button 节点
        for (var button : buttons) {
            String buttonKey = "button:" + button.getText();

            GraphNode buttonNode = writer.upsertNode(GraphNodeClaim.builder()
                    .projectId(projectId)
                    .versionId(versionId)
                    .nodeType(NodeType.Button.name())
                    .nodeKey(buttonKey)
                    .nodeName(button.getText())
                    .displayName(button.getText())
                    .description(button.getClickMethod())
                    .sourceType(SourceType.FRONTEND_AST.name())
                    .sourcePath(null)
                    .confidence(BigDecimal.ONE)
                    .status(NodeStatus.CONFIRMED.name())
                    .scanType("CODE_SCAN")
                    .build());
            nodeCount++;

            // Button -> Permission REQUIRES 边
            if (button.getPermission() != null && !button.getPermission().isBlank()) {
                String permKey = button.getPermission().toLowerCase();

                GraphNode permNode = writer.upsertNode(GraphNodeClaim.builder()
                        .projectId(projectId)
                        .versionId(versionId)
                        .nodeType(NodeType.Permission.name())
                        .nodeKey(permKey)
                        .nodeName(button.getPermission())
                        .displayName(button.getPermission())
                        .description("权限标识")
                        .sourceType(SourceType.FRONTEND_AST.name())
                        .sourcePath(null)
                        .confidence(BigDecimal.ONE)
                        .status(NodeStatus.CONFIRMED.name())
                        .scanType("CODE_SCAN")
                        .build());
                nodeCount++;

                createEdge(projectId, versionId,
                        buttonNode.getId(), permNode.getId(),
                        EdgeType.REQUIRES.name(),
                        buttonKey + "->requires->" + permKey,
                        SourceType.FRONTEND_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED);
                edgeCount++;
            }

            // Button -> ApiEndpoint CALLS 边（如果有 apiUrl）
            if (button.getApiUrl() != null && !button.getApiUrl().isBlank()) {
                String apiKey = "api:" + button.getApiUrl();
                GraphNode apiNode = findExistingNode(projectId, versionId,
                        NodeType.ApiEndpoint.name(), apiKey);
                if (apiNode != null) {
                    createEdge(projectId, versionId,
                            buttonNode.getId(), apiNode.getId(),
                            EdgeType.CALLS.name(),
                            buttonKey + "->calls->" + apiKey,
                            SourceType.FRONTEND_AST.name(),
                            BigDecimal.ONE, NodeStatus.CONFIRMED);
                    edgeCount++;
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
                    String permKey = page.getPermission().toLowerCase();

                    GraphNode permNode = writer.upsertNode(GraphNodeClaim.builder()
                            .projectId(projectId)
                            .versionId(versionId)
                            .nodeType(NodeType.Permission.name())
                            .nodeKey(permKey)
                            .nodeName(page.getPermission())
                            .displayName(page.getPermission())
                            .description("页面访问权限")
                            .sourceType(SourceType.FRONTEND_AST.name())
                            .sourcePath(null)
                            .confidence(BigDecimal.ONE)
                            .status(NodeStatus.CONFIRMED.name())
                            .scanType("CODE_SCAN")
                            .build());
                    nodeCount++;

                    createEdge(projectId, versionId,
                            pageNode.getId(), permNode.getId(),
                            EdgeType.REQUIRES.name(),
                            pageKey + "->requires->" + permKey,
                            SourceType.FRONTEND_AST.name(),
                            BigDecimal.ONE, NodeStatus.CONFIRMED);
                    edgeCount++;
                }
            }
        }

        log.info("Built frontend button/permission graph with evidence: {} nodes, {} edges (projectId={}, versionId={})",
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
     * 构建 RBAC 角色图谱：Role/Permission 节点 + USES/GRANTS 边。
     * <p>Permission 节点 nodeKey 统一小写化（{@code permissionValue.toLowerCase()}），
     * 与前端 FrontendGraphBuilder 保持一致，确保前后端权限合并为同一节点。</p>
     * <p>Role 节点从 {@code properties.permissions} 列表读取关联权限，
     * 为每个 Permission 创建节点并建立 Role --GRANTS--> Permission 边。</p>
     */
    public void buildRbacRoleGraph(String projectId, String versionId,
            List<io.github.legacygraph.model.NodeExtractionResult> roles) {
        if (roles == null || roles.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var item : roles) {
            // Permission 节点直接构建（nodeKey 已在 RbacRoleExtractor 中小写化）
            if ("Permission".equals(item.getNodeType())) {
                String permId = IdUtil.fastUUID();
                GraphNode permNode = buildNode(projectId, versionId, permId,
                        NodeType.Permission.name(), item.getNodeKey(),
                        item.getDisplayName(), item.getDisplayName(),
                        item.getDescription(),
                        item.getSourceType(), item.getSourcePath(),
                        BigDecimal.valueOf(item.getConfidence()), NodeStatus.CONFIRMED,
                        "CODE_SCAN");
                allNodes.add(permNode);
                continue;
            }

            // Role 节点
            String roleKey = item.getNodeKey();
            String roleId = IdUtil.fastUUID();

            GraphNode roleNode = buildNode(projectId, versionId, roleId,
                    NodeType.Role.name(), roleKey, item.getDisplayName(),
                    item.getDisplayName(), item.getDescription(),
                    item.getSourceType(), item.getSourcePath(),
                    BigDecimal.valueOf(item.getConfidence()), NodeStatus.CONFIRMED,
                    "CODE_SCAN");
            allNodes.add(roleNode);

            // Role -> Service/Controller USES 边
            String context = item.getProperties() != null
                    ? (String) item.getProperties().get("context") : null;
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
                            item.getSourceType(),
                            BigDecimal.valueOf(item.getConfidence()), NodeStatus.CONFIRMED));
                }
            }

            // Role --GRANTS--> Permission 边：从 properties.permissions 读取关联权限列表
            Object permsObj = item.getProperties() != null
                    ? item.getProperties().get("permissions") : null;
            if (permsObj instanceof List<?> perms) {
                for (Object perm : perms) {
                    if (perm == null) continue;
                    String permValue = perm.toString();
                    // 统一小写化，确保前后端 Permission nodeKey 一致
                    String permKey = permValue.toLowerCase();
                    String permId = IdUtil.fastUUID();

                    // Permission 节点
                    GraphNode permNode = buildNode(projectId, versionId, permId,
                            NodeType.Permission.name(), permKey,
                            permValue, permValue,
                            "RBAC 权限: " + permValue,
                            item.getSourceType(), item.getSourcePath(),
                            BigDecimal.valueOf(item.getConfidence()), NodeStatus.CONFIRMED,
                            "CODE_SCAN");
                    allNodes.add(permNode);

                    // Role --GRANTS--> Permission 边
                    allEdges.add(buildEdge(projectId, versionId,
                            roleId, permId,
                            EdgeType.GRANTS.name(),
                            roleKey + "->grants->" + permKey,
                            item.getSourceType(),
                            BigDecimal.valueOf(item.getConfidence()), NodeStatus.CONFIRMED));
                }
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built RBAC role graph: {} nodes, {} edges (projectId={}, versionId={})",
                nodeCount, edgeCount, projectId, versionId);
    }

    /**
     * 构建 RBAC 用户图谱：User 节点 + ASSIGNED_TO 边（Role --ASSIGNED_TO--> User）。
     * <p>从 sys_user_role 关联表提取 Role→User 关联，为每个 User 创建节点并建立
     * Role --ASSIGNED_TO--> User 边。User 节点不含密码 hash。</p>
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param userRoles 用户-角色关联列表，每项包含 userName 和 roleName
     */
    public void buildRbacUserGraph(String projectId, String versionId,
            List<io.github.legacygraph.model.UserRoleAssignment> userRoles) {
        if (userRoles == null || userRoles.isEmpty()) return;

        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        for (var assignment : userRoles) {
            String userName = assignment.getUserName();
            String roleName = assignment.getRoleName();
            if (userName == null || userName.isBlank() || roleName == null || roleName.isBlank()) {
                continue;
            }
            String sourceType = assignment.getSourceType() != null ? assignment.getSourceType() : "DB_SCAN";

            // User 节点（不含密码 hash）
            String userKey = "user:" + userName.toLowerCase();
            String userId = IdUtil.fastUUID();
            GraphNode userNode = buildNode(projectId, versionId, userId,
                    NodeType.User.name(), userKey,
                    userName, userName,
                    "系统用户: " + userName,
                    sourceType, assignment.getSourcePath(),
                    BigDecimal.valueOf(0.9), NodeStatus.CONFIRMED,
                    sourceType);
            allNodes.add(userNode);

            // Role --ASSIGNED_TO--> User 边
            String roleKey = "role:" + roleName.toLowerCase();
            GraphNode roleNode = findExistingNode(projectId, versionId,
                    NodeType.Role.name(), roleKey);
            if (roleNode != null) {
                allEdges.add(buildEdge(projectId, versionId,
                        roleNode.getId(), userId,
                        EdgeType.ASSIGNED_TO.name(),
                        roleKey + "->assigned_to->" + userKey,
                        sourceType,
                        BigDecimal.valueOf(0.9), NodeStatus.CONFIRMED));
            } else {
                // Role 节点不存在时也创建 Role 节点，确保边不会丢失
                String roleId = IdUtil.fastUUID();
                GraphNode newRoleNode = buildNode(projectId, versionId, roleId,
                        NodeType.Role.name(), roleKey,
                        roleName, roleName,
                        "RBAC 角色: " + roleName,
                        sourceType, assignment.getSourcePath(),
                        BigDecimal.valueOf(0.9), NodeStatus.CONFIRMED,
                        sourceType);
                allNodes.add(newRoleNode);
                allEdges.add(buildEdge(projectId, versionId,
                        roleId, userId,
                        EdgeType.ASSIGNED_TO.name(),
                        roleKey + "->assigned_to->" + userKey,
                        sourceType,
                        BigDecimal.valueOf(0.9), NodeStatus.CONFIRMED));
            }
        }

        int nodeCount = neo4jGraphDao.mergeNodesBatch(allNodes);
        int edgeCount = neo4jGraphDao.mergeEdgesBatch(allEdges);
        log.info("Built RBAC user graph: {} nodes, {} edges (projectId={}, versionId={})",
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

    // ==================== Package 包图谱（架构依赖链路） ====================

    /**
     * 构建代码包图谱（架构依赖链路）— 旧入口，委托给 PackageExtractor + 新重载。
     * <p>保留此方法以兼容现有调用方；新代码应直接使用 PackageExtractor + {@link #buildPackageGraph(String, String, io.github.legacygraph.extractors.PackageExtractor.PackageGraphFact)}。
     *
     * @param classes JavaStructureExtractor 抽取的类结构（含 packageName 与 imports）
     */
    public void buildPackageGraph(String projectId, String versionId,
            List<JavaStructureExtractor.JavaClassInfo> classes) {
        if (classes == null || classes.isEmpty()) {
            return;
        }
        io.github.legacygraph.extractors.PackageExtractor extractor =
                new io.github.legacygraph.extractors.PackageExtractor();
        buildPackageGraph(projectId, versionId, extractor.extract(classes));
    }

    /**
     * 构建代码包图谱（架构依赖链路）— 新入口，消费 PackageExtractor 输出。
     * <p>创建 Package 节点，并建立：
     * <ul>
     *   <li>Class --BELONGS_TO--> Package（类归属包）</li>
     *   <li>Package --DEPENDS_ON--> Package（import 引入的包依赖，排除框架包）</li>
     * </ul>
     *
     * @param fact PackageExtractor 提取的包图事实
     */
    public void buildPackageGraph(String projectId, String versionId,
            io.github.legacygraph.extractors.PackageExtractor.PackageGraphFact fact) {
        if (fact == null || fact.getPackageNames().isEmpty()) {
            return;
        }
        int edgeCount = 0;
        // 包名 → Package 节点缓存（同一包只建一次节点）
        Map<String, GraphNode> pkgCache = new HashMap<>();

        // 创建所有 Package 节点
        for (String pkgName : fact.getPackageNames()) {
            pkgCache.put(pkgName, upsertPackageNode(projectId, versionId, pkgName));
        }

        // Class --BELONGS_TO--> Package
        for (var claim : fact.getBelongsTo()) {
            GraphNode pkgNode = pkgCache.get(claim.getPackageName());
            if (pkgNode == null) {
                pkgNode = upsertPackageNode(projectId, versionId, claim.getPackageName());
                pkgCache.put(claim.getPackageName(), pkgNode);
            }
            GraphNode classNode = findExistingNode(projectId, versionId,
                    inferNodeType(claim.getClassQualifiedName()).name(),
                    claim.getClassQualifiedName());
            if (classNode != null) {
                String belongsKey = claim.getClassQualifiedName() + "->belongs_to->" + claim.getPackageName();
                createEdge(projectId, versionId,
                        classNode.getId(), pkgNode.getId(),
                        EdgeType.BELONGS_TO.name(), belongsKey,
                        SourceType.CODE_AST.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED);
                edgeCount++;
            }
        }

        // Package --DEPENDS_ON--> Package
        for (var claim : fact.getDependsOn()) {
            GraphNode srcPkgNode = pkgCache.get(claim.getSourcePackage());
            if (srcPkgNode == null) {
                srcPkgNode = upsertPackageNode(projectId, versionId, claim.getSourcePackage());
                pkgCache.put(claim.getSourcePackage(), srcPkgNode);
            }
            GraphNode tgtPkgNode = pkgCache.get(claim.getTargetPackage());
            if (tgtPkgNode == null) {
                tgtPkgNode = upsertPackageNode(projectId, versionId, claim.getTargetPackage());
                pkgCache.put(claim.getTargetPackage(), tgtPkgNode);
            }
            String depKey = claim.getSourcePackage() + "->depends_on->" + claim.getTargetPackage();
            createEdge(projectId, versionId,
                    srcPkgNode.getId(), tgtPkgNode.getId(),
                    EdgeType.DEPENDS_ON.name(), depKey,
                    SourceType.CODE_AST.name(),
                    BigDecimal.ONE, NodeStatus.CONFIRMED);
            edgeCount++;
        }

        log.info("Built package graph: {} packages, {} edges (projectId={}, versionId={})",
                pkgCache.size(), edgeCount, projectId, versionId);
    }

    /**
     * 创建或更新 Package 节点。
     */
    private GraphNode upsertPackageNode(String projectId, String versionId, String pkgName) {
        return writer.upsertNode(GraphNodeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .nodeType(NodeType.Package.name())
                .nodeKey(pkgName)
                .nodeName(simpleName(pkgName))
                .displayName(pkgName)
                .description("代码包: " + pkgName)
                .sourceType(SourceType.CODE_AST.name())
                .confidence(BigDecimal.ONE)
                .status(NodeStatus.CONFIRMED.name())
                .scanType("CODE_SCAN")
                .build());
    }

    /**
     * 取全限定名的最后一段作为简单名。
     */
    private static String simpleName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }

    // ==================== 存根方法（预先存在的编译错误修复，待完整实现） ====================

    /** 存根：并发图谱构建 */
    public void buildConcurrencyGraph(String projectId, String versionId,
            io.github.legacygraph.extractors.ConcurrencyExtractor.ConcurrencyScanResult result) {
        log.debug("buildConcurrencyGraph stub: projectId={}, versionId={}", projectId, versionId);
    }

    /** 存根：异常图谱构建 */
    public void buildExceptionGraph(String projectId, String versionId,
            io.github.legacygraph.extractors.ExceptionExtractor.ExceptionScanResult result) {
        log.debug("buildExceptionGraph stub: projectId={}, versionId={}", projectId, versionId);
    }

    /** 存根：安全图谱构建 */
    public void buildSecurityGraph(String projectId, String versionId,
            io.github.legacygraph.extractors.SecurityExtractor.SecurityScanResult result) {
        log.debug("buildSecurityGraph stub: projectId={}, versionId={}", projectId, versionId);
    }
}
