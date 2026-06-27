package io.github.legacygraph.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.repository.EvidenceRepository;
import io.github.legacygraph.repository.FactRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/lg/projects/{projectId}")
@Tag(name = "事实与证据", description = "事实列表和证据检索")
public class FactController {

    private final FactRepository factRepository;
    private final EvidenceRepository evidenceRepository;

    public FactController(FactRepository factRepository, EvidenceRepository evidenceRepository) {
        this.factRepository = factRepository;
        this.evidenceRepository = evidenceRepository;
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
        return Result.success(result);
    }

    @GetMapping("/facts/{id}")
    @Operation(summary = "获取事实详情")
    public Result<Fact> getFact(@PathVariable String projectId, @PathVariable String id) {
        Fact fact = factRepository.selectById(id);
        if (fact == null || !fact.getProjectId().equals(projectId)) {
            return Result.error("事实不存在");
        }
        return Result.success(fact);
    }

    @GetMapping("/facts/{id}/related-nodes")
    @Operation(summary = "获取事实关联的节点")
    public Result<List<String>> getRelatedNodes(@PathVariable String projectId, @PathVariable String id) {
        Fact fact = factRepository.selectById(id);
        if (fact == null || !fact.getProjectId().equals(projectId)) {
            return Result.error("事实不存在");
        }
        return Result.success(List.of());
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
        return Result.success(result);
    }

    @GetMapping("/evidence/{id}")
    @Operation(summary = "获取证据详情")
    public Result<Evidence> getEvidence(@PathVariable String projectId, @PathVariable String id) {
        Evidence evidence = evidenceRepository.selectById(id);
        if (evidence == null || !evidence.getProjectId().equals(projectId)) {
            return Result.error("证据不存在");
        }
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
    public Result<Void> extractCodeFacts(
            @PathVariable String projectId,
            @RequestBody ExtractCodeFactsRequest request) {
        // TODO: 调用CodeFactAgent进行抽取
        // 实际实现会异步处理，保存抽取结果到数据库
        return Result.success();
    }

    @PostMapping("/extract/facts/doc")
    @Operation(summary = "从文档片段抽取事实", description = "调用LLM从文本文档中抽取业务事实")
    public Result<Void> extractDocFacts(
            @PathVariable String projectId,
            @RequestBody ExtractDocFactsRequest request) {
        // TODO: 调用DocUnderstandingAgent进行抽取
        // 实际实现会异步处理，保存抽取结果到数据库
        return Result.success();
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
