package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.agent.CodeFactAgent;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.dto.ManualFactRequest;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.NodeEvidence;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.NodeEvidenceRepository;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.EvidenceRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}")
@Tag(name = "事实与证据", description = "事实列表和证据检索")
public class FactController {

    private final FactRepository factRepository;
    private final EvidenceRepository evidenceRepository;
    private final NodeEvidenceRepository nodeEvidenceRepository;
    private final Neo4jGraphDao neo4jGraphDao;
    private final ScanVersionRepository scanVersionRepository;
    private final CodeRepoRepository codeRepoRepository;
    private final BusinessGraphBuilder businessGraphBuilder;
    private final CodeFactAgent codeFactAgent;
    private final DocUnderstandingAgent docUnderstandingAgent;

    public FactController(FactRepository factRepository,
                          EvidenceRepository evidenceRepository,
                          NodeEvidenceRepository nodeEvidenceRepository,
                          Neo4jGraphDao neo4jGraphDao,
                          ScanVersionRepository scanVersionRepository,
                          CodeRepoRepository codeRepoRepository,
                          BusinessGraphBuilder businessGraphBuilder,
                          CodeFactAgent codeFactAgent,
                          DocUnderstandingAgent docUnderstandingAgent) {
        this.factRepository = factRepository;
        this.evidenceRepository = evidenceRepository;
        this.nodeEvidenceRepository = nodeEvidenceRepository;
        this.neo4jGraphDao = neo4jGraphDao;
        this.scanVersionRepository = scanVersionRepository;
        this.codeRepoRepository = codeRepoRepository;
        this.businessGraphBuilder = businessGraphBuilder;
        this.codeFactAgent = codeFactAgent;
        this.docUnderstandingAgent = docUnderstandingAgent;
    }

    @GetMapping("/facts")
    @Operation(summary = "查询事实列表")
    public Result<PageResult<Fact>> listFacts(
            @PathVariable String projectId,
            @RequestParam(required = false) String factType,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) Double minConfidence,
            PageQuery query) {

        LambdaQueryWrapper<Fact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Fact::getProjectId, projectId);
        if (StringUtils.hasText(factType)) {
            wrapper.eq(Fact::getFactType, factType);
        }
        if (StringUtils.hasText(sourceType)) {
            wrapper.eq(Fact::getSourceType, sourceType);
        }
        if (minConfidence != null) {
            wrapper.ge(Fact::getConfidence, minConfidence);
        }
        wrapper.orderByDesc(Fact::getCreatedAt);

        Page<Fact> page = factRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        PageResult<Fact> result = PageResult.of(
                page.getRecords(),
                page.getTotal(),
                query.getPageNum(),
                query.getPageSize()
        );

        // 将 sourcePath 从绝对路径转为相对路径（去掉项目仓库根路径）
        stripBasePath(projectId, result.getList());

        return Result.success(result);
    }

    @GetMapping("/facts/{id}")
    @Operation(summary = "获取事实详情")
    public Result<Fact> getFact(@PathVariable String projectId, @PathVariable String id) {
        Fact fact = factRepository.selectById(id);
        if (fact == null || !fact.getProjectId().equals(projectId)) {
            return Result.error("事实不存在");
        }
        stripBasePath(projectId, List.of(fact));
        return Result.success(fact);
    }

    @GetMapping("/facts/{id}/related-nodes")
    @Operation(summary = "获取事实关联的图谱节点")
    public Result<List<GraphNode>> getRelatedNodes(@PathVariable String projectId, @PathVariable String id) {
        Fact fact = factRepository.selectById(id);
        if (fact == null || !fact.getProjectId().equals(projectId)) {
            return Result.error("事实不存在");
        }

        // 事实与图谱节点通过证据(Evidence)关联：
        //   Fact(projectId, sourcePath) -> Evidence(同源) -> NodeEvidence(evidenceId) -> GraphNode(nodeId)
        // 注意：Fact.evidenceIds 标记为 @TableField(exist=false) 未持久化，
        // 且不存在 fact_id 外键，因此按来源(projectId+sourcePath)匹配证据。
        if (!StringUtils.hasText(fact.getSourcePath())) {
            return Result.success(List.of());
        }
        LambdaQueryWrapper<Evidence> evWrapper = new LambdaQueryWrapper<>();
        evWrapper.eq(Evidence::getProjectId, projectId)
                .eq(Evidence::getSourcePath, fact.getSourcePath());
        List<String> evidenceIds = evidenceRepository.selectList(evWrapper).stream()
                .map(Evidence::getId)
                .filter(e -> e != null)
                .distinct()
                .collect(Collectors.toList());

        if (evidenceIds.isEmpty()) {
            return Result.success(List.of());
        }

        LambdaQueryWrapper<NodeEvidence> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(NodeEvidence::getEvidenceId, evidenceIds);
        List<NodeEvidence> nodeEvidences = nodeEvidenceRepository.selectList(wrapper);

        List<String> nodeIds = nodeEvidences.stream()
                .map(NodeEvidence::getNodeId)
                .filter(n -> n != null)
                .distinct()
                .collect(Collectors.toList());

        if (nodeIds.isEmpty()) {
            return Result.success(List.of());
        }

        List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(nodeIds);
        return Result.success(nodes);
    }

    @GetMapping("/evidence")
    @Operation(summary = "检索证据列表")
    public Result<PageResult<Evidence>> searchEvidence(
            @PathVariable String projectId,
            @RequestParam(required = false) String evidenceType,
            @RequestParam(required = false) String keyword,
            PageQuery query) {

        LambdaQueryWrapper<Evidence> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Evidence::getProjectId, projectId);
        if (StringUtils.hasText(evidenceType)) {
            wrapper.eq(Evidence::getEvidenceType, evidenceType);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Evidence::getSourceName, keyword)
                    .or().like(Evidence::getSummary, keyword)
                    .or().like(Evidence::getContent, keyword));
        }
        wrapper.orderByDesc(Evidence::getCreatedAt);

        Page<Evidence> page = evidenceRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        PageResult<Evidence> result = PageResult.of(
                page.getRecords(),
                page.getTotal(),
                query.getPageNum(),
                query.getPageSize()
        );

        // 丰富证据数据：location、summary、relatedNodeCount
        enrichEvidenceList(projectId, result.getList());

        return Result.success(result);
    }

    @GetMapping("/evidence/{id}")
    @Operation(summary = "获取证据详情")
    public Result<Evidence> getEvidence(@PathVariable String projectId, @PathVariable String id) {
        Evidence evidence = evidenceRepository.selectById(id);
        if (evidence == null || !evidence.getProjectId().equals(projectId)) {
            return Result.error("证据不存在");
        }
        enrichEvidenceList(projectId, List.of(evidence));
        return Result.success(evidence);
    }

    @GetMapping("/evidence/{id}/related")
    @Operation(summary = "获取证据关联的图谱节点")
    public Result<List<String>> getRelatedNodesForEvidence(@PathVariable String projectId, @PathVariable String id) {
        Evidence evidence = evidenceRepository.selectById(id);
        if (evidence == null || !evidence.getProjectId().equals(projectId)) {
            return Result.error("证据不存在");
        }
        if (StringUtils.hasText(evidence.getRelatedNodeIds())) {
            return Result.success(Arrays.asList(evidence.getRelatedNodeIds().split(",")));
        }
        return Result.success(List.of());
    }

    // ==================== 事实抽取接口 ====================

    @PostMapping("/extract/facts/code")
    @Operation(summary = "从代码片段抽取事实", description = "调用LLM从代码片段中抽取业务事实")
    public Result<FactExtractionResult> extractCodeFacts(
            @PathVariable String projectId,
            @RequestBody ExtractCodeFactsRequest request) {
        FactExtractionResult result = codeFactAgent.extractFacts(
                projectId, request.getContent(), request.getFilePath());
        log.info("Code fact extraction completed for {}: {} facts extracted",
                request.getFilePath(), result.getItems() != null ? result.getItems().size() : 0);
        return Result.success(result);
    }

    @PostMapping("/extract/facts/doc")
    @Operation(summary = "从文档片段抽取事实", description = "调用LLM从文本文档中抽取业务事实")
    public Result<DocUnderstandingAgent.BusinessFactExtraction> extractDocFacts(
            @PathVariable String projectId,
            @RequestBody ExtractDocFactsRequest request) {
        DocUnderstandingAgent.BusinessFactExtraction result = docUnderstandingAgent.extractBusinessFacts(
                projectId, request.getContent(), request.getDocId());
        int factCount = result.getBusinessDomains().size()
                + result.getBusinessProcesses().size()
                + result.getBusinessObjects().size()
                + result.getBusinessRules().size();
        log.info("Document fact extraction completed for {}: {} facts extracted",
                request.getDocId(), factCount);

        // 将抽取到的业务事实落库到业务图谱（业务域/流程/对象/规则节点 + 证据关联）
        if (factCount > 0) {
            String versionId = resolveLatestVersionId(projectId);
            if (StringUtils.hasText(versionId)) {
                try {
                    businessGraphBuilder.buildBusinessGraph(projectId, versionId, result);
                    log.info("Business graph built from doc facts: projectId={}, versionId={}", projectId, versionId);

                    // 自动触发功能到代码实现的映射（Feature -> Page/API）
                    businessGraphBuilder.mapFeaturesToCode(projectId, versionId);
                    log.info("Feature-to-code mapping completed: projectId={}, versionId={}", projectId, versionId);
                } catch (Exception e) {
                    log.error("Failed to build business graph from doc facts: projectId={}", projectId, e);
                }
            } else {
                log.warn("No scan version found for project {}, skip business graph build", projectId);
            }
        }
        return Result.success(result);
    }

    // ==================== 手动创建业务事实接口（不依赖AI） ====================

    @PostMapping("/facts/manual")
    @Operation(summary = "手动创建业务事实", description = "不依赖AI，手动创建业务域/流程/对象/规则事实并落图")
    public Result<DocUnderstandingAgent.BusinessFactExtraction> createManualBusinessFacts(
            @PathVariable String projectId,
            @RequestBody ManualFactRequest request) {
        
        // 版本绑定保障：如果指定了 versionId 则使用，否则自动获取最新版本
        String versionId = request.getVersionId();
        if (!StringUtils.hasText(versionId)) {
            versionId = resolveLatestVersionId(projectId);
            if (!StringUtils.hasText(versionId)) {
                return Result.error("项目没有可用的扫描版本，请先创建版本");
            }
        }

        // 构建业务事实对象
        DocUnderstandingAgent.BusinessFactExtraction facts = new DocUnderstandingAgent.BusinessFactExtraction();
        facts.setBusinessDomains(request.getDomains() != null ? request.getDomains() : List.of());
        facts.setBusinessProcesses(request.getProcesses() != null ? request.getProcesses() : List.of());
        facts.setBusinessObjects(request.getObjects() != null ? request.getObjects() : List.of());
        facts.setBusinessRules(request.getRules() != null ? request.getRules() : List.of());

        int factCount = facts.getBusinessDomains().size()
                + facts.getBusinessProcesses().size()
                + facts.getBusinessObjects().size()
                + facts.getBusinessRules().size();

        if (factCount == 0) {
            return Result.error("未提供任何业务事实数据");
        }

        try {
            // 使用 MANUAL_FACT 来源类型，标记为手动创建
            businessGraphBuilder.buildBusinessGraph(projectId, versionId, facts, null, 
                    io.github.legacygraph.common.SourceType.MANUAL_FACT.name());
            log.info("Manual business facts created: projectId={}, versionId={}, factCount={}", 
                    projectId, versionId, factCount);

            // 自动触发功能到代码实现的映射
            businessGraphBuilder.mapFeaturesToCode(projectId, versionId);
            log.info("Feature-to-code mapping completed: projectId={}, versionId={}", projectId, versionId);

            return Result.success(facts);
        } catch (Exception e) {
            log.error("Failed to create manual business facts: projectId={}", projectId, e);
            return Result.error("创建业务事实失败: " + e.getMessage());
        }
    }

    /**
     * 将事实列表的 sourcePath 从绝对路径转为相对路径（去掉项目仓库根路径前缀）
     */
    private void stripBasePath(String projectId, List<Fact> facts) {
        if (facts.isEmpty()) return;

        // 查询项目下所有代码仓库的本地路径
        List<CodeRepo> repos = codeRepoRepository.selectList(
                new LambdaQueryWrapper<CodeRepo>()
                        .eq(CodeRepo::getProjectId, projectId)
                        .isNotNull(CodeRepo::getLocalPath)
        );

        for (Fact fact : facts) {
            String path = fact.getSourcePath();
            if (path == null) continue;
            for (CodeRepo repo : repos) {
                String localPath = repo.getLocalPath();
                if (localPath != null && path.startsWith(localPath)) {
                    // 去掉 localPath + "/"
                    fact.setSourcePath(path.substring(localPath.length() + 1));
                    break;
                }
            }
        }
    }

    /**
     * 丰富证据列表的计算字段：location、summary、relatedNodeCount、relatedNodeIdList
     */
    private void enrichEvidenceList(String projectId, List<Evidence> list) {
        if (list.isEmpty()) return;

        // 查询项目仓库路径（用于 sourcePath → location 转换）
        List<CodeRepo> repos = codeRepoRepository.selectList(
                new LambdaQueryWrapper<CodeRepo>()
                        .eq(CodeRepo::getProjectId, projectId)
                        .isNotNull(CodeRepo::getLocalPath)
        );

        // 批量查询 NodeEvidence 关联表（避免 N+1）
        List<String> evidenceIds = list.stream().map(Evidence::getId).toList();
        List<NodeEvidence> nodeEvidences = nodeEvidenceRepository.lambdaQuery()
                .in(NodeEvidence::getEvidenceId, evidenceIds)
                .list();
        Map<String, List<NodeEvidence>> byEvidenceId = nodeEvidences.stream()
                .collect(Collectors.groupingBy(NodeEvidence::getEvidenceId));

        for (Evidence ev : list) {
            // location: 去掉仓库根路径后的相对路径
            String path = ev.getSourcePath();
            if (path != null) {
                ev.setLocation(path);
                for (CodeRepo repo : repos) {
                    String localPath = repo.getLocalPath();
                    if (localPath != null && path.startsWith(localPath)) {
                        ev.setLocation(path.substring(localPath.length() + 1));
                        break;
                    }
                }
            }

            // summary: 如果为空则用 sourceName + 行号生成
            if (ev.getSummary() == null || ev.getSummary().isBlank()) {
                StringBuilder sb = new StringBuilder(ev.getSourceName() != null ? ev.getSourceName() : "");
                if (ev.getStartLine() != null) {
                    sb.append(" L").append(ev.getStartLine());
                    if (ev.getEndLine() != null && !ev.getEndLine().equals(ev.getStartLine())) {
                        sb.append("-L").append(ev.getEndLine());
                    }
                }
                ev.setSummary(sb.toString());
            }

            // relatedNodeCount + relatedNodeIdList
            if (ev.getRelatedNodeIds() != null && !ev.getRelatedNodeIds().isBlank()) {
                String[] ids = ev.getRelatedNodeIds().split(",");
                ev.setRelatedNodeCount(ids.length);
                ev.setRelatedNodeIdList(Arrays.asList(ids));
            } else {
                List<NodeEvidence> nes = byEvidenceId.getOrDefault(ev.getId(), List.of());
                List<String> nodeIds = nes.stream()
                        .map(NodeEvidence::getNodeId)
                        .filter(n -> n != null)
                        .distinct()
                        .toList();
                ev.setRelatedNodeCount(nodeIds.size());
                ev.setRelatedNodeIdList(nodeIds);
            }
        }
    }
    /**
     * 解析项目最新的扫描版本 ID（按创建时间倒序取第一条）
     */
    private String resolveLatestVersionId(String projectId) {
        LambdaQueryWrapper<ScanVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScanVersion::getProjectId, projectId)
                .orderByDesc(ScanVersion::getStartedAt)
                .last("LIMIT 1");
        ScanVersion version = scanVersionRepository.selectOne(wrapper);
        return version != null ? version.getId() : null;
    }

    /**
     * 代码事实抽取请求
     */
    @lombok.Data
    public static class ExtractCodeFactsRequest {
        private String repoId;
        private String filePath;
        private String content;
    }

    /**
     * 文档事实抽取请求
     */
    @lombok.Data
    public static class ExtractDocFactsRequest {
        private String docId;
        private String content;
    }
}
