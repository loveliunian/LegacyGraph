package io.github.legacygraph.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.common.Result;
import io.github.legacygraph.dto.evaluation.RagasReport;
import io.github.legacygraph.dto.qa.QaEvaluationResult;
import io.github.legacygraph.dto.qa.QaFeedbackRequest;
import io.github.legacygraph.entity.QaEvaluationRun;
import io.github.legacygraph.entity.QaFeedback;
import io.github.legacygraph.entity.QaTestCase;
import io.github.legacygraph.repository.QaEvaluationRunRepository;
import io.github.legacygraph.repository.QaFeedbackRepository;
import io.github.legacygraph.repository.QaTestCaseRepository;
import io.github.legacygraph.service.evaluation.RagasMetricsService;
import io.github.legacygraph.util.IdUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * QA 评测控制器 — 项目级评测用例与历史评测运行的查询入口。
 * <p>
 * 提供评测用例列表 / 详情查询、历史评测运行记录查询，以及评测场景下的反馈提交。
 * 评测运行结果由 {@link io.github.legacygraph.service.qa.QaEvaluationService} 写出为
 * JSON 报告文件（{@code docs/legacygraph/qa-evaluation-{versionId}.json}），本控制器
 * 读取这些报告文件作为历史运行记录返回。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/lg/projects/{projectId}/qa")
@RequiredArgsConstructor
public class QaEvaluationController {

    private final QaTestCaseRepository qaTestCaseRepository;
    private final QaFeedbackRepository qaFeedbackRepository;
    private final QaEvaluationRunRepository qaEvaluationRunRepository;
    private final ObjectMapper objectMapper;
    private final RagasMetricsService ragasMetricsService;

    /** 评测报告本地目录（与 DefaultQaEvaluationService 的回退目录口径一致） */
    @Value("${legacy-graph.reports.local-dir:${user.home}/.legacygraph/reports}")
    private String reportRoot;

    /** 评测报告子目录 */
    private static final String DOCS_SUBDIR = "docs/legacygraph";

    /**
     * 查询评测用例列表（支持 status 过滤）。
     *
     * @param projectId 项目 ID
     * @param status    可选，SMOKE / GOLDEN；为空时返回项目下全部用例
     * @return 用例列表（按创建时间升序）
     */
    @GetMapping("/cases")
    public Result<List<QaTestCase>> listCases(
            @PathVariable String projectId,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<QaTestCase> wrapper = new LambdaQueryWrapper<QaTestCase>()
                .eq(QaTestCase::getProjectId, projectId)
                .orderByAsc(QaTestCase::getCreatedAt);
        if (status != null && !status.isBlank()) {
            wrapper.eq(QaTestCase::getStatus, status);
        }
        List<QaTestCase> cases = qaTestCaseRepository.selectList(wrapper);
        return Result.success(cases);
    }

    /**
     * 查看用例详情。
     *
     * @param projectId 项目 ID
     * @param caseId    用例 ID
     * @return 用例详情；不存在时返回错误
     */
    @GetMapping("/cases/{caseId}")
    public Result<QaTestCase> getCase(
            @PathVariable String projectId,
            @PathVariable String caseId) {
        QaTestCase testCase = qaTestCaseRepository.selectById(caseId);
        if (testCase == null) {
            return Result.error("评测用例不存在: " + caseId);
        }
        // 跨项目访问保护：仅返回同项目用例
        if (testCase.getProjectId() != null && !testCase.getProjectId().equals(projectId)) {
            return Result.error("评测用例不存在: " + caseId);
        }
        return Result.success(testCase);
    }

    /**
     * S4-T6: 查询历史评测运行记录 — 优先查数据库（落库的指标），fallback 到 JSON 文件。
     * <p>验收标准：评估结果可查询。</p>
     *
     * @param projectId 项目 ID
     * @return 历史评测运行列表（每项为一次评测结果汇总）
     */
    @GetMapping("/eval-runs")
    public Result<List<QaEvaluationResult>> listEvalRuns(@PathVariable String projectId) {
        // S4-T6: 优先查数据库
        List<QaEvaluationResult> runs = new ArrayList<>();
        try {
            List<QaEvaluationRun> dbRuns = qaEvaluationRunRepository.lambdaQuery()
                    .eq(QaEvaluationRun::getProjectId, projectId)
                    .orderByDesc(QaEvaluationRun::getEvaluatedAt)
                    .list();
            for (QaEvaluationRun dbRun : dbRuns) {
                runs.add(toResultDto(dbRun));
            }
        } catch (Exception e) {
            log.warn("S4-T6: failed to query eval runs from DB for projectId={}: {}", projectId, e.getMessage());
        }

        // fallback：数据库无数据时读取 JSON 文件
        if (runs.isEmpty()) {
            Path dir = resolveReportDir(projectId);
            if (dir != null && Files.isDirectory(dir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "qa-evaluation-*.json")) {
                    for (Path file : stream) {
                        QaEvaluationResult result = readReport(file);
                        if (result != null) {
                            runs.add(result);
                        }
                    }
                } catch (IOException e) {
                    log.warn("QaEvaluationController: failed to list eval runs for projectId={}: {}", projectId, e.getMessage());
                }
                runs.sort(Comparator.nullsLast(
                        Comparator.comparing(QaEvaluationResult::getEvaluatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))));
            }
        }
        return Result.success(runs);
    }

    /**
     * S4-T6: 保存评测结果到数据库 — 评测完成后调用，指标落库支持可查询。
     *
     * @param projectId 项目 ID
     * @param result    评测结果
     * @return 落库后的记录 ID
     */
    @PostMapping("/eval-runs")
    public Result<String> saveEvalRun(
            @PathVariable String projectId,
            @RequestBody QaEvaluationResult result) {
        QaEvaluationRun run = toEntity(projectId, result);
        qaEvaluationRunRepository.insert(run);
        log.info("S4-T6: eval run saved to DB: projectId={}, runId={}, passed={}",
                projectId, run.getId(), run.getPassed());
        return Result.success(run.getId());
    }

    /**
     * 提交评测反馈。
     * <p>
     * 将反馈映射到 {@link QaFeedback} 实体持久化，feedbackType 映射为 helpful 字段。
     * </p>
     *
     * @param projectId 项目 ID
     * @param request   反馈请求
     * @return 成功结果
     */
    @PostMapping("/feedback")
    public Result<Void> submitFeedback(
            @PathVariable String projectId,
            @RequestBody QaFeedbackRequest request) {
        QaFeedback feedback = toEntity(projectId, request);
        qaFeedbackRepository.insert(feedback);
        log.info("QaEvaluationController: feedback submitted for projectId={}, answerId={}, feedbackType={}",
                projectId, request.getAnswerId(), request.getFeedbackType());
        return Result.success();
    }

    /**
     * 手动触发 Ragas 指标评估。
     * <p>
     * 接收问题、回答、检索上下文及期望实体/关键词，计算四项 Ragas 指标：
     * contextPrecision / contextRecall / faithfulness / answerRelevancy。
     * </p>
     *
     * @param projectId 项目 ID
     * @param request   Ragas 评估请求体
     * @return Ragas 指标报告
     */
    @PostMapping("/ragas-evaluate")
    public Result<RagasReport> ragasEvaluate(
            @PathVariable String projectId,
            @RequestBody RagasEvaluateRequest request) {
        log.info("QaEvaluationController: ragas-evaluate triggered for projectId={}, questionLen={}",
                projectId, request.getQuestion() != null ? request.getQuestion().length() : 0);
        RagasReport report = ragasMetricsService.evaluate(
                request.getQuestion(),
                request.getAnswer(),
                request.getRetrievedContexts(),
                request.getExpectedEntities(),
                request.getExpectedKeywords());
        return Result.success(report);
    }

    /**
     * Ragas 评估请求体。
     */
    @Data
    public static class RagasEvaluateRequest {
        /** 用户问题 */
        private String question;
        /** 实际回答 */
        private String answer;
        /** 检索到的上下文片段列表 */
        private List<String> retrievedContexts;
        /** 期望被命中的实体列表 */
        private List<String> expectedEntities;
        /** 期望被覆盖的关键词列表 */
        private List<String> expectedKeywords;
    }

    // ==================== 辅助方法 ====================

    /** 把反馈 DTO 映射为 QaFeedback 实体 */
    private QaFeedback toEntity(String projectId, QaFeedbackRequest request) {
        QaFeedback feedback = new QaFeedback();
        feedback.setId(IdUtil.fastUUID());
        feedback.setProjectId(projectId);
        feedback.setMessageId(request.getAnswerId());
        feedback.setQuestion(request.getQuestion());
        feedback.setAnswer(request.getClaimText());
        feedback.setHelpful(toHelpful(request.getFeedbackType()));
        feedback.setFeedbackText(buildFeedbackText(request));
        feedback.setUsedEvidenceIds(toJson(request.getExpectedEvidenceIds()));
        return feedback;
    }

    /** feedbackType → helpful 映射：POSITIVE 为 true，NEGATIVE 为 false，其他为 null */
    private Boolean toHelpful(String feedbackType) {
        if (feedbackType == null) {
            return null;
        }
        switch (feedbackType.toUpperCase()) {
            case "POSITIVE":
            case "THUMBS_UP":
            case "USEFUL":
                return Boolean.TRUE;
            case "NEGATIVE":
            case "THUMBS_DOWN":
            case "NOT_USEFUL":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /**
     * 组装 feedbackText，便于审计：
     * <ul>
     *   <li>始终包含 {@code feedbackType=...} 前缀</li>
     *   <li>若用户在 G-08 批量评审场景下填写了备注（G-08 §13.3），追加在第二行</li>
     * </ul>
     */
    private String buildFeedbackText(QaFeedbackRequest request) {
        if (request.getFeedbackType() == null && (request.getFeedbackText() == null || request.getFeedbackText().isBlank())) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (request.getFeedbackType() != null) {
            sb.append("feedbackType=").append(request.getFeedbackType());
        }
        if (request.getFeedbackText() != null && !request.getFeedbackText().isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(request.getFeedbackText());
        }
        return sb.toString();
    }

    /** 证据 ID 列表序列化为 JSON 字符串 */
    private String toJson(List<String> value) {
        if (value == null || value.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("QaEvaluationController: failed to serialize evidence ids: {}", e.getMessage());
            return "[]";
        }
    }

    /** 解析单个评测报告 JSON 文件 */
    private QaEvaluationResult readReport(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, QaEvaluationResult.class);
        } catch (IOException e) {
            log.warn("QaEvaluationController: failed to read report {}: {}", file, e.getMessage());
            return null;
        }
    }

    /** 解析项目评测报告目录：{reportRoot}/{projectId}/docs/legacygraph */
    private Path resolveReportDir(String projectId) {
        if (reportRoot == null || reportRoot.isBlank()) {
            return null;
        }
        return Paths.get(reportRoot, projectId, DOCS_SUBDIR);
    }

    // S4-T6: 评测结果 DB ↔ DTO 转换

    /** 实体 → DTO */
    private QaEvaluationResult toResultDto(QaEvaluationRun run) {
        QaEvaluationResult dto = new QaEvaluationResult();
        dto.setProjectId(run.getProjectId());
        dto.setVersionId(run.getVersionId());
        dto.setEvaluatedAt(run.getEvaluatedAt());
        dto.setEntityRecall(run.getEntityRecall() != null ? run.getEntityRecall() : 0);
        dto.setEvidencePrecision(run.getEvidencePrecision() != null ? run.getEvidencePrecision() : 0);
        dto.setRequiredKeywordCoverage(run.getRequiredKeywordCoverage() != null ? run.getRequiredKeywordCoverage() : 0);
        dto.setAbstentionAccuracy(run.getAbstentionAccuracy() != null ? run.getAbstentionAccuracy() : 0);
        dto.setRagasContextPrecision(run.getRagasContextPrecision() != null ? run.getRagasContextPrecision() : 0);
        dto.setRagasContextRecall(run.getRagasContextRecall() != null ? run.getRagasContextRecall() : 0);
        dto.setRagasFaithfulness(run.getRagasFaithfulness() != null ? run.getRagasFaithfulness() : 0);
        dto.setRagasAnswerRelevancy(run.getRagasAnswerRelevancy() != null ? run.getRagasAnswerRelevancy() : 0);
        dto.setTotalCases(run.getTotalCases() != null ? run.getTotalCases() : 0);
        dto.setPassedCases(run.getPassedCases() != null ? run.getPassedCases() : 0);
        dto.setPassed(run.getPassed() != null && run.getPassed());
        return dto;
    }

    /** DTO → 实体 */
    private QaEvaluationRun toEntity(String projectId, QaEvaluationResult result) {
        QaEvaluationRun run = new QaEvaluationRun();
        run.setId(IdUtil.fastUUID());
        run.setProjectId(projectId);
        run.setVersionId(result.getVersionId());
        run.setEvaluatedAt(result.getEvaluatedAt() != null ? result.getEvaluatedAt() : java.time.LocalDateTime.now());
        run.setEntityRecall(result.getEntityRecall());
        run.setEvidencePrecision(result.getEvidencePrecision());
        run.setRequiredKeywordCoverage(result.getRequiredKeywordCoverage());
        run.setAbstentionAccuracy(result.getAbstentionAccuracy());
        run.setRagasContextPrecision(result.getRagasContextPrecision());
        run.setRagasContextRecall(result.getRagasContextRecall());
        run.setRagasFaithfulness(result.getRagasFaithfulness());
        run.setRagasAnswerRelevancy(result.getRagasAnswerRelevancy());
        run.setTotalCases(result.getTotalCases());
        run.setPassedCases(result.getPassedCases());
        run.setPassed(result.isPassed());
        if (result.getFailureReasons() != null && !result.getFailureReasons().isEmpty()) {
            try {
                run.setFailureReasons(objectMapper.writeValueAsString(result.getFailureReasons()));
            } catch (JsonProcessingException e) {
                run.setFailureReasons(String.join("; ", result.getFailureReasons()));
            }
        }
        run.setCreatedAt(java.time.LocalDateTime.now());
        return run;
    }
}
