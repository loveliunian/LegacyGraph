package io.github.legacygraph.service.requirement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dto.requirement.ContractSpec;
import io.github.legacygraph.entity.AcceptanceCriterion;
import io.github.legacygraph.entity.Requirement;
import io.github.legacygraph.entity.RequirementItem;
import io.github.legacygraph.repository.AcceptanceCriterionRepository;
import io.github.legacygraph.repository.RequirementItemRepository;
import io.github.legacygraph.repository.RequirementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 契约生成服务（G-17）。
 * <p>从需求条目（{@link RequirementItem}）的 text 和 constraints 中提取操作动词与资源名称，
 * 生成 RESTful 端点契约规范（{@link ContractSpec}），并可转为 OpenAPI 3.0 YAML 或 TypeScript 接口定义。</p>
 */
@Slf4j
@Service
public class ContractGeneratorService {

    private final RequirementRepository requirementRepository;
    private final RequirementItemRepository itemRepository;
    private final AcceptanceCriterionRepository criterionRepository;
    private final ObjectMapper objectMapper;

    public ContractGeneratorService(RequirementRepository requirementRepository,
                                     RequirementItemRepository itemRepository,
                                     AcceptanceCriterionRepository criterionRepository,
                                     ObjectMapper objectMapper) {
        this.requirementRepository = requirementRepository;
        this.itemRepository = itemRepository;
        this.criterionRepository = criterionRepository;
        this.objectMapper = objectMapper;
    }

    // ==================== 契约规范生成 ====================

    /**
     * 从需求条目生成契约规范。
     *
     * @param projectId     项目 ID
     * @param requirementId 需求 ID
     * @return 契约规范
     */
    public ContractSpec generateSpec(String projectId, String requirementId) {
        Requirement req = requirementRepository.selectById(requirementId);
        if (req == null) {
            throw new IllegalArgumentException("需求不存在: " + requirementId);
        }

        // 加载需求条目
        LambdaQueryWrapper<RequirementItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementItem::getRequirementId, requirementId);
        List<RequirementItem> items = itemRepository.selectList(wrapper);

        // 逐条目生成端点
        List<ContractSpec.Endpoint> endpoints = new ArrayList<>();
        for (RequirementItem item : items) {
            endpoints.addAll(generateEndpointsForItem(item));
        }

        return ContractSpec.builder()
                .projectId(projectId)
                .requirementId(requirementId)
                .title(req.getGoal() != null && !req.getGoal().isBlank()
                        ? req.getGoal() : "Generated API Contract")
                .version("1.0.0")
                .basePath("/api/v1")
                .endpoints(endpoints)
                .build();
    }

    /**
     * 从单条需求条目提取操作动词与资源名称，生成对应的 RESTful 端点。
     */
    private List<ContractSpec.Endpoint> generateEndpointsForItem(RequirementItem item) {
        List<ContractSpec.Endpoint> endpoints = new ArrayList<>();
        String text = item.getText();
        if (text == null || text.isBlank()) {
            return endpoints;
        }

        // 解析约束列表
        List<String> constraints = parseStringList(item.getConstraintsJson());

        // 加载验收条件，用于补充响应结构
        LambdaQueryWrapper<AcceptanceCriterion> acWrapper = new LambdaQueryWrapper<>();
        acWrapper.eq(AcceptanceCriterion::getRequirementItemId, item.getId());
        List<AcceptanceCriterion> acs = criterionRepository.selectList(acWrapper);
        List<String> acTexts = acs.stream().map(AcceptanceCriterion::getText).toList();

        // 提取资源名称与路径
        String resourceName = extractResourceName(text, item.getCode());
        String resourcePath = toPath(resourceName);
        String lowerText = text.toLowerCase();

        // 根据操作动词生成 CRUD 端点
        if (containsAny(lowerText, "创建", "新增", "添加", "create", "add")) {
            endpoints.add(ContractSpec.Endpoint.builder()
                    .method("POST")
                    .path("/" + resourcePath)
                    .summary("创建" + resourceName)
                    .requestSchema(buildRequestSchema(resourceName, constraints))
                    .responseSchema(buildResponseSchema(resourceName, acTexts))
                    .build());
        }
        if (containsAny(lowerText, "查询", "获取", "列表", "搜索", "查看", "query", "get", "list", "search")) {
            // 列表端点
            endpoints.add(ContractSpec.Endpoint.builder()
                    .method("GET")
                    .path("/" + resourcePath)
                    .summary("查询" + resourceName + "列表")
                    .requestSchema(null)
                    .responseSchema(buildListResponseSchema(resourceName, acTexts))
                    .build());
            // 详情端点
            endpoints.add(ContractSpec.Endpoint.builder()
                    .method("GET")
                    .path("/" + resourcePath + "/{id}")
                    .summary("获取" + resourceName + "详情")
                    .requestSchema(null)
                    .responseSchema(buildResponseSchema(resourceName, acTexts))
                    .build());
        }
        if (containsAny(lowerText, "更新", "修改", "编辑", "update", "modify", "edit")) {
            endpoints.add(ContractSpec.Endpoint.builder()
                    .method("PUT")
                    .path("/" + resourcePath + "/{id}")
                    .summary("更新" + resourceName)
                    .requestSchema(buildRequestSchema(resourceName, constraints))
                    .responseSchema(buildResponseSchema(resourceName, acTexts))
                    .build());
        }
        if (containsAny(lowerText, "删除", "移除", "delete", "remove")) {
            endpoints.add(ContractSpec.Endpoint.builder()
                    .method("DELETE")
                    .path("/" + resourcePath + "/{id}")
                    .summary("删除" + resourceName)
                    .requestSchema(null)
                    .responseSchema(buildDeleteResponseSchema())
                    .build());
        }

        // 未识别到操作动词时，默认生成查询端点
        if (endpoints.isEmpty()) {
            endpoints.add(ContractSpec.Endpoint.builder()
                    .method("GET")
                    .path("/" + resourcePath)
                    .summary("查询" + resourceName)
                    .requestSchema(null)
                    .responseSchema(buildListResponseSchema(resourceName, acTexts))
                    .build());
        }
        return endpoints;
    }

    // ==================== 资源名称提取 ====================

    /**
     * 从需求条目文本中提取资源名称。
     * <p>简化策略：移除常见操作动词与助词后取剩余文本；无法提取时回退到条目编码。</p>
     */
    private String extractResourceName(String text, String fallbackCode) {
        // 移除常见动词、助词等
        String cleaned = text.replaceAll(
                "(?i)(创建|新增|添加|查询|获取|列表|搜索|查看|更新|修改|编辑|删除|移除|"
                        + "能够|需要|应该|系统|用户可以|可以|支持|提供|实现|"
                        + "create|add|query|get|list|search|update|modify|edit|delete|remove|"
                        + "the|a|an|able to|should|must|can)",
                "");
        cleaned = cleaned.replaceAll("[\\s\\p{Punct}]+", "").trim();
        if (cleaned.isEmpty()) {
            return fallbackCode != null ? fallbackCode.toLowerCase() : "resource";
        }
        // 截取前 8 个字符作为资源名
        return cleaned.length() > 8 ? cleaned.substring(0, 8) : cleaned;
    }

    /**
     * 将资源名称转为 URL 路径片段。
     * <p>英文字母转小写，空格转连字符；纯中文保留原样。</p>
     */
    private String toPath(String resourceName) {
        return resourceName.toLowerCase().replaceAll("\\s+", "-");
    }

    // ==================== Schema 构建 ====================

    /**
     * 构建请求体 Schema。
     */
    private Map<String, Object> buildRequestSchema(String resourceName, List<String> constraints) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", schemaProp("string", resourceName + "名称"));
        properties.put("description", schemaProp("string", "描述信息"));
        // 从约束中提取字段（简化：每条约束映射为一个 string 字段）
        if (constraints != null) {
            for (int i = 0; i < constraints.size() && i < 5; i++) {
                String field = "field" + (i + 1);
                properties.put(field, schemaProp("string", constraints.get(i)));
            }
        }
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        return schema;
    }

    /**
     * 构建单条资源响应 Schema。
     */
    private Map<String, Object> buildResponseSchema(String resourceName, List<String> acTexts) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", schemaProp("string", resourceName + "唯一标识"));
        properties.put("name", schemaProp("string", resourceName + "名称"));
        properties.put("createdAt", schemaProp("string", "创建时间（ISO 8601）"));
        // 从验收条件中提取响应字段（简化：每条 AC 映射为一个字段）
        if (acTexts != null) {
            for (int i = 0; i < acTexts.size() && i < 5; i++) {
                String field = "attr" + (i + 1);
                properties.put(field, schemaProp("string", acTexts.get(i)));
            }
        }
        schema.put("properties", properties);
        return schema;
    }

    /**
     * 构建列表响应 Schema。
     */
    private Map<String, Object> buildListResponseSchema(String resourceName, List<String> acTexts) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("total", schemaProp("integer", "总记录数"));
        // items 为数组，引用单条资源结构
        Map<String, Object> itemsSchema = buildResponseSchema(resourceName, acTexts);
        Map<String, Object> arrayProp = new LinkedHashMap<>();
        arrayProp.put("type", "array");
        arrayProp.put("items", itemsSchema);
        properties.put("items", arrayProp);
        schema.put("properties", properties);
        return schema;
    }

    /**
     * 构建删除操作响应 Schema。
     */
    private Map<String, Object> buildDeleteResponseSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("deleted", schemaProp("boolean", "是否删除成功"));
        properties.put("id", schemaProp("string", "被删除的资源 ID"));
        schema.put("properties", properties);
        return schema;
    }

    /** 构建一个简单的 type+description Schema 属性 */
    private Map<String, Object> schemaProp(String type, String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", type);
        prop.put("description", description);
        return prop;
    }

    // ==================== OpenAPI 3.0 YAML 输出 ====================

    /**
     * 将契约规范转为 OpenAPI 3.0 YAML 字符串。
     * <p>使用 StringBuilder 手动构建，不依赖第三方 YAML 库。</p>
     */
    public String toOpenApiYaml(ContractSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("openapi: 3.0.3\n");
        sb.append("info:\n");
        sb.append("  title: ").append(yamlEscape(spec.getTitle())).append("\n");
        sb.append("  version: ").append(spec.getVersion()).append("\n");
        sb.append("  description: 由需求 ").append(spec.getRequirementId()).append(" 自动生成的 API 契约\n");
        sb.append("servers:\n");
        sb.append("  - url: ").append(spec.getBasePath()).append("\n");
        sb.append("    description: API 基础路径\n");
        sb.append("paths:\n");

        // 按路径分组端点
        Map<String, List<ContractSpec.Endpoint>> pathMap = new LinkedHashMap<>();
        for (ContractSpec.Endpoint ep : spec.getEndpoints()) {
            pathMap.computeIfAbsent(ep.getPath(), k -> new ArrayList<>()).add(ep);
        }

        for (Map.Entry<String, List<ContractSpec.Endpoint>> entry : pathMap.entrySet()) {
            String path = entry.getKey();
            sb.append("  ").append(path).append(":\n");
            for (ContractSpec.Endpoint ep : entry.getValue()) {
                String method = ep.getMethod().toLowerCase();
                sb.append("    ").append(method).append(":\n");
                sb.append("      summary: ").append(yamlEscape(ep.getSummary())).append("\n");
                sb.append("      operationId: ").append(method).append(path.replaceAll("[/{}]", "")).append("\n");
                // 请求体
                if (ep.getRequestSchema() != null && !ep.getRequestSchema().isEmpty()) {
                    sb.append("      requestBody:\n");
                    sb.append("        required: true\n");
                    sb.append("        content:\n");
                    sb.append("          application/json:\n");
                    sb.append("            schema:\n");
                    appendSchemaYaml(sb, ep.getRequestSchema(), 14);
                }
                // 响应
                sb.append("      responses:\n");
                sb.append("        '200':\n");
                sb.append("          description: 成功响应\n");
                if (ep.getResponseSchema() != null && !ep.getResponseSchema().isEmpty()) {
                    sb.append("          content:\n");
                    sb.append("            application/json:\n");
                    sb.append("              schema:\n");
                    appendSchemaYaml(sb, ep.getResponseSchema(), 16);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 递归将 Schema Map 追加为 YAML 片段。
     *
     * @param sb    目标 StringBuilder
     * @param schema 当前层 Schema
     * @param indent 当前缩进空格数
     */
    @SuppressWarnings("unchecked")
    private void appendSchemaYaml(StringBuilder sb, Map<String, Object> schema, int indent) {
        String pad = " ".repeat(indent);
        for (Map.Entry<String, Object> e : schema.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (val instanceof Map) {
                sb.append(pad).append(key).append(":\n");
                appendSchemaYaml(sb, (Map<String, Object>) val, indent + 2);
            } else if (val instanceof List) {
                sb.append(pad).append(key).append(":\n");
                for (Object item : (List<?>) val) {
                    sb.append(" ".repeat(indent + 2)).append("- ").append(yamlEscape(String.valueOf(item))).append("\n");
                }
            } else {
                sb.append(pad).append(key).append(": ").append(yamlEscape(String.valueOf(val))).append("\n");
            }
        }
    }

    /** YAML 字符串转义（简化：仅在含特殊字符时加引号） */
    private String yamlEscape(String s) {
        if (s == null) return "";
        if (s.matches(".*[:#{}\\[\\],&*!|>'\"%@`].*")) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    // ==================== TypeScript 接口输出 ====================

    /**
     * 将契约规范转为 TypeScript 接口定义字符串。
     */
    public String toTypeScript(ContractSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("// 由需求 ").append(spec.getRequirementId()).append(" 自动生成的 TypeScript 契约\n");
        sb.append("// 标题: ").append(spec.getTitle()).append("\n");
        sb.append("// 版本: ").append(spec.getVersion()).append("\n");
        sb.append("// 基础路径: ").append(spec.getBasePath()).append("\n\n");

        // 收集所有资源类型名，避免重复定义
        Set<String> definedTypes = new LinkedHashSet<>();
        // 按 path 分组生成接口
        Map<String, List<ContractSpec.Endpoint>> pathMap = new LinkedHashMap<>();
        for (ContractSpec.Endpoint ep : spec.getEndpoints()) {
            pathMap.computeIfAbsent(ep.getPath(), k -> new ArrayList<>()).add(ep);
        }

        for (Map.Entry<String, List<ContractSpec.Endpoint>> entry : pathMap.entrySet()) {
            String path = entry.getKey();
            String typeName = toTypeName(path);
            for (ContractSpec.Endpoint ep : entry.getValue()) {
                // 请求接口
                if (ep.getRequestSchema() != null && !ep.getRequestSchema().isEmpty()) {
                    String reqName = typeName + toPascalCase(ep.getMethod()) + "Request";
                    if (definedTypes.add(reqName)) {
                        sb.append("export interface ").append(reqName).append(" {\n");
                        appendTsProperties(sb, ep.getRequestSchema(), "  ");
                        sb.append("}\n\n");
                    }
                }
                // 响应接口
                if (ep.getResponseSchema() != null && !ep.getResponseSchema().isEmpty()) {
                    String resName = typeName + toPascalCase(ep.getMethod()) + "Response";
                    if (definedTypes.add(resName)) {
                        sb.append("export interface ").append(resName).append(" {\n");
                        appendTsProperties(sb, ep.getResponseSchema(), "  ");
                        sb.append("}\n\n");
                    }
                }
            }
        }

        // 生成端点类型汇总
        sb.append("// ==================== 端点定义 ====================\n");
        sb.append("export type ApiEndpoint = {\n");
        sb.append("  method: 'GET' | 'POST' | 'PUT' | 'DELETE';\n");
        sb.append("  path: string;\n");
        sb.append("  summary: string;\n");
        sb.append("};\n\n");
        sb.append("export const endpoints: ApiEndpoint[] = [\n");
        for (ContractSpec.Endpoint ep : spec.getEndpoints()) {
            sb.append("  { method: '").append(ep.getMethod()).append("', path: '")
                    .append(ep.getPath()).append("', summary: '").append(tsEscape(ep.getSummary())).append("' },\n");
        }
        sb.append("];\n");
        return sb.toString();
    }

    /**
     * 将 Schema Map 的 properties 追加为 TypeScript 接口属性。
     */
    @SuppressWarnings("unchecked")
    private void appendTsProperties(StringBuilder sb, Map<String, Object> schema, String indent) {
        Object propsObj = schema.get("properties");
        if (!(propsObj instanceof Map)) {
            return;
        }
        Map<String, Object> props = (Map<String, Object>) propsObj;
        Set<String> required = new HashSet<>();
        Object reqObj = schema.get("required");
        if (reqObj instanceof List) {
            required.addAll((List<String>) reqObj);
        }
        for (Map.Entry<String, Object> e : props.entrySet()) {
            String field = e.getKey();
            Object propDef = e.getValue();
            String tsType = "any";
            String description = "";
            if (propDef instanceof Map) {
                Map<String, Object> propMap = (Map<String, Object>) propDef;
                String type = String.valueOf(propMap.getOrDefault("type", "string"));
                description = String.valueOf(propMap.getOrDefault("description", ""));
                if ("array".equals(type)) {
                    Object items = propMap.get("items");
                    if (items instanceof Map) {
                        // 数组项为对象类型时，内联展开
                        String itemTypeName = toTypeName(field);
                        sb.append(indent).append("// 内联数组项类型（自动生成）\n");
                        sb.append("export interface ").append(itemTypeName).append("Item {\n");
                        appendTsProperties(sb, (Map<String, Object>) items, "  ");
                        sb.append("}\n");
                        tsType = itemTypeName + "Item[]";
                    } else {
                        tsType = "any[]";
                    }
                } else {
                    tsType = tsTypeOf(type);
                }
            }
            boolean optional = !required.contains(field);
            sb.append(indent).append(field).append(optional ? "?" : "").append(": ").append(tsType).append(";");
            if (!description.isEmpty()) {
                sb.append(" // ").append(description);
            }
            sb.append("\n");
        }
    }

    /** JSON Schema type → TypeScript type */
    private String tsTypeOf(String jsonType) {
        return switch (jsonType) {
            case "integer", "number" -> "number";
            case "boolean" -> "boolean";
            case "array" -> "any[]";
            default -> "string";
        };
    }

    /** 路径转 PascalCase 类型名，如 /users/{id} → Users */
    private String toTypeName(String path) {
        String name = path.replaceAll("\\{[^}]+}", "")
                .replaceAll("[^a-zA-Z0-9]", " ")
                .trim();
        if (name.isEmpty()) {
            return "Resource";
        }
        StringBuilder result = new StringBuilder();
        for (String part : name.split("\\s+")) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    result.append(part.substring(1).toLowerCase());
                }
            }
        }
        return result.length() > 0 ? result.toString() : "Resource";
    }

    /** HTTP 方法转 PascalCase，如 get → Get */
    private String toPascalCase(String method) {
        if (method == null || method.isEmpty()) return "";
        return Character.toUpperCase(method.charAt(0)) + method.substring(1).toLowerCase();
    }

    /** TypeScript 字符串转义 */
    private String tsEscape(String s) {
        if (s == null) return "";
        return s.replace("'", "\\'");
    }

    // ==================== 工具方法 ====================

    /** 判断文本是否包含任意关键词 */
    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** JSON 数组字符串 → List<String> */
    @SuppressWarnings("unchecked")
    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = objectMapper.readValue(json, List.class);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.warn("解析约束 JSON 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
