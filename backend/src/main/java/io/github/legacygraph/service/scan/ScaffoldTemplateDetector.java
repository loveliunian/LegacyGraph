package io.github.legacygraph.service.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ScaffoldTemplate;
import io.github.legacygraph.repository.ScaffoldTemplateRepository;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚手架模板检测服务（G-21）。
 *
 * <p>在扫描完成后从图谱中识别标准 CRUD 模板（Controller/Service/Mapper/Entity 分层），
 * 提取代码骨架（类声明、继承关系、注解、方法签名）存入模板表，
 * 供 {@link io.github.legacygraph.service.solution.ScaffoldTemplateMatcher}
 * 在方案生成时匹配复用。</p>
 *
 * <p>识别逻辑：查询图谱中 Controller/Service/Mapper 类型节点，按类名提取实体名
 * （如 UserController → User），对同时具备 Controller+Service+Mapper 的实体
 * 判定为标准 CRUD 基线，提取每层代码骨架生成模板。</p>
 */
@Slf4j
@Service
public class ScaffoldTemplateDetector {

    /** 每层节点查询上限 */
    private static final int LAYER_NODE_LIMIT = 500;

    /** 方法节点查询上限（每个类） */
    private static final int METHOD_NODE_LIMIT = 50;

    private final ScaffoldTemplateRepository scaffoldTemplateRepository;
    private final Neo4jGraphDao neo4jGraphDao;
    private final ObjectMapper objectMapper;

    public ScaffoldTemplateDetector(ScaffoldTemplateRepository scaffoldTemplateRepository,
                                    Neo4jGraphDao neo4jGraphDao,
                                    ObjectMapper objectMapper) {
        this.scaffoldTemplateRepository = scaffoldTemplateRepository;
        this.neo4jGraphDao = neo4jGraphDao;
        this.objectMapper = objectMapper;
    }

    /**
     * 从图谱中识别标准 CRUD 模板并持久化。
     *
     * @param projectId     项目 ID
     * @param scanVersionId 扫描版本 ID（可为 null，查询全版本）
     * @return 识别并保存的模板列表
     */
    public List<ScaffoldTemplate> detect(String projectId, String scanVersionId) {
        if (projectId == null || projectId.isBlank()) {
            return List.of();
        }
        log.info("ScaffoldTemplateDetector: start detecting templates for projectId={}, versionId={}",
                projectId, scanVersionId);

        try {
            // 1. 查询各层级节点
            Map<String, GraphNode> controllerNodes = queryLayerNodes(projectId, scanVersionId, NodeType.Controller);
            Map<String, GraphNode> serviceNodes = queryLayerNodes(projectId, scanVersionId, NodeType.Service);
            Map<String, GraphNode> mapperNodes = queryLayerNodes(projectId, scanVersionId, NodeType.Mapper);

            // 2. 收集所有实体名（Controller 层出现的实体视为 CRUD 基线候选）
            Set<String> entityNames = controllerNodes.keySet();

            List<ScaffoldTemplate> templates = new ArrayList<>();
            for (String entityName : entityNames) {
                // 实体需同时具备 Service 和 Mapper 才算标准 CRUD 基线
                if (!serviceNodes.containsKey(entityName) || !mapperNodes.containsKey(entityName)) {
                    continue;
                }
                // 生成 Controller 层模板
                addTemplate(templates, buildTemplate(projectId, entityName, "Controller", controllerNodes.get(entityName)));
                // 生成 Service 层模板
                addTemplate(templates, buildTemplate(projectId, entityName, "Service", serviceNodes.get(entityName)));
                // 生成 Mapper 层模板
                addTemplate(templates, buildTemplate(projectId, entityName, "Mapper", mapperNodes.get(entityName)));
                // 生成 Entity 层模板（若存在对应实体类）
                GraphNode entityNode = findEntityNode(projectId, scanVersionId, entityName);
                if (entityNode != null) {
                    addTemplate(templates, buildTemplate(projectId, entityName, "Entity", entityNode));
                }
            }

            // 3. 持久化模板（先清除旧模板再插入）
            if (!templates.isEmpty()) {
                deleteExistingTemplates(projectId);
                scaffoldTemplateRepository.insertBatch(templates);
            }

            log.info("ScaffoldTemplateDetector: detected {} templates for {} entities (projectId={})",
                    templates.size(), entityNames.size(), projectId);
            return templates;
        } catch (Exception e) {
            log.warn("ScaffoldTemplateDetector: failed to detect templates for projectId={}: {}",
                    projectId, e.getMessage(), e);
            return List.of();
        }
    }

    // ==================== 节点查询 ====================

    /**
     * 查询指定层级的图谱节点，按实体名分组。
     *
     * @param projectId     项目 ID
     * @param versionId    扫描版本 ID
     * @param layerNodeType 层级对应的 NodeType（Controller/Service/Mapper）
     * @return 实体名 → 节点 映射
     */
    private Map<String, GraphNode> queryLayerNodes(String projectId, String versionId, NodeType layerNodeType) {
        List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                projectId, versionId, layerNodeType.name(), null,
                null, null, LAYER_NODE_LIMIT);
        Map<String, GraphNode> result = new LinkedHashMap<>();
        if (nodes == null || nodes.isEmpty()) {
            return result;
        }
        for (GraphNode node : nodes) {
            String className = node.getClassName() != null ? node.getClassName() : node.getNodeName();
            String entityName = extractEntityName(className, layerNodeType.name());
            if (entityName != null && !entityName.isBlank()) {
                // 同名实体取第一个（基线样本）
                result.putIfAbsent(entityName, node);
            }
        }
        return result;
    }

    /**
     * 查找实体类节点（通过源文件路径含 /entity/ 且类名匹配）。
     */
    private GraphNode findEntityNode(String projectId, String versionId, String entityName) {
        try {
            List<GraphNode> allNodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, null, "CODE_AST",
                    null, null, LAYER_NODE_LIMIT);
            if (allNodes == null || allNodes.isEmpty()) {
                return null;
            }
            for (GraphNode node : allNodes) {
                String sourcePath = node.getSourcePath();
                if (sourcePath == null || !sourcePath.contains("/entity/")) {
                    continue;
                }
                String className = node.getClassName() != null ? node.getClassName() : node.getNodeName();
                String simpleName = extractSimpleName(className);
                if (entityName.equals(simpleName)) {
                    return node;
                }
            }
        } catch (Exception e) {
            log.debug("ScaffoldTemplateDetector: failed to find entity node for {}: {}", entityName, e.getMessage());
        }
        return null;
    }

    // ==================== 模板构建 ====================

    /**
     * 从图谱节点构建脚手架模板。
     */
    private ScaffoldTemplate buildTemplate(String projectId, String entityName, String layer, GraphNode node) {
        ScaffoldTemplate template = new ScaffoldTemplate();
        template.setId(IdUtil.fastUUID());
        template.setProjectId(projectId);
        template.setEntityName(entityName);
        template.setLayer(layer);
        template.setFilePath(node.getSourcePath());

        // 提取代码骨架
        template.setCodeSkeleton(buildCodeSkeleton(node, layer, entityName));

        // 提取注解信息
        template.setAnnotations(extractAnnotations(node));

        // 提取方法签名
        template.setMethodSignatures(extractMethodSignatures(node));

        LocalDateTime now = LocalDateTime.now();
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        return template;
    }

    /**
     * 构建代码骨架字符串（含类声明、继承关系）。
     */
    private String buildCodeSkeleton(GraphNode node, String layer, String entityName) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Layer: ").append(layer).append("\n");
        sb.append("// Entity: ").append(entityName).append("\n");
        if (node.getSourcePath() != null) {
            sb.append("// File: ").append(node.getSourcePath()).append("\n");
        }
        sb.append("\n");

        String className = node.getClassName() != null ? node.getClassName() : node.getNodeName();
        String simpleName = extractSimpleName(className);
        String packageName = extractPackageName(className);

        if (packageName != null && !packageName.isBlank()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        // 继承关系
        Map<String, Object> props = parseProperties(node.getProperties());
        String extendsClause = buildExtendsClause(props);
        String implementsClause = buildImplementsClause(props);

        sb.append("public class ").append(simpleName);
        if (extendsClause != null) {
            sb.append(" extends ").append(extendsClause);
        }
        if (implementsClause != null) {
            sb.append(" implements ").append(implementsClause);
        }
        sb.append(" {\n");
        sb.append("    // 方法签名详见 methodSignatures 字段\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 从节点 properties 中提取注解信息（JSON 字符串）。
     */
    private String extractAnnotations(GraphNode node) {
        Map<String, Object> props = parseProperties(node.getProperties());
        if (props.isEmpty()) {
            return null;
        }
        // 从 properties 中提取注解相关字段（若有）
        List<String> annotations = new ArrayList<>();
        if (props.containsKey("extendedTypes")) {
            annotations.add("@Extends(" + props.get("extendedTypes") + ")");
        }
        if (props.containsKey("implementedTypes")) {
            annotations.add("@Implements(" + props.get("implementedTypes") + ")");
        }
        if (annotations.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(annotations);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查询类的方法节点并提取方法签名（JSON 字符串）。
     */
    private String extractMethodSignatures(GraphNode classNode) {
        if (classNode == null || classNode.getNodeKey() == null) {
            return null;
        }
        try {
            // 查询 Method 节点，按 nodeKey 前缀匹配类
            String classKey = classNode.getNodeKey();
            List<GraphNode> methodNodes = neo4jGraphDao.queryNodes(
                    classNode.getProjectId(), classNode.getVersionId(),
                    NodeType.Method.name(), null, null, null, LAYER_NODE_LIMIT);
            if (methodNodes == null || methodNodes.isEmpty()) {
                return null;
            }
            // 过滤属于当前类的方法（nodeKey 以类 FQN 开头）
            List<Map<String, Object>> signatures = new ArrayList<>();
            for (GraphNode method : methodNodes) {
                String methodKey = method.getNodeKey();
                if (methodKey == null || !methodKey.startsWith(classKey + ".")) {
                    continue;
                }
                Map<String, Object> sig = new LinkedHashMap<>();
                sig.put("name", method.getNodeName());
                sig.put("nodeKey", methodKey);
                signatures.add(sig);
                if (signatures.size() >= METHOD_NODE_LIMIT) {
                    break;
                }
            }
            if (signatures.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(signatures);
        } catch (Exception e) {
            log.debug("ScaffoldTemplateDetector: failed to extract method signatures for {}: {}",
                    classNode.getNodeKey(), e.getMessage());
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从类全名中提取实体名（去除分层后缀）。
     * <p>如 UserController → User，UserServiceImpl → User，UserMapper → User</p>
     *
     * @param className    类全名（如 com.example.controller.UserController）
     * @param layerNodeType 层级类型名（Controller/Service/Mapper）
     * @return 实体名（如 User）
     */
    String extractEntityName(String className, String layerNodeType) {
        if (className == null || className.isBlank()) {
            return null;
        }
        String simpleName = extractSimpleName(className);
        // 去除 Impl 后缀
        if (simpleName.endsWith("Impl")) {
            simpleName = simpleName.substring(0, simpleName.length() - 4);
        }
        // 去除层级后缀
        if (simpleName.endsWith(layerNodeType)) {
            simpleName = simpleName.substring(0, simpleName.length() - layerNodeType.length());
        }
        // 也尝试去除其他层级后缀（如 UserService 中提取 User，需去掉 Service）
        for (String suffix : List.of("Controller", "Service", "Mapper", "Repository", "Entity")) {
            if (simpleName.endsWith(suffix) && simpleName.length() > suffix.length()) {
                simpleName = simpleName.substring(0, simpleName.length() - suffix.length());
                break;
            }
        }
        return simpleName.isBlank() ? null : simpleName;
    }

    /**
     * 从类全名中提取简单类名（去包路径）。
     */
    private String extractSimpleName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return qualifiedName;
        }
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }

    /**
     * 从类全名中提取包名。
     */
    private String extractPackageName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return null;
        }
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot > 0 ? qualifiedName.substring(0, lastDot) : null;
    }

    /**
     * 从 properties 中提取 extends 子句。
     */
    @SuppressWarnings("unchecked")
    private String buildExtendsClause(Map<String, Object> props) {
        Object extended = props.get("extendedTypes");
        if (extended == null) {
            return null;
        }
        if (extended instanceof List) {
            List<String> types = (List<String>) extended;
            if (types.isEmpty()) {
                return null;
            }
            return String.join(", ", types);
        }
        return extended.toString();
    }

    /**
     * 从 properties 中提取 implements 子句。
     */
    @SuppressWarnings("unchecked")
    private String buildImplementsClause(Map<String, Object> props) {
        Object implemented = props.get("implementedTypes");
        if (implemented == null) {
            return null;
        }
        if (implemented instanceof List) {
            List<String> types = (List<String>) implemented;
            if (types.isEmpty()) {
                return null;
            }
            return String.join(", ", types);
        }
        return implemented.toString();
    }

    /**
     * 解析节点 properties JSON 为 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseProperties(String propertiesJson) {
        if (propertiesJson == null || propertiesJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(propertiesJson, Map.class);
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 删除项目已有的旧模板（重新检测前清理）。
     */
    private void deleteExistingTemplates(String projectId) {
        List<ScaffoldTemplate> existing = scaffoldTemplateRepository.findByProjectId(projectId);
        if (existing != null && !existing.isEmpty()) {
            for (ScaffoldTemplate t : existing) {
                scaffoldTemplateRepository.deleteById(t.getId());
            }
            log.debug("ScaffoldTemplateDetector: deleted {} old templates for projectId={}",
                    existing.size(), projectId);
        }
    }

    private void addTemplate(List<ScaffoldTemplate> templates, ScaffoldTemplate template) {
        if (template != null) {
            templates.add(template);
        }
    }
}
