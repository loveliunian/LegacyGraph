package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.dto.systemoverview.LayerMappingDTO;
import io.github.legacygraph.dto.systemoverview.SystemOverviewDTO;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

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
                .corePaths(buildCorePaths())
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
        return buildCorePaths();
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
        List<LayerMappingDTO> mappings = buildDynamicMappings(projectId, versionId);
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
        for (String path : buildCorePaths()) {
            sb.append("- ").append(path).append("\n");
        }

        sb.append("\n## 3. 四层定义\n\n");
        sb.append("- **业务层**：BusinessDomain/Process/Object/Rule/Role（为什么存在）\n");
        sb.append("- **功能层**：Feature/Page/Button/Permission/ApiEndpoint（如何触发）\n");
        sb.append("- **代码层**：Controller/Service/Method/Mapper/SqlStatement（由什么实现）\n");
        sb.append("- **数据层**：Table/Column/Index（落到什么表）\n");
        sb.append("\n> 核心链路：BusinessDomain CONTAINS Feature EXPOSED_BY ApiEndpoint HANDLED_BY Controller ");
        sb.append("IMPLEMENTED_BY Service CALLS Mapper EXECUTES SqlStatement READS/WRITES Table HAS_COLUMN Column\n");

        sb.append("\n## 4. QA 文档基础\n\n");
        sb.append("- 本报告沉淀资料扫描后的业务/功能/代码/数据关系，可作为后续 QA 文档生成的事实基础。\n");
        sb.append("- QA 文档应优先引用业务域、功能、Controller/API、代码模块、数据表与核心贯穿链路。\n");
        sb.append("- 对未覆盖或低置信关系，应回到 Claim、证据或图谱查询确认，避免把推断写成已确认事实。\n");

        return sb.toString();
    }

    // ──────────── 动态投影（M2 修复）────────────

    /**
     * 从 lg_knowledge_claim 动态投影四层映射。
     * <p>
     * 查询 CONFIRMED/PENDING_CONFIRM 状态的 Claim，按 subjectKey 聚合为 LayerMappingDTO。
     * 若无 Claim 数据则回退到内置映射。
     * </p>
     */
    private List<LayerMappingDTO> buildDynamicMappings(String projectId, String versionId) {
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

            // 按 subjectKey 聚合
            Map<String, List<KnowledgeClaim>> bySubject = claims.stream()
                    .filter(c -> c.getSubjectKey() != null)
                    .collect(Collectors.groupingBy(KnowledgeClaim::getSubjectKey));

            List<LayerMappingDTO> mappings = new ArrayList<>();

            // 对每个 BusinessDomain 类型的 subject 构建映射
            for (Map.Entry<String, List<KnowledgeClaim>> entry : bySubject.entrySet()) {
                String subjectKey = entry.getKey();
                List<KnowledgeClaim> subjectClaims = entry.getValue();

                // 检查是否有 BusinessDomain CONTAINS Feature 类型的 claim
                KnowledgeClaim domainClaim = subjectClaims.stream()
                        .filter(c -> "BusinessDomain".equals(c.getSubjectType())
                                && "CONTAINS".equals(c.getPredicate())
                                && "Feature".equals(c.getObjectType()))
                        .findFirst()
                        .orElse(null);

                if (domainClaim == null) {
                    continue; // 不是 BusinessDomain，跳过
                }

                String domain = domainClaim.getSubjectKey();
                String feature = domainClaim.getObjectKey();

                // 查找 Feature 相关的 claim
                List<KnowledgeClaim> featureClaims = bySubject.getOrDefault(feature, Collections.emptyList());

                String controller = null;
                String apiPath = null;
                String codeModule = null;
                List<String> tables = new ArrayList<>();

                for (KnowledgeClaim fc : featureClaims) {
                    if ("IMPLEMENTED_BY".equals(fc.getPredicate()) && "Controller".equals(fc.getObjectType())) {
                        controller = fc.getObjectKey();
                    } else if ("USES".equals(fc.getPredicate()) && "Service".equals(fc.getObjectType())) {
                        codeModule = fc.getObjectKey();
                    }
                }

                if (codeModule == null && controller != null) {
                    List<KnowledgeClaim> controllerClaims = bySubject.getOrDefault(controller, Collections.emptyList());
                    for (KnowledgeClaim cc : controllerClaims) {
                        if ("Controller".equals(cc.getSubjectType())
                                && "HANDLED_BY".equals(cc.getPredicate())
                                && "Service".equals(cc.getObjectType())) {
                            codeModule = cc.getObjectKey();
                            break;
                        }
                    }
                }

                // 查找 ApiEndpoint HANDLED_BY Controller
                for (List<KnowledgeClaim> apiClaims : bySubject.values()) {
                    for (KnowledgeClaim ac : apiClaims) {
                        if ("ApiEndpoint".equals(ac.getSubjectType())
                                && "HANDLED_BY".equals(ac.getPredicate())
                                && controller != null && controller.equals(ac.getObjectKey())) {
                            apiPath = ac.getSubjectKey();
                            break;
                        }
                    }
                    if (apiPath != null) break;
                }

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

    private List<String> buildCorePaths() {
        return Arrays.asList(
                "扫描→图谱：Project → ScanVersion → Adapter → GraphBuilder → EvidenceGraphWriter → Neo4j/证据表",
                "QA问答：question → IntentClassifier → HybridRetrieval → ReRanker → GraphRagPlanExecutor → LlmGateway → QA表",
                "测试回写：Feature → TestCase → TestExecutor → Table/Method → 置信度回写",
                "变更影响：Table ← READS/WRITE ← SQL ← Mapper ← Service ← HANDLED_BY ← Api ← Feature",
                "Graphify导入：graph.json → Compatibility → Import → GraphMergeAgent → Neo4j"
        );
    }

    private boolean isUsableOverviewClaim(KnowledgeClaim claim) {
        if (claim == null || claim.getSubjectKey() == null) {
            return false;
        }
        String status = claim.getStatus();
        return status == null || "CONFIRMED".equals(status) || "PENDING_CONFIRM".equals(status);
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
}
