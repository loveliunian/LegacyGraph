package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.systemoverview.LayerMappingDTO;
import io.github.legacygraph.dto.systemoverview.SystemOverviewDTO;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 系统关系总览服务 — 投影业务/功能/代码/数据四层关系。
 * <p>
 * 落地 {@code doc/系统关系总览/04-落地实施计划.md} 阶段2 + M2 修复。
 * </p>
 * <p>
 * 动态投影策略：优先从 {@code lg_knowledge_claim} 查询可用 Claim（CONFIRMED/PENDING_CONFIRM），
 * 按 (subjectType, predicate, objectType) 聚合为四层映射。若无 Claim 数据则回退到内置映射。
 * </p>
 * <p>
 * 业务域识别规则对齐 04 §11 修正：按 Controller 类名→业务域映射，
 * 不用纯 API 前缀（因 /lg/projects/{projectId} 下有多个 Controller 共享前缀）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemOverviewService {

    private final KnowledgeClaimService knowledgeClaimService;
    private final Neo4jGraphDao graphDao;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 自身分析项目标识：仅该项目在无 Claim 数据时才回退到内置（LegacyGraph 自身）映射。 */
    private static final String SELF_PROJECT_ID = "self";

    /**
     * 全量四层关系总览。
     * <p>
     * M2 修复：优先从 lg_knowledge_claim 动态投影，无 Claim 数据时回退到内置映射。
     * </p>
     * <p>
     * 性能：结论性总览低频变更，结果缓存到 Redis（cacheNames=system-overview），
     * 写入侧（{@code SystemOverviewIngestService.ingest}）显式失效，短 TTL 兜底防漏失效。
     * </p>
     */
    @Cacheable(cacheNames = "system-overview",
            key = "#projectId + ':' + (#versionId != null ? #versionId : 'default')")
    public SystemOverviewDTO getOverview(String projectId, String versionId) {
        List<LayerMappingDTO> mappings = buildDynamicMappings(projectId, versionId);
        int totalDomains = (int) mappings.stream()
                .map(LayerMappingDTO::getBusinessDomain)
                .distinct()
                .count();
        return SystemOverviewDTO.builder()
                .projectId(projectId)
                .versionId(versionId)
                .mappings(mappings)
                .corePaths(buildCorePaths(mappings))
                .totalDomains(totalDomains)
                .build();
    }

    /**
     * 按业务域查询（支持模糊匹配）。
     */
    public List<LayerMappingDTO> getDomain(String projectId, String versionId, String domainId) {
        return buildDynamicMappings(projectId, versionId).stream()
                .filter(m -> domainId == null || domainId.isBlank()
                        || m.getBusinessDomain().contains(domainId)
                        || m.getCapability().contains(domainId))
                .collect(Collectors.toList());
    }

    /**
     * 核心贯穿链路（业务→功能→代码→数据）。
     */
    public List<String> getPaths(String projectId, String versionId) {
        return buildCorePaths(buildDynamicMappings(projectId, versionId));
    }

    /**
     * 按起点/终点过滤贯穿链路。
     */
    public List<String> getPaths(String projectId, String versionId, String from, String to) {
        if ((from == null || from.isBlank()) && (to == null || to.isBlank())) {
            return getPaths(projectId, versionId);
        }
        return buildDynamicMappings(projectId, versionId).stream()
                .map(this::toPath)
                .filter(path -> matches(path, from) && matches(path, to))
                .collect(Collectors.toList());
    }

    /**
     * 生成系统关系总览 Markdown 报告（对齐 02 结构）。
     */
    public String generateMarkdown(String projectId, String versionId) {
        // P0-4 统一真值口径：正文仅 CONFIRMED，附录 PENDING_CONFIRM/INFERRED，REJECTED/STALE/CONFLICTED 不进入结论
        List<KnowledgeClaim> confirmedClaims = loadConfirmedClaims(projectId, versionId);
        List<KnowledgeClaim> pendingClaims = loadPendingClaims(projectId, versionId);
        // 向后兼容：统计与 Mermaid 仍使用合并后的可用 Claim（CONFIRMED + PENDING_CONFIRM/INFERRED）
        List<KnowledgeClaim> claims = new ArrayList<>();
        claims.addAll(confirmedClaims);
        claims.addAll(pendingClaims);
        claims.sort(claimComparator());
        Map<String, Long> statusCounts = safeCountClaimsByStatus(projectId, versionId);
        long totalConfirmed = statusCounts.getOrDefault("CONFIRMED", 0L);
        long totalPending = statusCounts.getOrDefault("PENDING_CONFIRM", 0L)
                + statusCounts.getOrDefault("INFERRED", 0L);
        // 结论区的映射只能来自 CONFIRMED Claim；图谱直查与待确认 Claim 仅用于交互视图和附录，
        // 不能绕过 ReportTruthPolicy 混入业务域映射、贯穿链路和数据表影响面。
        List<LayerMappingDTO> mappings = buildConfirmedMappings(projectId, confirmedClaims);
        StringBuilder sb = new StringBuilder();

        sb.append("# 系统关系总览报告 — 业务/功能/代码/数据\n\n");
        sb.append(String.format("**项目ID:** %s  \n", projectId));
        sb.append(String.format("**版本ID:** %s  \n", versionId == null ? "default" : versionId));
        sb.append(String.format("**生成时间:** %s\n\n", LocalDateTime.now().format(DATE_FORMATTER)));

        sb.append("## 1. 业务域映射总表\n\n");
        sb.append("| 业务域 | 业务能力 | 功能 | Controller | API | 代码 | 数据表 | 关系 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (LayerMappingDTO m : mappings) {
            sb.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %s |\n",
                    n(m.getBusinessDomain()), n(m.getCapability()), n(m.getFeature()),
                    n(m.getController()), n(m.getApiPath()), n(m.getCodeModule()),
                    m.getDataTables() != null ? String.join(",", m.getDataTables()) : "-",
                    n(m.getEdgeType())));
        }

        sb.append("\n## 2. 核心贯穿链路\n\n");
        for (String path : buildCorePaths(mappings)) {
            sb.append("- ").append(path).append("\n");
        }

        appendGraphStatistics(sb, claims);
        appendGraphRelationDetails(sb, confirmedClaims, pendingClaims, totalConfirmed, totalPending);
        appendDomainBreakdown(sb, mappings);
        appendTableImpact(sb, mappings);
        appendMermaidGraph(sb, claims);
        appendCoreThroughChain(sb, projectId, versionId);
        appendGraphStatsSummary(sb, projectId, versionId);
        appendModuleDependencyMermaid(sb, projectId, versionId);

        sb.append("\n## 11. QA 文档基础\n\n");
        sb.append("- 本报告沉淀资料扫描后的业务/功能/代码/数据关系，可作为后续 QA 文档生成的事实基础。\n");
        sb.append("- QA 文档应优先引用业务域、功能、Controller/API、代码模块、数据表与核心贯穿链路。\n");
        sb.append("- 对未覆盖或低置信关系，应回到 Claim、证据或图谱查询确认，避免把推断写成已确认事实。\n\n");
        sb.append("### 四层定义\n\n");
        sb.append("- **业务层**：BusinessDomain/Process/Object/Rule/Role（为什么存在）\n");
        sb.append("- **功能层**：Feature/Page/Button/Permission/ApiEndpoint（如何触发）\n");
        sb.append("- **代码层**：Controller/Service/Method/Mapper/SqlStatement（由什么实现）\n");
        sb.append("- **数据层**：Table/Column/Index（落到什么表）\n");
        sb.append("\n> 核心链路：BusinessDomain CONTAINS Feature EXPOSED_BY ApiEndpoint HANDLED_BY Controller ");
        sb.append("IMPLEMENTED_BY Service CALLS Mapper EXECUTES SqlStatement READS/WRITES Table HAS_COLUMN Column\n");

        return sb.toString();
    }

    // ──────────── 动态投影（M2 修复）────────────

    /**
     * 从图谱直接查询四层映射，替代 knowledge_claim 回退。
     * <p>
     * 优先从 Neo4j 图谱查询 BusinessDomain → Feature/BusinessProcess → Controller/Service → Table
     * 的四层结构。若图谱无数据则回退到 Claim 投影，再回退到内置映射。
     * </p>
     */
    private List<LayerMappingDTO> buildGraphBasedMappings(String projectId, String versionId) {
        try {
            List<Map<String, Object>> domainContains = graphDao.businessDomainContains(projectId, versionId);
            List<Map<String, Object>> apiRelations = graphDao.apiImplementationRelations(projectId, versionId);

            if ((apiRelations == null || apiRelations.isEmpty())
                    && (domainContains == null || domainContains.isEmpty())) {
                log.debug("No graph data for projectId={}, versionId={}, falling back to claims", projectId, versionId);
                return Collections.emptyList();
            }

            // 构建 API key/name → 实现关系索引
            Map<String, Map<String, Object>> apiByName = new HashMap<>();
            Map<String, Map<String, Object>> apiByKey = new HashMap<>();
            if (apiRelations != null) {
                for (Map<String, Object> api : apiRelations) {
                    String key = toStr(api.get("nodeKey"));
                    String name = toStr(api.get("displayName"));
                    if (key != null) apiByKey.put(key, api);
                    if (name != null) apiByName.put(name, api);
                }
            }

            // 构建 feature → domain 映射
            Map<String, String> featureToDomain = new HashMap<>();
            if (domainContains != null) {
                for (Map<String, Object> row : domainContains) {
                    String domain = toStr(row.get("domainDisplayName"));
                    if (domain == null) domain = toStr(row.get("domainName"));
                    List<?> features = (List<?>) row.get("features");
                    if (features != null && domain != null) {
                        for (Object f : features) {
                            String fname = toStr(f);
                            if (fname != null) {
                                featureToDomain.putIfAbsent(fname, domain);
                            }
                        }
                    }
                }
            }

            List<LayerMappingDTO> mappings = new ArrayList<>();
            if (apiRelations != null) {
                for (Map<String, Object> api : apiRelations) {
                    String apikey = toStr(api.get("nodeKey"));
                    String display = toStr(api.get("displayName"));
                    List<String> controllers = toStrList(api.get("controllers"));
                    List<String> services = toStrList(api.get("services"));
                    List<String> tables = toStrList(api.get("tables"));

                    // 匹配业务域
                    String domain = featureToDomain.get(apikey);
                    if (domain == null) domain = featureToDomain.get(display);
                    if (domain == null && !controllers.isEmpty()) {
                        domain = controllers.get(0) + " 域";
                    }
                    if (domain == null) domain = "未分类";

                    String controller = controllers.isEmpty() ? null : controllers.get(0);
                    String service = services.isEmpty() ? null : services.get(0);

                    mappings.add(mapping(domain, display, display,
                            controller, apikey, service,
                            tables.isEmpty() ? null : tables, "GRAPH"));
                }
            }

            // 若图谱查不到 API 实现关系，但有业务域数据，仍输出域级映射
            if (mappings.isEmpty() && domainContains != null) {
                for (Map<String, Object> row : domainContains) {
                    String domain = toStr(row.get("domainDisplayName"));
                    if (domain == null) domain = toStr(row.get("domainName"));
                    List<?> features = (List<?>) row.get("features");
                    if (features != null) {
                        for (Object f : features) {
                            String fname = toStr(f);
                            mappings.add(mapping(domain, fname, fname,
                                    null, null, null, null, "GRAPH"));
                        }
                    }
                }
            }

            if (mappings.isEmpty()) {
                return Collections.emptyList();
            }
            log.info("Graph-based projection: {} mappings from graph (projectId={}, versionId={})",
                    mappings.size(), projectId, versionId);
            return mappings;
        } catch (Exception e) {
            log.warn("Failed to build graph-based mappings, falling back to claims: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从 lg_knowledge_claim 动态投影四层映射。
     * <p>
     * 查询 CONFIRMED/PENDING_CONFIRM 状态的 Claim，按 subjectKey 聚合为 LayerMappingDTO。
     * 若无 Claim 数据则回退到内置映射。
     * </p>
     */
    private List<LayerMappingDTO> buildDynamicMappings(String projectId, String versionId) {
        // 优先从图谱直接查询四层结构
        List<LayerMappingDTO> graphMappings = buildGraphBasedMappings(projectId, versionId);
        if (!graphMappings.isEmpty()) {
            return graphMappings;
        }

        try {
            List<KnowledgeClaim> claims = knowledgeClaimService.listClaims(
                    projectId, versionId, null, null, null, null, 500);

            if (claims == null || claims.isEmpty()) {
                log.debug("No claims found for projectId={}, falling back to builtin mappings", projectId);
                return fallbackMappings(projectId);
            }

            claims = claims.stream()
                    .filter(this::isUsableOverviewClaim)
                    .collect(Collectors.toList());
            if (claims.isEmpty()) {
                log.debug("No usable overview claims found for projectId={}, falling back to builtin mappings", projectId);
                return fallbackMappings(projectId);
            }

            List<LayerMappingDTO> mappings = buildClaimMappings(claims);
            if (mappings.isEmpty()) {
                log.debug("Dynamic projection yielded no mappings, falling back to builtin");
                return fallbackMappings(projectId);
            }

            log.info("Dynamic projection: {} mappings from {} claims", mappings.size(), claims.size());
            return mappings;

        } catch (Exception e) {
            log.warn("Failed to build dynamic mappings, falling back to builtin: {}", e.getMessage());
            return fallbackMappings(projectId);
        }
    }

    /**
     * 报告正文只从已确认 Claim 构建映射，禁止图谱直查与 PENDING_CONFIRM 回退绕过真值策略。
     */
    private List<LayerMappingDTO> buildConfirmedMappings(String projectId, List<KnowledgeClaim> confirmedClaims) {
        List<LayerMappingDTO> mappings = buildClaimMappings(confirmedClaims);
        return mappings.isEmpty() ? fallbackMappings(projectId) : mappings;
    }

    /** 从给定 Claim 集合投影四层映射；调用方负责先决定真值范围。 */
    private List<LayerMappingDTO> buildClaimMappings(List<KnowledgeClaim> claims) {
        if (claims == null || claims.isEmpty()) {
            return Collections.emptyList();
        }

            // 按 subjectKey 聚合
            Map<String, List<KnowledgeClaim>> bySubject = claims.stream()
                    .filter(c -> c.getSubjectKey() != null)
                    .collect(Collectors.groupingBy(KnowledgeClaim::getSubjectKey));

            // 预计算：所有 ApiEndpoint subjectKey + Controller -> 首个 API 路径。
            // 避免每个 Feature 都全表扫描 ApiEndpoint HANDLED_BY Controller（O(n²)）。
            Set<String> apiEndpointKeys = new HashSet<>();
            Map<String, String> controllerToFirstApi = new HashMap<>();
            for (List<KnowledgeClaim> cl : bySubject.values()) {
                for (KnowledgeClaim c : cl) {
                    if (!"ApiEndpoint".equals(c.getSubjectType())) {
                        continue;
                    }
                    apiEndpointKeys.add(c.getSubjectKey());
                    if ("HANDLED_BY".equals(c.getPredicate()) && c.getObjectKey() != null) {
                        controllerToFirstApi.putIfAbsent(c.getObjectKey(), c.getSubjectKey());
                    }
                }
            }

            List<LayerMappingDTO> mappings = new ArrayList<>();

            // 遍历所有 BusinessDomain CONTAINS Feature 的 Claim —— 每个 Feature 一行映射。
            // 此前按 subjectKey 分组后每域只 findFirst 取一条，会把同域其余 Feature 全部折叠丢失。
            for (KnowledgeClaim domainClaim : claims) {
                if (!"BusinessDomain".equals(domainClaim.getSubjectType())
                        || !"CONTAINS".equals(domainClaim.getPredicate())
                        || !"Feature".equals(domainClaim.getObjectType())) {
                    continue;
                }

                String domain = domainClaim.getSubjectKey();
                String feature = domainClaim.getObjectKey();

                // 查找 Feature 相关的 claim
                List<KnowledgeClaim> featureClaims = bySubject.getOrDefault(feature, Collections.emptyList());

                String controller = null;
                String codeModule = null;
                List<String> tables = new ArrayList<>();

                for (KnowledgeClaim fc : featureClaims) {
                    if ("IMPLEMENTED_BY".equals(fc.getPredicate()) && "Controller".equals(fc.getObjectType())) {
                        controller = fc.getObjectKey();
                    } else if ("USES".equals(fc.getPredicate()) && "Service".equals(fc.getObjectType())) {
                        codeModule = fc.getObjectKey();
                    }
                }

                // Feature 无直接 USES Service 时，回退到 Controller -> Service
                // （ingest 写的是 Controller IMPLEMENTED_BY Service，故同时接受 IMPLEMENTED_BY / HANDLED_BY）
                if (codeModule == null && controller != null) {
                    List<KnowledgeClaim> controllerClaims = bySubject.getOrDefault(controller, Collections.emptyList());
                    for (KnowledgeClaim cc : controllerClaims) {
                        if ("Controller".equals(cc.getSubjectType())
                                && ("IMPLEMENTED_BY".equals(cc.getPredicate())
                                    || "HANDLED_BY".equals(cc.getPredicate()))
                                && "Service".equals(cc.getObjectType())) {
                            codeModule = cc.getObjectKey();
                            break;
                        }
                    }
                }

                // apiPath：Feature 本身就是 API 端点时（feature key == 某 ApiEndpoint subjectKey）直接用；
                // 否则取该 Controller 的首个 API（降级，可能并非该 Feature 自身的 API）。
                String apiPath = apiEndpointKeys.contains(feature) ? feature
                        : (controller != null ? controllerToFirstApi.get(controller) : null);

                // 查找 Service READS/WRITES Table
                if (codeModule != null) {
                    List<KnowledgeClaim> serviceClaims = bySubject.getOrDefault(codeModule, Collections.emptyList());
                    for (KnowledgeClaim sc : serviceClaims) {
                        if ("Service".equals(sc.getSubjectType())
                                && ("READS".equals(sc.getPredicate()) || "WRITES".equals(sc.getPredicate()))
                                && "Table".equals(sc.getObjectType())) {
                            if (!tables.contains(sc.getObjectKey())) {
                                tables.add(sc.getObjectKey());
                            }
                        }
                    }
                }

                mappings.add(mapping(domain, feature, feature, controller, apiPath,
                        codeModule, tables.isEmpty() ? null : tables, "DYNAMIC"));
            }

            return mappings;
    }

    // ──────────── 内置映射（对齐 02 §0.1，确定性，CODE 来源）────────────

    /**
     * 无 Claim 数据时的回退策略。
     * <p>
     * 内置映射（{@link #buildBuiltinMappings()}）描述的是 LegacyGraph 自身结构，
     * 仅对自身分析项目（{@value #SELF_PROJECT_ID}）有意义。其他真实业务项目在尚未
     * 生成/导入系统关系总览数据时，返回空列表 —— 避免把本项目（LegacyGraph 自身）
     * 的结构误当作当前项目的结论展示给用户。
     * </p>
     */
    private List<LayerMappingDTO> fallbackMappings(String projectId) {
        if (SELF_PROJECT_ID.equals(projectId)) {
            return buildBuiltinMappings();
        }
        log.debug("Project {} has no system-overview data, returning empty (no self-builtin fallback)", projectId);
        return Collections.emptyList();
    }

    private List<LayerMappingDTO> buildBuiltinMappings() {
        return Arrays.asList(
                mapping("项目与数据源管理", "项目纳管", "project", "ProjectController", "/lg/projects",
                        "ProjectService", Arrays.asList("lg_project", "lg_code_repo", "lg_db_connection", "lg_document"), "BELONGS_TO"),
                mapping("Graphify 集成", "Graphify 作业", "graphify", "GraphifyJobController", "/graphify/jobs",
                        "GraphifyImportService", Arrays.asList("lg_scan_version", "lg_ai_scan_job"), "IMPLEMENTED_BY"),
                mapping("资料扫描", "扫描编排", "scan", "ScanController", "/scan-versions",
                        "ProjectScanner", Arrays.asList("lg_scan_version", "lg_scan_task", "lg_source_asset_snapshot"), "TRIGGERS"),
                mapping("图谱构建与查询", "图谱写入", "graph", "GraphQueryController", "/lg/projects/{projectId}",
                        "EvidenceGraphWriter", Arrays.asList("lg_evidence", "lg_node_evidence", "lg_edge_evidence"), "HAS_EVIDENCE"),
                mapping("事实/证据/知识断言", "知识断言", "fact", "KnowledgeController", "/knowledge",
                        "KnowledgeClaimService", Arrays.asList("lg_knowledge_claim", "lg_gap_task"), "MAPS_TO"),
                mapping("QA 问答", "流式问答", "graph(GraphQa)", "EnhancedQaController", "/qa/ask/stream",
                        "EnhancedQaAgent", Arrays.asList("lg_qa_conversation", "lg_qa_message", "lg_semantic_cache"), "USES"),
                mapping("测试闭环", "测试生成", "test", "TestCaseController", "/lg/projects/{projectId}",
                        "TestCaseAgent", Arrays.asList("lg_test_case", "lg_test_assertion", "lg_test_result"), "VERIFIED_BY"),
                mapping("代码理解", "代码理解编排", "understanding", "CodeUnderstandingController", "/understanding",
                        "CodeUnderstandingOrchestrator", Arrays.asList("lg_agent_run", "lg_tool_run"), "USES"),
                mapping("变更影响与迁移", "变更影响", "change", "ChangeTaskController", "/change-tasks",
                        "ChangeImpactAgent", Arrays.asList("lg_change_task", "lg_patch_file", "lg_pr_task"), "AFFECTS"),
                mapping("报告与度量", "报告导出", "report", "ReportExportController", "/reports",
                        "ReportExportService", Arrays.asList("lg_reports"), "USES"),
                mapping("LLM 与 Agent 管理", "模型网关", "settings", "LlmProviderController", "/llm/providers",
                        "LlmGateway", Arrays.asList("lg_llm_provider", "lg_prompt_run"), "USES"),
                mapping("系统管理", "系统配置", "system", "SystemController", "/lg/system",
                        "SystemService", Arrays.asList("lg_sys_config", "lg_sys_dict", "lg_sys_operation_log"), "BELONGS_TO")
        );
    }

    /**
     * 核心贯穿链路：从四层映射行动态生成，每行若有完整链条则拼接。
     * 格式："业务域 → 能力/功能 → Controller/API → 代码模块 → 数据表"
     * 无映射数据时回退为空列表。
     */
    private List<String> buildCorePaths(List<LayerMappingDTO> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> paths = new ArrayList<>();
        for (LayerMappingDTO m : mappings) {
            StringBuilder sb = new StringBuilder();
            if (notBlankStr(m.getBusinessDomain())) {
                sb.append(m.getBusinessDomain());
            }
            String feature = notBlankStr(m.getCapability()) ? m.getCapability() : m.getFeature();
            if (feature != null) {
                if (sb.length() > 0) sb.append(" → ");
                sb.append(feature);
            }
            if (notBlankStr(m.getController())) {
                if (sb.length() > 0) sb.append(" → ");
                sb.append(m.getController());
            } else if (notBlankStr(m.getApiPath())) {
                if (sb.length() > 0) sb.append(" → ");
                sb.append(m.getApiPath());
            }
            if (notBlankStr(m.getCodeModule())) {
                if (sb.length() > 0) sb.append(" → ");
                sb.append(m.getCodeModule());
            }
            if (m.getDataTables() != null && !m.getDataTables().isEmpty()) {
                if (sb.length() > 0) sb.append(" → ");
                sb.append(String.join(",", m.getDataTables()));
            }
            if (sb.length() > 0) {
                paths.add(sb.toString());
            }
        }
        return paths;
    }

    private boolean notBlankStr(String s) {
        return s != null && !s.isBlank();
    }

    private boolean isUsableOverviewClaim(KnowledgeClaim claim) {
        if (claim == null || claim.getSubjectKey() == null) {
            return false;
        }
        String status = claim.getStatus();
        return status == null || "CONFIRMED".equals(status) || "PENDING_CONFIRM".equals(status);
    }

    private List<KnowledgeClaim> loadUsableOverviewClaims(String projectId, String versionId) {
        try {
            List<KnowledgeClaim> claims = knowledgeClaimService.listClaims(
                    projectId, versionId, null, null, null, null, 500);
            if (claims == null || claims.isEmpty()) {
                return Collections.emptyList();
            }
            return claims.stream()
                    .filter(this::isUsableOverviewClaim)
                    .sorted(claimComparator())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load overview claims for markdown: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 加载正文用 Claim — 仅 CONFIRMED（P0-4 真值口径）。
     */
    private List<KnowledgeClaim> loadConfirmedClaims(String projectId, String versionId) {
        try {
            List<KnowledgeClaim> claims = knowledgeClaimService.listClaims(
                    projectId, versionId, null, null, "CONFIRMED", null, 500);
            if (claims == null || claims.isEmpty()) {
                return Collections.emptyList();
            }
            return claims.stream()
                    .filter(ReportTruthPolicy.forBody())
                    .sorted(claimComparator())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load confirmed claims: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 加载附录用 Claim — PENDING_CONFIRM / INFERRED（P0-4 真值口径）。
     * <p>listClaims 的 status 为单值过滤，故分两次查询后合并。</p>
     */
    private List<KnowledgeClaim> loadPendingClaims(String projectId, String versionId) {
        try {
            List<KnowledgeClaim> pending = knowledgeClaimService.listClaims(
                    projectId, versionId, null, null, "PENDING_CONFIRM", null, 500);
            List<KnowledgeClaim> inferred = knowledgeClaimService.listClaims(
                    projectId, versionId, null, null, "INFERRED", null, 500);
            List<KnowledgeClaim> result = new ArrayList<>();
            if (pending != null) result.addAll(pending);
            if (inferred != null) result.addAll(inferred);
            return result.stream()
                    .filter(ReportTruthPolicy.forAppendix())
                    .sorted(claimComparator())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load pending/inferred claims: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 按状态统计 Claim 数量，用于截断覆盖率提示；异常或空时返回空 Map。 */
    private Map<String, Long> safeCountClaimsByStatus(String projectId, String versionId) {
        try {
            Map<String, Long> counts = knowledgeClaimService.countClaimsByStatus(projectId, versionId);
            return counts != null ? counts : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to count claims by status: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private void appendGraphStatistics(StringBuilder sb, List<KnowledgeClaim> claims) {
        sb.append("\n## 3. 图谱关系统计\n\n");
        if (claims.isEmpty()) {
            sb.append("暂无可用 Claim 关系；请先完成资料扫描或图谱导入。\n");
            return;
        }

        sb.append("### 3.1 按关系类型\n\n");
        sb.append("| 关系类型 | 数量 |\n");
        sb.append("|---|---:|\n");
        claims.stream()
                .collect(Collectors.groupingBy(c -> n(c.getPredicate()), TreeMap::new, Collectors.counting()))
                .forEach((predicate, count) -> sb.append(String.format("| %s | %d |\n", predicate, count)));

        sb.append("\n### 3.2 按节点类型\n\n");
        sb.append("| 节点类型 | 作为起点 | 作为终点 |\n");
        sb.append("|---|---:|---:|\n");
        Set<String> nodeTypes = new TreeSet<>();
        claims.forEach(c -> {
            nodeTypes.add(n(c.getSubjectType()));
            nodeTypes.add(n(c.getObjectType()));
        });
        for (String nodeType : nodeTypes) {
            long asSubject = claims.stream().filter(c -> Objects.equals(n(c.getSubjectType()), nodeType)).count();
            long asObject = claims.stream().filter(c -> Objects.equals(n(c.getObjectType()), nodeType)).count();
            sb.append(String.format("| %s | %d | %d |\n", nodeType, asSubject, asObject));
        }
    }

    /**
     * 图谱关系明细 — 按 P0-4 真值口径分区生成。
     * <p>
     * 正文（已确认结论）仅展示 CONFIRMED Claim；附录展示 PENDING_CONFIRM/INFERRED Claim 并明确标识。
     * 截断时追加覆盖率提示。
     * </p>
     */
    private void appendGraphRelationDetails(StringBuilder sb, List<KnowledgeClaim> confirmedClaims,
                                            List<KnowledgeClaim> pendingClaims,
                                            long totalConfirmed, long totalPending) {
        sb.append("\n## 4. 图谱关系明细\n\n");

        // 正文（已确认结论）
        sb.append("### 正文（已确认结论）\n\n");
        if (confirmedClaims.isEmpty()) {
            sb.append("暂无已确认的 Claim 明细。\n");
        } else {
            appendClaimTable(sb, confirmedClaims);
            appendCoverageHint(sb, confirmedClaims.size(), totalConfirmed);
        }

        // 附录（待确认/推断）
        sb.append("\n### 附录（待确认/推断）\n\n");
        sb.append("> 以下关系尚未经确认，仅供参考\n\n");
        if (pendingClaims.isEmpty()) {
            sb.append("暂无待确认/推断的 Claim。\n");
        } else {
            appendClaimTable(sb, pendingClaims);
            appendCoverageHint(sb, pendingClaims.size(), totalPending);
        }
    }

    private void appendClaimTable(StringBuilder sb, List<KnowledgeClaim> claims) {
        sb.append("| 起点类型 | 起点 | 关系 | 终点类型 | 终点 | 来源 | 状态 | 置信度 |\n");
        sb.append("|---|---|---|---|---|---|---|---:|\n");
        for (KnowledgeClaim claim : claims) {
            sb.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %s |\n",
                    n(claim.getSubjectType()), n(claim.getSubjectKey()), n(claim.getPredicate()),
                    n(claim.getObjectType()), n(claim.getObjectKey()), n(claim.getSourceType()),
                    n(claim.getStatus()), formatConfidence(claim.getConfidence())));
        }
    }

    /** 截断覆盖率提示：当展示数小于总数时追加。 */
    private void appendCoverageHint(StringBuilder sb, int shown, long total) {
        if (total > 0 && shown < total) {
            sb.append(String.format("\n> ⚠ 覆盖 %d/%d，尚有 %d 条未展示\n", shown, total, total - shown));
        }
    }

    private void appendDomainBreakdown(StringBuilder sb, List<LayerMappingDTO> mappings) {
        sb.append("\n## 5. 按业务域拆解\n\n");
        if (mappings.isEmpty()) {
            sb.append("暂无业务域映射；请先完成系统关系总览生成。\n");
            return;
        }

        Map<String, List<LayerMappingDTO>> byDomain = mappings.stream()
                .collect(Collectors.groupingBy(m -> n(m.getBusinessDomain()), TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<LayerMappingDTO>> entry : byDomain.entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n\n");
            for (LayerMappingDTO mapping : entry.getValue()) {
                sb.append("- 功能：`").append(n(mapping.getCapability())).append("`\n");
                sb.append("- API：`").append(n(mapping.getApiPath())).append("`\n");
                sb.append("- Controller：`").append(n(mapping.getController())).append("`\n");
                sb.append("- 代码模块：`").append(n(mapping.getCodeModule())).append("`\n");
                sb.append("- 数据表：`").append(mapping.getDataTables() == null || mapping.getDataTables().isEmpty()
                        ? "-"
                        : String.join(",", mapping.getDataTables())).append("`\n");
                sb.append("- 链路：").append(toPath(mapping)).append("\n\n");
            }
        }
    }

    private void appendTableImpact(StringBuilder sb, List<LayerMappingDTO> mappings) {
        sb.append("\n## 6. 数据表影响面\n\n");
        List<LayerMappingDTO> tableMappings = mappings.stream()
                .filter(m -> m.getDataTables() != null && !m.getDataTables().isEmpty())
                .toList();
        if (tableMappings.isEmpty()) {
            sb.append("暂无数据表关联。\n");
            return;
        }

        sb.append("| 数据表 | 业务域 | 功能/API | Controller | Service/代码模块 |\n");
        sb.append("|---|---|---|---|---|\n");
        for (LayerMappingDTO mapping : tableMappings) {
            for (String table : mapping.getDataTables()) {
                sb.append(String.format("| %s | %s | %s | %s | %s |\n",
                        n(table), n(mapping.getBusinessDomain()), n(firstNonBlank(mapping.getApiPath(), mapping.getCapability())),
                        n(mapping.getController()), n(mapping.getCodeModule())));
            }
        }
    }

    private void appendMermaidGraph(StringBuilder sb, List<KnowledgeClaim> claims) {
        sb.append("\n## 7. Mermaid 关系图\n\n");
        if (claims.isEmpty()) {
            sb.append("暂无可绘制关系。\n");
            return;
        }

        sb.append("```mermaid\n");
        sb.append("graph LR\n");
        claims.stream()
                .limit(80)
                .forEach(c -> sb.append(String.format("  \"%s\" -->|%s| \"%s\"\n",
                        mermaidLabel(c.getSubjectKey()), mermaidLabel(c.getPredicate()), mermaidLabel(c.getObjectKey()))));
        if (claims.size() > 80) {
            sb.append("  \"更多关系\" -.-> \"请查看上方图谱关系明细\"\n");
        }
        sb.append("```\n");
        if (claims.size() > 80) {
            sb.append(String.format("\n> ⚠ 覆盖 80/%d，尚有 %d 条未展示\n", claims.size(), claims.size() - 80));
        }
    }

    // ──────────── SubTask 15.4: 核心贯穿链路（图谱直查）────────────

    /**
     * 核心贯穿链路：Table → SqlStatement → Mapper → Service → Controller → ApiEndpoint → Page。
     * 从图谱直接查询完整链路，找出前 5 条最长的贯穿链路。
     */
    private void appendCoreThroughChain(StringBuilder sb, String projectId, String versionId) {
        sb.append("\n## 8. 核心贯穿链路（图谱直查）\n\n");
        try {
            List<Map<String, Object>> apiRelations = graphDao.apiImplementationRelations(projectId, versionId);
            if (apiRelations == null || apiRelations.isEmpty()) {
                sb.append("暂无图谱贯穿链路数据。\n");
                return;
            }

            // 构建链路字符串并按长度排序
            List<String> chains = new ArrayList<>();
            for (Map<String, Object> api : apiRelations) {
                String apiName = toStr(api.get("displayName"));
                if (apiName == null) apiName = toStr(api.get("nodeKey"));
                List<String> controllers = toStrList(api.get("controllers"));
                List<String> services = toStrList(api.get("services"));
                List<String> tables = toStrList(api.get("tables"));

                // 构建 Table → Mapper → Service → Controller → ApiEndpoint 链路
                List<String> parts = new ArrayList<>();
                if (!tables.isEmpty()) parts.add(String.join(" / ", tables));
                if (!services.isEmpty()) parts.add(services.get(0));
                if (!controllers.isEmpty()) parts.add(controllers.get(0));
                if (apiName != null) parts.add(apiName);

                if (parts.size() >= 2) {
                    chains.add(String.join(" → ", parts));
                }
            }

            // 按链路长度（节点数）降序，取前 5
            chains.sort((a, b) -> {
                long lenA = a.chars().filter(c -> c == '→').count();
                long lenB = b.chars().filter(c -> c == '→').count();
                return Long.compare(lenB, lenA);
            });

            int limit = Math.min(5, chains.size());
            if (limit == 0) {
                sb.append("暂无完整贯穿链路。\n");
                return;
            }
            for (int i = 0; i < limit; i++) {
                sb.append(String.format("%d. %s\n", i + 1, chains.get(i)));
            }
            if (chains.size() > 5) {
                sb.append(String.format("\n（共 %d 条链路，展示前 5 条）\n", chains.size()));
            }
        } catch (Exception e) {
            log.warn("Failed to append core through chain: {}", e.getMessage());
            sb.append("贯穿链路查询失败。\n");
        }
    }

    // ──────────── SubTask 15.5: 图谱统计摘要 ────────────

    /**
     * 图谱统计摘要：节点总数、边总数、各类型节点/边数量分布。
     */
    private void appendGraphStatsSummary(StringBuilder sb, String projectId, String versionId) {
        sb.append("\n## 9. 图谱统计摘要\n\n");
        try {
            Map<String, Object> stats = graphDao.versionGraphStats(projectId, versionId);
            long totalNodes = toLong(stats.get("totalNodes"));
            long totalEdges = toLong(stats.get("totalEdges"));

            sb.append("### 9.1 总览\n\n");
            sb.append(String.format("- 节点总数: %d\n", totalNodes));
            sb.append(String.format("- 边总数: %d\n", totalEdges));
            Object avgConf = stats.get("avgConfidence");
            sb.append(String.format("- 平均置信度: %s\n",
                    avgConf instanceof Number ? String.format("%.4f", ((Number) avgConf).doubleValue()) : "-"));

            // 节点类型分布
            sb.append("\n### 9.2 节点类型分布\n\n");
            sb.append("| 节点类型 | 数量 |\n");
            sb.append("|---|---:|\n");
            List<Map<String, Object>> nodeDist = graphDao.nodeTypeDistribution(projectId, versionId);
            if (nodeDist != null && !nodeDist.isEmpty()) {
                for (Map<String, Object> row : nodeDist) {
                    sb.append(String.format("| %s | %d |\n",
                            toStr(row.get("nodeType")), toLong(row.get("cnt"))));
                }
            } else {
                sb.append("| (无数据) | 0 |\n");
            }

            // 边类型分布
            sb.append("\n### 9.3 边类型分布\n\n");
            sb.append("| 边类型 | 数量 |\n");
            sb.append("|---|---:|\n");
            List<Map<String, Object>> edgeDist = graphDao.edgeTypeDistribution(projectId, versionId);
            if (edgeDist != null && !edgeDist.isEmpty()) {
                for (Map<String, Object> row : edgeDist) {
                    sb.append(String.format("| %s | %d |\n",
                            toStr(row.get("edgeType")), toLong(row.get("cnt"))));
                }
            } else {
                sb.append("| (无数据) | 0 |\n");
            }
        } catch (Exception e) {
            log.warn("Failed to append graph stats summary: {}", e.getMessage());
            sb.append("图谱统计摘要查询失败。\n");
        }
    }

    // ──────────── SubTask 15.5: 模块依赖 Mermaid 图 ────────────

    /**
     * 模块依赖 Mermaid 图：基于 Package 的 DEPENDS_ON 边生成 graph TD 格式。
     */
    private void appendModuleDependencyMermaid(StringBuilder sb, String projectId, String versionId) {
        sb.append("\n## 10. 模块依赖关系图\n\n");
        try {
            List<GraphNode> packages = graphDao.queryNodes(projectId, versionId,
                    NodeType.Package.name(), null, null, null, 200);
            List<GraphEdge> edges = graphDao.queryEdges(projectId, versionId,
                    EdgeType.DEPENDS_ON.name(), null, 500);

            if (packages == null || packages.isEmpty()) {
                sb.append("暂无 Package 节点，无法生成模块依赖图。\n");
                return;
            }

            // 构建 nodeId → nodeKey 映射
            Map<String, String> idToKey = new HashMap<>();
            for (GraphNode pkg : packages) {
                String key = pkg.getNodeKey() != null ? pkg.getNodeKey() : pkg.getId();
                idToKey.put(pkg.getId(), key);
            }

            sb.append("```mermaid\n");
            sb.append("graph TD\n");
            int edgeCount = 0;
            if (edges != null) {
                for (GraphEdge edge : edges) {
                    String fromKey = idToKey.get(edge.getFromNodeId());
                    String toKey = idToKey.get(edge.getToNodeId());
                    if (fromKey == null) fromKey = edge.getFromNodeId();
                    if (toKey == null) toKey = edge.getToNodeId();
                    if (fromKey != null && toKey != null) {
                        sb.append(String.format("  \"%s\" --> \"%s\"\n",
                                mermaidLabel(fromKey), mermaidLabel(toKey)));
                        edgeCount++;
                    }
                }
            }
            if (edgeCount == 0) {
                sb.append("  \"无依赖关系\"\n");
            }
            sb.append("```\n");
            sb.append(String.format("\n（共 %d 个 Package，%d 条 DEPENDS_ON 依赖）\n",
                    packages.size(), edgeCount));
            // P0-4: 截断提示 — Package 上限 200、依赖边上限 500，命中上限时提示可能截断
            if (packages.size() >= 200) {
                sb.append("\n> ⚠ Package 列表已达展示上限（200），可能存在截断\n");
            }
            if (edges != null && edges.size() >= 500) {
                sb.append("\n> ⚠ 依赖边列表已达展示上限（500），可能存在截断\n");
            }
        } catch (Exception e) {
            log.warn("Failed to append module dependency mermaid: {}", e.getMessage());
            sb.append("模块依赖图生成失败。\n");
        }
    }

    // ──────────── 辅助方法 ────────────

    private String toStr(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() || "null".equals(s) ? null : s;
    }

    @SuppressWarnings("unchecked")
    private List<String> toStrList(Object value) {
        if (value == null) return Collections.emptyList();
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                String s = toStr(item);
                if (s != null) result.add(s);
            }
            return result;
        }
        return Collections.emptyList();
    }

    private long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }

    private String toPath(LayerMappingDTO mapping) {
        String tables = mapping.getDataTables() == null || mapping.getDataTables().isEmpty()
                ? "-"
                : String.join(",", mapping.getDataTables());
        return String.format("%s → %s → %s → %s → %s → %s",
                n(mapping.getBusinessDomain()),
                n(mapping.getCapability()),
                n(mapping.getApiPath()),
                n(mapping.getController()),
                n(mapping.getCodeModule()),
                tables);
    }

    private boolean matches(String path, String keyword) {
        return keyword == null || keyword.isBlank() || path.contains(keyword);
    }

    private LayerMappingDTO mapping(String domain, String capability, String feature, String controller,
                                    String apiPath, String codeModule, List<String> dataTables, String edgeType) {
        return LayerMappingDTO.builder()
                .businessDomain(domain)
                .capability(capability)
                .feature(feature)
                .controller(controller)
                .apiPath(apiPath)
                .codeModule(codeModule)
                .dataTables(dataTables)
                .edgeType(edgeType)
                .build();
    }

    private String n(String s) {
        return s == null ? "-" : s;
    }

    private Comparator<KnowledgeClaim> claimComparator() {
        return Comparator
                .comparing((KnowledgeClaim c) -> n(c.getSubjectType()))
                .thenComparing(c -> n(c.getSubjectKey()))
                .thenComparing(c -> n(c.getPredicate()))
                .thenComparing(c -> n(c.getObjectType()))
                .thenComparing(c -> n(c.getObjectKey()));
    }

    private String formatConfidence(BigDecimal confidence) {
        return confidence == null ? "-" : confidence.toPlainString();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String mermaidLabel(String value) {
        return n(value).replace("\"", "\\\"");
    }
}
