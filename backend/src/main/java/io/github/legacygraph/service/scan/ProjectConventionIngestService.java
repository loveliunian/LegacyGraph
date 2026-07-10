package io.github.legacygraph.service.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.Project;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.ProjectRepository;
import io.github.legacygraph.service.qa.VectorizationService;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目约定知识入库服务 — 扫描完成后把项目级约定向量化到 pgvector（chunkType=PROJECT_CONVENTION），
 * 供 QA 召回"这个项目用什么技术栈/怎么分层/类怎么命名"等问题。
 *
 * <p>入库内容：
 * <ul>
 *   <li>技术栈：优先从 {@link Project#getTechStack()}（JSONB 字符串）解析；为空时从仓库 pom.xml / package.json 解析，
 *       含 Spring Boot / MyBatis-Plus / Vue 版本等</li>
 *   <li>分层规范：从扫描产出的图谱节点统计推导标准分层（controller/service/mapper/entity）</li>
 *   <li>命名约定：从图谱节点 nodeName 统计类名后缀（Controller/Service/Mapper）符合约定的比例</li>
 * </ul>
 *
 * <p>设计约束（参考 {@link ScanArtifactPublisher}）：
 * <ul>
 *   <li>重新入库前先按 sourceUri 删除旧向量，避免残留</li>
 *   <li>VectorizationService 不可用时静默跳过</li>
 *   <li>各部分独立 try/catch，单部分失败不影响其他部分</li>
 *   <li>失败只 warn，不阻塞扫描主流程</li>
 * </ul>
 */
@Slf4j
@Service
public class ProjectConventionIngestService {

    /** chunkType 标识项目约定知识 */
    public static final String CHUNK_TYPE = "PROJECT_CONVENTION";
    /** sourceUri 固定值（与 ScanArtifactPublisher 的 docs/legacygraph 风格一致），便于按约定溯源与重入时去重 */
    public static final String SOURCE_URI = "docs/legacygraph/project-convention.md";

    private static final int CHUNK_MAX_CHARS = 1000;
    private static final int CHUNK_OVERLAP = 100;
    /** 查询各类节点时的上限，避免超大项目一次拉取过多 */
    private static final int NODE_QUERY_LIMIT = 2000;
    /** 文档中展示的样本名称数量 */
    private static final int SAMPLE_NAME_LIMIT = 5;

    /** 标准分层节点类型 → 对应的命名后缀，用于分层规范与命名约定统计 */
    private static final Map<String, String> LAYER_SUFFIX = new LinkedHashMap<>();
    static {
        LAYER_SUFFIX.put("Controller", "Controller");
        LAYER_SUFFIX.put("Service", "Service");
        LAYER_SUFFIX.put("Mapper", "Mapper");
    }

    private final ProjectRepository projectRepository;
    private final VectorizationService vectorizationService;
    private final Neo4jGraphDao neo4jGraphDao;
    private final CodeRepoRepository codeRepoRepository;
    private final ObjectMapper objectMapper;

    public ProjectConventionIngestService(ProjectRepository projectRepository,
                                           VectorizationService vectorizationService,
                                           Neo4jGraphDao neo4jGraphDao,
                                           CodeRepoRepository codeRepoRepository,
                                           ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.vectorizationService = vectorizationService;
        this.neo4jGraphDao = neo4jGraphDao;
        this.codeRepoRepository = codeRepoRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 把项目约定知识向量化到 pgvector。扫描完成后调用，失败不抛异常。
     * 内部查询项目关联的代码仓库后委托 {@link #ingestProjectConvention}。
     *
     * @param projectId 项目ID
     * @param versionId 扫描版本ID（用于关联向量与版本，可为 null）
     */
    public void ingest(String projectId, String versionId) {
        if (projectId == null || projectId.isBlank()) {
            log.warn("ProjectConventionIngest: projectId 为空，跳过项目约定入库");
            return;
        }
        List<CodeRepo> repos = List.of();
        try {
            repos = codeRepoRepository.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CodeRepo>()
                            .eq(CodeRepo::getProjectId, projectId));
        } catch (Exception e) {
            log.warn("ProjectConventionIngest: 查询项目仓库失败 projectId={}: {}", projectId, e.getMessage());
        }
        ingestProjectConvention(projectId, versionId, repos);
    }

    /**
     * 把项目约定知识向量化到 pgvector（接收显式 repos 参数，避免重复查询）。
     * 扫描完成后由 {@code ProjectScanner} 调用，失败不抛异常。
     *
     * @param projectId 项目ID
     * @param versionId 扫描版本ID（用于关联向量与版本，可为 null）
     * @param repos     项目关联的代码仓库列表（用于从 pom.xml/package.json 提取技术栈，可为空）
     */
    public void ingestProjectConvention(String projectId, String versionId, List<CodeRepo> repos) {
        if (projectId == null || projectId.isBlank()) {
            log.warn("ProjectConventionIngest: projectId 为空，跳过项目约定入库");
            return;
        }
        if (!vectorizationService.isAvailable()) {
            log.debug("VectorizationService 不可用，跳过项目约定入库: projectId={}", projectId);
            return;
        }

        // 拼装项目约定 Markdown 文档
        String markdown = buildConventionMarkdown(projectId, versionId, repos);
        if (markdown == null || markdown.isBlank()) {
            log.info("ProjectConventionIngest: 项目约定文档为空，跳过向量化: projectId={}", projectId);
            return;
        }

        // 重新入库前先删除旧向量，避免残留陈旧内容
        try {
            vectorizationService.deleteBySourceUri(SOURCE_URI);
        } catch (Exception e) {
            log.warn("ProjectConventionIngest: 删除旧向量失败（继续写入）: {}", e.getMessage());
        }

        String normalizedVersionId = IdUtil.normalizeId(versionId);
        try {
            int stored = vectorizationService.embedDocument(
                    projectId, normalizedVersionId, CHUNK_TYPE, SOURCE_URI,
                    markdown, CHUNK_MAX_CHARS, CHUNK_OVERLAP, "bge-m3");
            log.info("ProjectConventionIngest: 项目约定入库完成 projectId={}, versionId={}, chunks={}",
                    projectId, versionId, stored);
        } catch (Exception e) {
            log.warn("ProjectConventionIngest: 向量化失败 projectId={}: {}", projectId, e.getMessage());
        }
    }

    /**
     * 拼装项目约定 Markdown 文档：技术栈 + 分层规范 + 命名约定。
     * 各部分独立 try/catch，保证单部分失败不影响整体。
     */
    private String buildConventionMarkdown(String projectId, String versionId, List<CodeRepo> repos) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 项目约定\n\n");

        // 1. 技术栈
        appendTechStack(sb, projectId, repos);

        // 2. 分层规范 + 3. 命名约定（共用一次图谱查询结果）
        appendLayerAndNamingConvention(sb, projectId, versionId);

        return sb.toString();
    }

    /**
     * 追加技术栈章节：优先从 Project.techStack（JSONB 字符串）解析；
     * 为空时从仓库的 pom.xml / package.json 解析关键依赖。
     */
    private void appendTechStack(StringBuilder sb, String projectId, List<CodeRepo> repos) {
        sb.append("## 技术栈\n\n");
        Project project = null;
        try {
            project = projectRepository.selectById(projectId);
        } catch (Exception e) {
            log.warn("ProjectConventionIngest: 查询项目失败 projectId={}: {}", projectId, e.getMessage());
        }
        String techStack = project != null ? project.getTechStack() : null;

        if (techStack != null && !techStack.isBlank()) {
            // 优先使用已配置的 techStack 字段
            appendTechStackFromField(sb, techStack);
        } else if (repos != null && !repos.isEmpty()) {
            // techStack 为空时从 pom.xml / package.json 解析
            appendTechStackFromRepos(sb, repos);
        } else {
            sb.append("（项目未配置 techStack 字段，且无可用仓库目录）\n\n");
        }
    }

    /**
     * 从 Project.techStack 字段（JSON 对象/数组/纯文本）格式化输出。
     */
    private void appendTechStackFromField(StringBuilder sb, String techStack) {
        String trimmed = techStack.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(trimmed);
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                boolean hasField = false;
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String value = entry.getValue().isNull() ? "" : entry.getValue().asText();
                    sb.append("- ").append(entry.getKey()).append("：").append(value).append("\n");
                    hasField = true;
                }
                if (!hasField) {
                    sb.append("（techStack 为空 JSON 对象）\n");
                }
            } catch (Exception e) {
                sb.append("```\n").append(techStack).append("\n```\n");
            }
        } else if (trimmed.startsWith("[")) {
            try {
                JsonNode node = objectMapper.readTree(trimmed);
                for (JsonNode item : node) {
                    sb.append("- ").append(item.asText()).append("\n");
                }
            } catch (Exception e) {
                sb.append("```\n").append(techStack).append("\n```\n");
            }
        } else {
            sb.append("```\n").append(techStack).append("\n```\n");
        }
        sb.append("\n");
    }

    /**
     * 从仓库的 pom.xml（Java）和 package.json（前端）解析技术栈。
     * 对每个 repo，按 backendSubPath/localPath 查找 pom.xml，按 frontendSubPath/localPath 查找 package.json。
     */
    private void appendTechStackFromRepos(StringBuilder sb, List<CodeRepo> repos) {
        boolean foundAny = false;
        for (CodeRepo repo : repos) {
            String localPath = repo.getLocalPath();
            if (localPath == null || localPath.isBlank()) {
                continue;
            }
            // 后端：pom.xml
            String backendSub = repo.getBackendSubPath();
            Path backendDir = (backendSub != null && !backendSub.isBlank())
                    ? Paths.get(localPath, backendSub) : Paths.get(localPath);
            Path pomPath = backendDir.resolve("pom.xml");
            if (Files.exists(pomPath)) {
                foundAny = true;
                sb.append("### 后端（").append(repo.getRepoName() != null ? repo.getRepoName() : "backend").append("）\n\n");
                String pomInfo = parsePomXml(pomPath);
                if (pomInfo != null && !pomInfo.isBlank()) {
                    sb.append(pomInfo);
                } else {
                    sb.append("（pom.xml 解析无关键依赖）\n");
                }
                sb.append("\n");
            }

            // 前端：package.json
            String frontendSub = repo.getFrontendSubPath();
            Path frontendDir = (frontendSub != null && !frontendSub.isBlank())
                    ? Paths.get(localPath, frontendSub) : Paths.get(localPath);
            Path pkgPath = frontendDir.resolve("package.json");
            if (Files.exists(pkgPath)) {
                foundAny = true;
                sb.append("### 前端（").append(repo.getRepoName() != null ? repo.getRepoName() : "frontend").append("）\n\n");
                String pkgInfo = parsePackageJson(pkgPath);
                if (pkgInfo != null && !pkgInfo.isBlank()) {
                    sb.append(pkgInfo);
                } else {
                    sb.append("（package.json 解析无关键依赖）\n");
                }
                sb.append("\n");
            }
        }
        if (!foundAny) {
            sb.append("（未在仓库目录中找到 pom.xml 或 package.json）\n\n");
        }
    }

    /**
     * 解析 pom.xml，提取框架（Spring Boot）、ORM（MyBatis/JPA）、数据库驱动等关键依赖。
     * 使用 Java 内置 javax.xml.parser，无需额外依赖。
     *
     * @return Markdown 格式的依赖列表（每行 "- key：value"），解析失败返回 null
     */
    String parsePomXml(Path pomPath) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 禁用外部实体解析，防止 XXE
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomPath.toFile());
            doc.getDocumentElement().normalize();

            // 提取 <properties> 中的版本属性（如 spring-boot.version）
            Map<String, String> props = new LinkedHashMap<>();
            NodeList propNodes = doc.getElementsByTagName("properties");
            if (propNodes.getLength() > 0 && propNodes.item(0) instanceof Element propElem) {
                NodeList children = propElem.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element pe) {
                        props.put(pe.getTagName(), pe.getTextContent().trim());
                    }
                }
            }

            // 提取 <parent>（Spring Boot parent）
            StringBuilder result = new StringBuilder();
            NodeList parentNodes = doc.getElementsByTagName("parent");
            if (parentNodes.getLength() > 0 && parentNodes.item(0) instanceof Element parentElem) {
                String groupId = getChildText(parentElem, "groupId");
                String version = getChildText(parentElem, "version");
                if (groupId != null && groupId.contains("springframework.boot")) {
                    result.append("- 框架：Spring Boot ").append(resolveVersion(version, props)).append("\n");
                }
            }

            // 遍历 <dependency>，匹配关键依赖
            boolean hasMybatis = false, hasJpa = false;
            String dbType = null;
            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                if (!(depNodes.item(i) instanceof Element dep)) continue;
                String artifactId = getChildText(dep, "artifactId");
                if (artifactId == null) continue;
                String depVersion = getChildText(dep, "version");

                if (artifactId.contains("mybatis-plus") || artifactId.contains("mybatis-spring")) {
                    hasMybatis = true;
                    result.append("- ORM：MyBatis");
                    if (depVersion != null) result.append(" ").append(resolveVersion(depVersion, props));
                    result.append("\n");
                } else if (artifactId.contains("mybatis") && !hasMybatis) {
                    hasMybatis = true;
                    result.append("- ORM：MyBatis\n");
                } else if (artifactId.contains("data-jpa") || artifactId.contains("hibernate")) {
                    hasJpa = true;
                    result.append("- ORM：JPA/Hibernate\n");
                } else if (artifactId.contains("mysql-connector")) {
                    dbType = "MySQL";
                } else if (artifactId.contains("postgresql")) {
                    dbType = "PostgreSQL";
                } else if (artifactId.contains("spring-boot-starter-web")) {
                    result.append("- Web 框架：Spring Web\n");
                } else if (artifactId.contains("spring-boot-starter-actuator")) {
                    result.append("- 监控：Spring Actuator\n");
                } else if (artifactId.contains("redis") || artifactId.contains("jedis") || artifactId.contains("lettuce")) {
                    result.append("- 缓存：Redis\n");
                }
            }
            if (dbType != null) {
                result.append("- 数据库：").append(dbType).append("\n");
            }
            return result.length() > 0 ? result.toString() : null;
        } catch (Exception e) {
            log.warn("ProjectConventionIngest: 解析 pom.xml 失败 {}: {}", pomPath, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 package.json，提取前端框架（Vue/React）、UI 组件库、构建工具等关键依赖。
     *
     * @return Markdown 格式的依赖列表，解析失败返回 null
     */
    String parsePackageJson(Path pkgPath) {
        try {
            String content = Files.readString(pkgPath);
            JsonNode root = objectMapper.readTree(content);
            StringBuilder result = new StringBuilder();

            // 合并 dependencies + devDependencies
            Map<String, String> allDeps = new LinkedHashMap<>();
            addDeps(allDeps, root.get("dependencies"));
            addDeps(allDeps, root.get("devDependencies"));

            for (Map.Entry<String, String> entry : allDeps.entrySet()) {
                String name = entry.getKey();
                String version = entry.getValue();
                if (name.equals("vue")) {
                    result.append("- 前端框架：Vue ").append(version).append("\n");
                } else if (name.equals("react")) {
                    result.append("- 前端框架：React ").append(version).append("\n");
                } else if (name.equals("@angular/core")) {
                    result.append("- 前端框架：Angular ").append(version).append("\n");
                } else if (name.equals("element-plus") || name.equals("element-ui")) {
                    result.append("- UI 组件库：Element ").append(version).append("\n");
                } else if (name.equals("ant-design-vue") || name.equals("antd")) {
                    result.append("- UI 组件库：Ant Design ").append(version).append("\n");
                } else if (name.equals("@arco-design/web-vue")) {
                    result.append("- UI 组件库：Arco Design ").append(version).append("\n");
                } else if (name.equals("vite")) {
                    result.append("- 构建工具：Vite ").append(version).append("\n");
                } else if (name.equals("webpack")) {
                    result.append("- 构建工具：Webpack ").append(version).append("\n");
                } else if (name.equals("axios")) {
                    result.append("- HTTP 客户端：Axios ").append(version).append("\n");
                } else if (name.equals("pinia") || name.equals("vuex")) {
                    result.append("- 状态管理：").append(name.equals("pinia") ? "Pinia " : "Vuex ").append(version).append("\n");
                } else if (name.equals("vue-router") || name.equals("react-router-dom")) {
                    result.append("- 路由：").append(name).append(" ").append(version).append("\n");
                }
            }
            return result.length() > 0 ? result.toString() : null;
        } catch (Exception e) {
            log.warn("ProjectConventionIngest: 解析 package.json 失败 {}: {}", pkgPath, e.getMessage());
            return null;
        }
    }

    /** 把 JsonNode dependencies/devDependencies 的键值对加入 map */
    private void addDeps(Map<String, String> target, JsonNode depsNode) {
        if (depsNode == null || !depsNode.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = depsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            target.put(e.getKey(), e.getValue().asText());
        }
    }

    /** 获取 XML 元素的直接子元素文本值 */
    private String getChildText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    /** 解析 Maven 版本占位符（如 ${spring-boot.version}） */
    private String resolveVersion(String version, Map<String, String> props) {
        if (version == null) return "";
        if (version.startsWith("${") && version.endsWith("}")) {
            String key = version.substring(2, version.length() - 1);
            return props.getOrDefault(key, version);
        }
        return version;
    }

    /**
     * 追加分层规范与命名约定章节：从图谱节点统计推导。
     * 查询 Controller/Service/Mapper 三类节点，统计数量与命名后缀符合度。
     */
    private void appendLayerAndNamingConvention(StringBuilder sb, String projectId, String versionId) {
        // 分层规范
        sb.append("## 分层规范\n\n");
        sb.append("项目采用标准分层架构（从扫描的包结构与图谱节点推导）：\n");

        Map<String, LayerStat> stats = new LinkedHashMap<>();
        for (String nodeType : LAYER_SUFFIX.keySet()) {
            stats.put(nodeType, queryLayerStat(projectId, versionId, nodeType));
        }

        for (Map.Entry<String, LayerStat> entry : stats.entrySet()) {
            String nodeType = entry.getKey();
            LayerStat stat = entry.getValue();
            String suffix = LAYER_SUFFIX.get(nodeType);
            sb.append("- ").append(nodeType).append(" 层");
            if (stat.count > 0) {
                sb.append("：").append(stat.count).append(" 个");
                if (!stat.samples.isEmpty()) {
                    sb.append("（示例：").append(String.join("、", stat.samples)).append("）");
                }
            } else {
                sb.append("（未检测到）");
            }
            sb.append("\n");
        }
        sb.append("- Entity 层：数据模型对象\n");
        sb.append("\n");

        // 命名约定
        sb.append("## 命名约定\n\n");
        sb.append("从已有代码统计的类名后缀约定：\n");
        for (Map.Entry<String, LayerStat> entry : stats.entrySet()) {
            String nodeType = entry.getKey();
            LayerStat stat = entry.getValue();
            String suffix = LAYER_SUFFIX.get(nodeType);
            sb.append("- ").append(nodeType).append(" 类：以 ").append(suffix).append(" 结尾");
            if (stat.count > 0) {
                int matchRate = stat.count > 0 ? (stat.suffixMatched * 100 / stat.count) : 0;
                sb.append("（").append(stat.suffixMatched).append("/").append(stat.count)
                  .append(" 符合，").append(matchRate).append("%）");
            } else {
                sb.append("（无样本）");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    /**
     * 查询某类节点并统计：总数、命名后缀符合数、样本名称。
     */
    private LayerStat queryLayerStat(String projectId, String versionId, String nodeType) {
        LayerStat stat = new LayerStat();
        String suffix = LAYER_SUFFIX.get(nodeType);
        try {
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                    projectId, versionId, nodeType, null, null, null, NODE_QUERY_LIMIT);
            if (nodes == null || nodes.isEmpty()) {
                return stat;
            }
            for (GraphNode node : nodes) {
                String name = node.getNodeName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                stat.count++;
                if (name.endsWith(suffix)) {
                    stat.suffixMatched++;
                }
                if (stat.samples.size() < SAMPLE_NAME_LIMIT) {
                    stat.samples.add(name);
                }
            }
        } catch (Exception e) {
            log.warn("ProjectConventionIngest: 查询 {} 节点统计失败 projectId={}: {}",
                    nodeType, projectId, e.getMessage());
        }
        return stat;
    }

    /** 单类节点的统计结果 */
    private static class LayerStat {
        int count;
        int suffixMatched;
        final java.util.List<String> samples = new java.util.ArrayList<>();
    }
}
