package io.github.legacygraph.service.systemoverview;

import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestRequest;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestRequest.FaqCard;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestRequest.RelationRow;
import io.github.legacygraph.dto.systemoverview.SystemOverviewIngestResult;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.service.graph.GraphQueryService;
import io.github.legacygraph.service.graph.KnowledgeClaimService;
import io.github.legacygraph.service.qa.SemanticCache;
import io.github.legacygraph.service.qa.VectorRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 系统关系总览事实底座导入服务。
 * <p>
 * 落地 {@code doc/系统关系总览/04-落地实施计划.md} 阶段1：把 02/03 的结构化关系写入
 * 向量（{@code lg_vector_document}）、Claim（{@code lg_knowledge_claim}）、语义缓存
 * （{@code lg_semantic_cache}）三条通路，让 EnhancedQaAgent 真正能回答关系问题。
 * </p>
 * <p>
 * EnhancedQaAgent 已接 {@code loadRelevantClaims} 相关性筛选，底座填充后即自动生效，
 * 无需改 Agent。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemOverviewIngestService {

    private final VectorRetrievalService vectorRetrievalService;
    private final KnowledgeClaimService knowledgeClaimService;
    private final SemanticCache semanticCache;
    private final GraphQueryService graphQueryService;

    /** 向量分片类型标识，区分系统关系总览与普通 DOC/CODE 分片 */
    public static final String CHUNK_TYPE = "SYSTEM_OVERVIEW";

    private static final String EXTRACTOR = "SystemOverviewIngest";

    /** 从图谱生成总览时，单次最多处理的 API 端点数（防止超大项目一次性拉爆 Neo4j）。 */
    private static final int MAX_GRAPH_APIS = 500;

    /**
     * 导入请求中的关系行与 FAQ。
     * 幂等：Claim 按 (projectId,versionId,subjectType,subjectKey,predicate,objectType,objectKey) 去重；
     * 向量按内容 upsert；语义缓存按 question 去重（SemanticCache 内部处理）。
     */
    @Transactional
    @CacheEvict(cacheNames = "system-overview", allEntries = true)
    public SystemOverviewIngestResult ingest(SystemOverviewIngestRequest request) {
        String projectId = request.getProjectId();
        String versionId = resolveVersion(request.getVersionId());

        int vectorCount = 0;
        int claimCount = 0;
        int faqCount = 0;
        int skipped = 0;

        // 1. relations → 向量 + Claim
        List<RelationRow> relations = request.getRelations();
        if (relations != null && !relations.isEmpty()) {
            List<VectorDocument> docs = new ArrayList<>();
            List<KnowledgeClaimDraft> drafts = new ArrayList<>();
            int idx = 0;
            for (RelationRow row : relations) {
                idx++;
                String content = toContent(row);
                if (content.isBlank()) {
                    skipped++;
                    continue;
                }
                docs.add(buildVector(projectId, versionId, idx, content, row.getBusinessDomain()));
                drafts.addAll(toClaims(projectId, versionId, row));
            }
            if (!docs.isEmpty()) {
                vectorRetrievalService.batchUpsertVectors(projectId, versionId, docs);
                vectorCount = docs.size();
            }
            if (!drafts.isEmpty()) {
                List<KnowledgeClaim> saved = knowledgeClaimService.upsertDrafts(drafts);
                claimCount = saved.size();
            }
        }

        // 2. faqs → 语义缓存
        List<FaqCard> faqs = request.getFaqs();
        if (faqs != null && !faqs.isEmpty()) {
            for (FaqCard faq : faqs) {
                if (faq.getQuestion() == null || faq.getQuestion().isBlank()
                        || faq.getAnswer() == null || faq.getAnswer().isBlank()) {
                    skipped++;
                    continue;
                }
                semanticCache.put(projectId, faq.getQuestion(), faq.getAnswer(), faq.getEvidence());
                faqCount++;
            }
        }

        log.info("SystemOverview ingested: projectId={}, versionId={}, vectors={}, claims={}, faqs={}, skipped={}",
                projectId, versionId, vectorCount, claimCount, faqCount, skipped);

        return SystemOverviewIngestResult.builder()
                .projectId(projectId)
                .versionId(versionId)
                .vectorCount(vectorCount)
                .claimCount(claimCount)
                .faqCount(faqCount)
                .skipped(skipped)
                .build();
    }

    /**
     * 导入内置的系统关系总览底座（来自 02 §0.1 十二业务域 + 03 Part A 核心 FAQ）。
     * <p>
     * 用于一键初始化 LegacyGraph 自身的关系知识底座，让 QA 在无外部数据时也能回答
     * 关系类问题。
     * </p>
     */
    @Transactional
    public SystemOverviewIngestResult ingestBuiltins(String projectId, String versionId) {
        return ingest(SystemOverviewIngestRequest.builder()
                .projectId(projectId)
                .versionId(versionId)
                .relations(buildBuiltinRelations())
                .faqs(buildBuiltinFaqs())
                .build());
    }

    /**
     * 基于当前项目的真实扫描图谱生成系统关系总览。
     * <p>
     * 从 Neo4j 图谱按 API 端点回溯调用链（ApiEndpoint → Controller → Service → Table），
     * 组装为四层关系行后走 {@link #ingest} 通路写入 Claim，供 {@code SystemOverviewService}
     * 动态投影。与内置底座（LegacyGraph 自身硬编码）不同，这里的数据完全来自当前项目扫描结果。
     * </p>
     * <p>
     * 说明：代码图谱不含"业务域"层，此处用 Controller 名（去 Controller 后缀）作为业务域近似；
     * 若项目已通过文档理解产出真实业务域，可后续叠加。
     * </p>
     */
    @Transactional
    public SystemOverviewIngestResult ingestFromProjectGraph(String projectId, String versionId) {
        List<RelationRow> rows = buildRelationsFromGraph(projectId, versionId);
        if (rows.isEmpty()) {
            log.info("No graph-derived relations for projectId={}, versionId={} (empty scan graph?)",
                    projectId, versionId);
            return SystemOverviewIngestResult.builder()
                    .projectId(projectId)
                    .versionId(resolveVersion(versionId))
                    .vectorCount(0).claimCount(0).faqCount(0).skipped(0)
                    .build();
        }
        log.info("Graph-derived {} relation rows for projectId={}, versionId={}",
                rows.size(), projectId, versionId);
        return ingest(SystemOverviewIngestRequest.builder()
                .projectId(projectId)
                .versionId(versionId)
                .relations(rows)
                .build());
    }

    /**
     * 从当前项目图谱回溯每个 API 端点的调用链，抽取 Controller/Service/Table 组装关系行。
     */
    private List<RelationRow> buildRelationsFromGraph(String projectId, String versionId) {
        List<RelationRow> rows = new ArrayList<>();
        List<Map<String, Object>> apiRels = null;
        try {
            apiRels = graphQueryService.getApiImplementationRelations(projectId, versionId);
        } catch (Exception e) {
            log.warn("Load api implementation relations failed for projectId={}: {}", projectId, e.getMessage());
        }

        int processed = 0;
        if (apiRels != null) {
            for (Map<String, Object> r : apiRels) {
                if (processed++ >= MAX_GRAPH_APIS) {
                    log.warn("API implementation relations exceed {}, truncating graph-derived overview", MAX_GRAPH_APIS);
                    break;
                }
                String apiKey = str(r.get("nodeKey"));
                String apiName = str(r.get("displayName"));
                if (apiKey == null || apiKey.isBlank()) {
                    continue;
                }

                String controller = firstStr(r.get("controllers"));
                String service = firstStr(r.get("services"));
                List<String> tables = strList(r.get("tables"));
                // 仅保留 PG 表（过滤 Neo4j/Redis/MinIO 等非关系存储），与 toClaims 口径一致
                tables = tables.stream()
                        .filter(t -> !t.startsWith("Neo4j") && !t.startsWith("Redis") && !t.startsWith("MinIO"))
                        .collect(java.util.stream.Collectors.toList());

                // 至少要抽到代码层或数据层，否则该 API 无投影价值
                if (controller == null && service == null && tables.isEmpty()) {
                    continue;
                }

                String domain = deriveDomain(controller, apiName);
                String capability = notBlank(apiName) ? apiName : apiKey;
                rows.add(row(domain, capability, capability, controller, apiKey, service,
                        String.join(",", tables), "HANDLED_BY", "CODE", 0.85));
            }
        }

        // Table 捕获：以 Mapper 为锚补全数据表访问（API 回溯在 Service↔Mapper 无边时够不到表，
        // 但 Mapper→SqlStatement→Table 通常通）。每行向量化后内容含 "数据表:t1,t2,..."，
        // 让「哪些数据库表」类列举题有结构化内容可语义召回。
        List<Map<String, Object>> tableRels = null;
        try {
            tableRels = graphQueryService.getTableAccessRelations(projectId, versionId);
        } catch (Exception e) {
            log.warn("Load table access relations failed for projectId={}: {}", projectId, e.getMessage());
        }
        if (tableRels != null) {
            for (Map<String, Object> r : tableRels) {
                String mapperKey = str(r.get("mapperKey"));
                String mapperName = notBlank(str(r.get("mapperName"))) ? str(r.get("mapperName")) : mapperKey;
                if (mapperKey == null && mapperName == null) {
                    continue;
                }
                List<String> tables = strList(r.get("tables"));
                tables = tables.stream()
                        .filter(t -> !t.startsWith("Neo4j") && !t.startsWith("Redis") && !t.startsWith("MinIO"))
                        .collect(java.util.stream.Collectors.toList());
                if (tables.isEmpty()) {
                    continue;
                }
                String module = notBlank(mapperName) ? mapperName : mapperKey;
                String domain = deriveDomainFromModule(module);
                // codeModuleType=Mapper：toClaims 以 Mapper 为 subject 生成 READS/WRITES Table
                RelationRow tr = row(domain, null, null, null, null, module,
                        String.join(",", tables), "READS", "CODE", 0.85);
                tr.setCodeModuleType("Mapper");
                rows.add(tr);
            }
        }

        return rows;
    }

    /** 取列表首元素为字符串，空/非列表返回 null。 */
    private String firstStr(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            return first == null ? null : String.valueOf(first);
        }
        return null;
    }

    /** 把列表元素统一转为字符串列表，非列表返回空列表。 */
    private List<String> strList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    result.add(String.valueOf(o));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 代码图谱无业务域层：用 Controller 名（去 Controller 后缀）近似，退化时用 API 名。 */
    private String deriveDomain(String controller, String apiName) {
        if (notBlank(controller)) {
            String d = controller.replaceAll("Controller$", "").trim();
            if (!d.isBlank()) {
                return d;
            }
        }
        return notBlank(apiName) ? apiName : "未分类";
    }

    /** 用代码模块名（去 Mapper/Service/Controller 后缀）近似业务域，用于 Table 捕获行。 */
    private String deriveDomainFromModule(String module) {
        if (!notBlank(module)) {
            return "未分类";
        }
        String d = module.replaceAll("(Mapper|Service|Controller|Repository)$", "").trim();
        return d.isBlank() ? module : d;
    }

    // ──────────── 关系 → Claim 转换 ────────────

    /**
     * 一行关系 → 多条 Claim Draft，对齐 01 §4 边类型：
     * <ul>
     *   <li>BusinessDomain CONTAINS Feature（业务域包含能力）</li>
     *   <li>Feature IMPLEMENTED_BY Controller（能力由 Controller 实现）</li>
     *   <li>ApiEndpoint HANDLED_BY Controller（API 由 Controller 处理）</li>
     *   <li>Controller IMPLEMENTED_BY Service（Controller 委托 Service）</li>
     *   <li>Feature USES Service（能力使用代码模块）</li>
     *   <li>Service READS Table（代码模块读表）</li>
     *   <li>Service WRITES Table（代码模块写表）</li>
     *   <li>BusinessDomain MAPS_TO Table（业务域映射到表）</li>
     * </ul>
     * <p>
     * 谨慎口径：DOC 来源或 AI 抽取默认 PENDING_CONFIRM；
     * 仅 CODE 来源 + confidence ≥ 0.85 才自动 CONFIRMED（对齐 KnowledgeClaimService.computeStatus）。
     * </p>
     */
    private List<KnowledgeClaimDraft> toClaims(String projectId, String versionId, RelationRow row) {
        List<KnowledgeClaimDraft> drafts = new ArrayList<>();
        String source = notBlank(row.getSourceType()) ? row.getSourceType() : "DOC";
        double conf = row.getConfidence() != null ? row.getConfidence()
                : ("CODE".equals(source) ? 0.85 : 0.6);
        BigDecimal confidence = BigDecimal.valueOf(conf);

        String domain = row.getBusinessDomain();
        String capability = row.getCapability();

        // 1. Domain CONTAINS Feature
        if (notBlank(domain) && notBlank(capability)) {
            drafts.add(draft(projectId, versionId, "BusinessDomain", domain, "CONTAINS",
                    "Feature", capability, source, confidence));
        }
        // 2. Feature IMPLEMENTED_BY Controller
        if (notBlank(capability) && notBlank(row.getController())) {
            drafts.add(draft(projectId, versionId, "Feature", capability, "IMPLEMENTED_BY",
                    "Controller", row.getController(), source, confidence));
        }
        // 3. ApiEndpoint HANDLED_BY Controller（API 路径→Controller 处理）
        if (notBlank(row.getApiPath()) && notBlank(row.getController())) {
            drafts.add(draft(projectId, versionId, "ApiEndpoint", row.getApiPath(), "HANDLED_BY",
                    "Controller", row.getController(), source, confidence));
        }
        // 4. Controller IMPLEMENTED_BY Service（Controller 委托 Service 实现）
        if (notBlank(row.getController()) && notBlank(row.getCodeModule())) {
            drafts.add(draft(projectId, versionId, "Controller", row.getController(), "IMPLEMENTED_BY",
                    "Service", row.getCodeModule(), source, confidence));
        }
        // 5. Feature USES Service
        if (notBlank(capability) && notBlank(row.getCodeModule())) {
            drafts.add(draft(projectId, versionId, "Feature", capability, "USES",
                    "Service", row.getCodeModule(), source, confidence));
        }
        // 6. codeModule READS/WRITES Table —— subjectType 取 codeModuleType（默认 Service）；
        //    Table 捕获行的 codeModule 是 Mapper，需以 Mapper 为 subject。
        String codeSubjectType = notBlank(row.getCodeModuleType()) ? row.getCodeModuleType() : "Service";
        if (notBlank(row.getCodeModule()) && notBlank(row.getDataTables())) {
            for (String t : row.getDataTables().split(",")) {
                String table = t.trim();
                if (table.isEmpty() || table.startsWith("Neo4j") || table.startsWith("Redis")
                        || table.startsWith("MinIO")) {
                    continue; // 跳过非 PG 存储
                }
                // READS：所有表
                drafts.add(draft(projectId, versionId, codeSubjectType, row.getCodeModule(), "READS",
                        "Table", table, source, confidence));
                // WRITES：非快照/日志类表（排除纯读表如 lg_sys_operation_log）
                if (!table.endsWith("_log") && !table.endsWith("_snapshot")) {
                    drafts.add(draft(projectId, versionId, codeSubjectType, row.getCodeModule(), "WRITES",
                            "Table", table, source, confidence));
                }
            }
        }
        // 7. BusinessDomain MAPS_TO Table（业务对象→表）
        if (notBlank(domain) && notBlank(row.getDataTables())) {
            for (String t : row.getDataTables().split(",")) {
                String table = t.trim();
                if (table.startsWith("lg_")) {
                    drafts.add(draft(projectId, versionId, "BusinessDomain", domain, "MAPS_TO",
                            "Table", table, source, confidence));
                }
            }
        }
        return drafts;
    }

    private KnowledgeClaimDraft draft(String projectId, String versionId,
                                      String subjectType, String subjectKey, String predicate,
                                      String objectType, String objectKey,
                                      String sourceType, BigDecimal confidence) {
        return KnowledgeClaimDraft.builder()
                .projectId(projectId)
                .versionId(versionId)
                .subjectType(subjectType)
                .subjectKey(subjectKey)
                .predicate(predicate)
                .objectType(objectType)
                .objectKey(objectKey)
                .sourceType(sourceType)
                .extractor(EXTRACTOR)
                .confidence(confidence)
                .evidenceIds(new ArrayList<>())
                .build();
    }

    // ──────────── 关系 → 向量 ────────────

    private VectorDocument buildVector(String projectId, String versionId, int idx,
                                       String content, String domain) {
        VectorDocument doc = new VectorDocument();
        doc.setProjectId(projectId);
        doc.setVersionId(versionId);
        doc.setChunkType(CHUNK_TYPE);
        doc.setSourceUri("system-overview:" + (notBlank(domain) ? domain : "row") + ":" + idx);
        doc.setChunkIndex(idx);
        doc.setContent(content);
        doc.setEmbeddingModel("bge-m3");
        return doc;
    }

    /** 把一行关系拼成自然语言文本，便于语义检索召回。 */
    private String toContent(RelationRow row) {
        StringBuilder sb = new StringBuilder();
        if (notBlank(row.getBusinessDomain())) sb.append("业务域:").append(row.getBusinessDomain()).append(' ');
        if (notBlank(row.getCapability())) sb.append("业务能力:").append(row.getCapability()).append(' ');
        if (notBlank(row.getFeature())) sb.append("功能:").append(row.getFeature()).append(' ');
        if (notBlank(row.getController())) sb.append("Controller:").append(row.getController()).append(' ');
        if (notBlank(row.getApiPath())) sb.append("API:").append(row.getApiPath()).append(' ');
        if (notBlank(row.getCodeModule())) sb.append("代码:").append(row.getCodeModule()).append(' ');
        if (notBlank(row.getDataTables())) sb.append("数据表:").append(row.getDataTables()).append(' ');
        if (notBlank(row.getEdgeType())) sb.append("关系:").append(row.getEdgeType());
        return sb.toString().trim();
    }

    // ──────────── 内置底座（02 §0.1 + 03 Part A）────────────

    private List<RelationRow> buildBuiltinRelations() {
        List<RelationRow> rows = new ArrayList<>();
        // 12 业务域核心映射，对齐 02 §0.1 表总览
        rows.add(row("项目与数据源管理", "项目纳管", "project", "ProjectController", "/lg/projects",
                "ProjectService", "lg_project,lg_code_repo,lg_db_connection,lg_document", "BELONGS_TO", "DOC", 0.70));
        rows.add(row("项目与数据源管理", "用户认证", "login", "AuthController", "/lg/auth",
                "AuthService", "lg_sys_user,lg_sys_role,lg_sys_user_role", "REQUIRES_PERMISSION", "DOC", 0.70));
        rows.add(row("资料扫描", "扫描编排", "scan", "ScanController", "/scan-versions",
                "ProjectScanner", "lg_scan_version,lg_scan_task,lg_source_asset_snapshot", "TRIGGERS", "DOC", 0.70));
        rows.add(row("图谱构建与查询", "图谱写入", "graph", "GraphQueryController", "/lg/projects/{projectId}",
                "EvidenceGraphWriter", "Neo4j,lg_evidence,lg_node_evidence,lg_edge_evidence", "HAS_EVIDENCE", "DOC", 0.70));
        rows.add(row("事实/证据/知识断言", "知识断言", "fact", "KnowledgeController", "/knowledge",
                "KnowledgeClaimService", "lg_knowledge_claim,lg_gap_task", "MAPS_TO", "DOC", 0.70));
        rows.add(row("QA 问答", "流式问答", "graph(GraphQa)", "EnhancedQaController", "/qa/ask/stream",
                "EnhancedQaAgent", "lg_qa_conversation,lg_qa_message,lg_semantic_cache", "USES", "DOC", 0.70));
        rows.add(row("测试闭环", "测试生成", "test", "TestCaseController", "/lg/projects/{projectId}",
                "TestCaseAgent", "lg_test_case,lg_test_assertion,lg_test_result", "VERIFIED_BY", "DOC", 0.70));
        rows.add(row("代码理解", "代码理解编排", "understanding", "CodeUnderstandingController", "/understanding",
                "CodeUnderstandingOrchestrator", "lg_agent_run,lg_tool_run", "USES", "DOC", 0.70));
        rows.add(row("变更影响与迁移", "变更影响", "change", "ChangeTaskController", "/change-tasks",
                "ChangeImpactAgent", "lg_change_task,lg_patch_file,lg_pr_task", "AFFECTS", "DOC", 0.70));
        rows.add(row("报告与度量", "报告导出", "report", "ReportExportController", "/reports",
                "ReportExportService", "lg_reports", "USES", "DOC", 0.70));
        rows.add(row("LLM 与 Agent 管理", "模型网关", "settings", "LlmProviderController", "/llm/providers",
                "LlmGateway", "lg_llm_provider,lg_prompt_run", "USES", "DOC", 0.70));
        rows.add(row("系统管理", "系统配置", "system", "SystemController", "/lg/system",
                "SystemService", "lg_sys_config,lg_sys_dict,lg_sys_operation_log", "BELONGS_TO", "DOC", 0.70));
        return rows;
    }

    private List<FaqCard> buildBuiltinFaqs() {
        List<FaqCard> faqs = new ArrayList<>();
        // 核心 FAQ，对齐 03 Part A
        faqs.add(FaqCard.builder()
                .question("LegacyGraph 是做什么的")
                .answer("LegacyGraph 是企业级系统分析与知识图谱平台，把代码库、数据库、文档、前端页面连接成统一知识网络，再投影出业务/功能/代码三类图谱。核心理念：静态分析给事实，LLM 做归纳补全，自动测试反证，人工审核兜底。")
                .evidence("[{\"sourceType\":\"DOC\",\"sourcePath\":\"README.md\"}]")
                .build());
        faqs.add(FaqCard.builder()
                .question("图谱节点和边存在 Neo4j 还是 PostgreSQL")
                .answer("图谱节点和边存 Neo4j（Neo4jGraphDao 幂等 MERGE）；PostgreSQL 只存证据关联（lg_evidence/lg_node_evidence/lg_edge_evidence）和审核记录。lg_graph_node/lg_graph_edge 表已废弃，仅保留 Bean 兼容，当前业务代码不再写入。")
                .evidence("[{\"sourceType\":\"CODE\",\"sourcePath\":\"dao/Neo4jGraphDao.java\"},{\"sourceType\":\"CODE\",\"sourcePath\":\"builder/EvidenceGraphWriter.java\"}]")
                .build());
        faqs.add(FaqCard.builder()
                .question("扫描涉及哪些数据库表")
                .answer("扫描涉及：lg_scan_version（扫描版本）、lg_scan_task（扫描子任务）、lg_source_asset_snapshot（资产快照/增量）、lg_ai_scan_job（AI 扫描异步任务）、lg_document（文档元数据）、lg_doc_chunk（文档分片）。版本是图谱可追溯的边界，所有事实/任务/证据/节点/边/Claim/Gap 都挂在同一 project_id+version_id 下。")
                .evidence("[{\"sourceType\":\"DB\",\"sourcePath\":\"V20,V24,V28\"}]")
                .build());
        faqs.add(FaqCard.builder()
                .question("QA 问答用到哪些表")
                .answer("QA 用到：lg_qa_conversation（会话）、lg_qa_message（消息，含 evidences/confidence）、lg_qa_feedback（反馈，含 used_evidence_ids）、lg_semantic_cache（语义缓存）、lg_vector_document（向量）、lg_doc_chunk（文档片段）、lg_knowledge_claim（GraphRAG Claim 输入）。")
                .evidence("[{\"sourceType\":\"DB\",\"sourcePath\":\"V29,V30\"},{\"sourceType\":\"CODE\",\"sourcePath\":\"agent/EnhancedQaAgent.java\"}]")
                .build());
        faqs.add(FaqCard.builder()
                .question("业务功能代码数据四层怎么串起来")
                .answer("核心链路：BusinessDomain CONTAINS Feature EXPOSED_BY ApiEndpoint HANDLED_BY Controller/Method IMPLEMENTED_BY Service CALLS Mapper EXECUTES SqlStatement READS/WRITES Table HAS_COLUMN Column。任一层可沿此链路向前追到业务、向后追到数据。")
                .evidence("[{\"sourceType\":\"DOC\",\"sourcePath\":\"doc/系统关系总览/01-关系总览与映射框架.md\"}]")
                .build());
        return faqs;
    }

    private RelationRow row(String domain, String capability, String feature, String controller,
                            String apiPath, String codeModule, String dataTables,
                            String edgeType, String sourceType, double confidence) {
        return RelationRow.builder()
                .businessDomain(domain)
                .capability(capability)
                .feature(feature)
                .controller(controller)
                .apiPath(apiPath)
                .codeModule(codeModule)
                .dataTables(dataTables)
                .edgeType(edgeType)
                .sourceType(sourceType)
                .confidence(confidence)
                .build();
    }

    // ──────────── helpers ────────────

    private String resolveVersion(String versionId) {
        return (versionId == null || versionId.isBlank()) ? "default" : versionId;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
