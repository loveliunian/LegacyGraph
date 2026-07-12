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
import io.github.legacygraph.service.qa.QaEvaluationService;
import io.github.legacygraph.util.IdUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final QaEvaluationService qaEvaluationService;

    /** 评测报告本地目录（与 DefaultQaEvaluationService 的回退目录口径一致） */
    @Value("${legacygraph.reports.local-dir:${user.home}/.legacygraph/reports}")
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

    // ==================== H28: v2 评测集接口 ====================

    /** v2 评测集 classpath 路径 */
    private static final String V2_TEST_SET_PATH = "qa-evaluation/test-set-v2.json";

    /** v2 基线目标（与 test-set-v2.json 中 baseline_targets 一致） */
    private static final Map<String, Double> V2_BASELINE_TARGETS = Map.of(
            "FACT_LOOKUP", 0.95,
            "RELATIONSHIP", 0.90,
            "INFERENCE", 0.80,
            "CHANGE_IMPACT", 0.70,
            "ENUMERATION", 0.95
    );

    /**
     * H28: 运行 v2 评测集（100 题，5 类意图）。
     * <p>
     * 从 classpath 加载 test-set-v2.json，调用 QaEvaluationService 运行评测，
     * 按 intent 分组计算分类准确率，与基线目标对比。
     * </p>
     *
     * @param projectId 项目 ID
     * @param versionId 扫描版本 ID（可选，默认 "latest"）
     * @return v2 评测结果（含分类指标）
     */
    @PostMapping("/eval-v2/run")
    public Result<QaEvalV2Response> runEvalV2(
            @PathVariable String projectId,
            @RequestParam(required = false, defaultValue = "latest") String versionId) {
        log.info("H28: v2 eval run triggered: projectId={}, versionId={}", projectId, versionId);
        try {
            // 1. 加载 v2 评测集
            List<QaTestCase> testCases = loadV2TestSet(projectId);
            if (testCases.isEmpty()) {
                return Result.error("v2 评测集加载失败或为空");
            }
            log.info("H28: loaded {} v2 test cases", testCases.size());

            // 2. 运行评测
            QaEvaluationResult evalResult = qaEvaluationService.evaluate(projectId, versionId, testCases);

            // 3. 按 intent 分组计算分类准确率
            Map<String, CategoryMetric> categoryMetrics = computeCategoryMetrics(evalResult);

            // 4. 落库
            try {
                QaEvaluationRun run = toEntity(projectId, evalResult);
                qaEvaluationRunRepository.insert(run);
                log.info("H28: v2 eval run saved: runId={}", run.getId());
            } catch (Exception e) {
                log.warn("H28: failed to save v2 eval run: {}", e.getMessage());
            }

            // 5. 构建响应
            QaEvalV2Response response = new QaEvalV2Response();
            response.setEvalResult(evalResult);
            response.setCategoryMetrics(categoryMetrics);
            response.setBaselineTargets(V2_BASELINE_TARGETS);
            response.setRegressionDetected(checkRegression(categoryMetrics));

            return Result.success(response);
        } catch (Exception e) {
            log.error("H28: v2 eval run failed: projectId={}, err={}", projectId, e.getMessage(), e);
            return Result.error("v2 评测运行失败: " + e.getMessage());
        }
    }

    /**
     * H28: 查询 v2 基线目标。
     *
     * @return 基线目标映射
     */
    @GetMapping("/eval-v2/baseline")
    public Result<Map<String, Double>> getV2Baseline() {
        return Result.success(V2_BASELINE_TARGETS);
    }

    /**
     * 从 classpath 加载 v2 评测集，解析为 QaTestCase 列表。
     */
    private List<QaTestCase> loadV2TestSet(String projectId) throws IOException {
        ClassPathResource resource = new ClassPathResource(V2_TEST_SET_PATH);
        if (!resource.exists()) {
            log.error("H28: v2 test set not found at classpath:{}", V2_TEST_SET_PATH);
            return List.of();
        }
        List<QaTestCase> cases = new ArrayList<>();
        try (InputStream is = resource.getInputStream()) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode testCasesNode = root.get("test_cases");
            if (testCasesNode == null || !testCasesNode.isArray()) {
                log.error("H28: v2 test set has no test_cases array");
                return List.of();
            }
            for (com.fasterxml.jackson.databind.JsonNode tcNode : testCasesNode) {
                QaTestCase tc = new QaTestCase();
                tc.setId(tcNode.path("id").asText());
                tc.setProjectId(projectId);
                tc.setQuestion(tcNode.path("question").asText());
                tc.setIntent(tcNode.path("category").asText());
                tc.setStatus("GOLDEN");
                // expectedEntities 从 expected_node_keys 加载
                List<String> nodeKeys = new ArrayList<>();
                com.fasterxml.jackson.databind.JsonNode nkNode = tcNode.get("expected_node_keys");
                if (nkNode != null && nkNode.isArray()) {
                    nkNode.forEach(n -> nodeKeys.add(n.asText()));
                }
                tc.setExpectedEntities(nodeKeys);
                // expectedKeywords
                List<String> keywords = new ArrayList<>();
                com.fasterxml.jackson.databind.JsonNode kwNode = tcNode.get("expected_keywords");
                if (kwNode != null && kwNode.isArray()) {
                    kwNode.forEach(k -> keywords.add(k.asText()));
                }
                tc.setExpectedKeywords(keywords);
                tc.setShouldAbstain(false);
                tc.setCreatedAt(LocalDateTime.now());
                cases.add(tc);
            }
        }
        return cases;
    }

    /**
     * 按 intent 分组计算分类准确率。
     */
    private Map<String, CategoryMetric> computeCategoryMetrics(QaEvaluationResult evalResult) {
        Map<String, CategoryMetric> metrics = new LinkedHashMap<>();
        if (evalResult.getCaseResults() == null) {
            return metrics;
        }
        // 按 intent 分组
        Map<String, List<QaEvaluationResult.QaTestCaseResult>> grouped = new LinkedHashMap<>();
        for (QaEvaluationResult.QaTestCaseResult cr : evalResult.getCaseResults()) {
            String intent = cr.getIntent() != null ? cr.getIntent() : "UNKNOWN";
            grouped.computeIfAbsent(intent, k -> new ArrayList<>()).add(cr);
        }
        // 计算每类准确率
        for (Map.Entry<String, List<QaEvaluationResult.QaTestCaseResult>> entry : grouped.entrySet()) {
            String category = entry.getKey();
            List<QaEvaluationResult.QaTestCaseResult> results = entry.getValue();
            int total = results.size();
            int passed = (int) results.stream().filter(QaEvaluationResult.QaTestCaseResult::isPassed).count();
            double accuracy = total > 0 ? (double) passed / total : 0.0;
            double baseline = V2_BASELINE_TARGETS.getOrDefault(category, 0.0);
            CategoryMetric metric = new CategoryMetric();
            metric.setTotalCases(total);
            metric.setPassedCases(passed);
            metric.setAccuracy(accuracy);
            metric.setBaselineTarget(baseline);
            metric.setMeetsBaseline(accuracy >= baseline);
            metrics.put(category, metric);
        }
        return metrics;
    }

    /**
     * 检查是否有分类准确率回归（低于基线 > 5%）。
     */
    private boolean checkRegression(Map<String, CategoryMetric> metrics) {
        for (Map.Entry<String, CategoryMetric> entry : metrics.entrySet()) {
            CategoryMetric m = entry.getValue();
            if (m.getBaselineTarget() - m.getAccuracy() > 0.05) {
                log.warn("H28: regression detected in category {}: accuracy={}, baseline={}",
                        entry.getKey(), m.getAccuracy(), m.getBaselineTarget());
                return true;
            }
        }
        return false;
    }

    /** H28: v2 评测响应 */
    @Data
    public static class QaEvalV2Response {
        /** 完整评测结果 */
        private QaEvaluationResult evalResult;
        /** 分类指标（按 intent 分组） */
        private Map<String, CategoryMetric> categoryMetrics;
        /** 基线目标 */
        private Map<String, Double> baselineTargets;
        /** 是否检测到回归（某分类低于基线 > 5%） */
        private boolean regressionDetected;
    }

    /** H28: 分类指标 */
    @Data
    public static class CategoryMetric {
        /** 该分类用例总数 */
        private int totalCases;
        /** 通过用例数 */
        private int passedCases;
        /** 准确率（0~1） */
        private double accuracy;
        /** 基线目标（0~1） */
        private double baselineTarget;
        /** 是否达到基线 */
        private boolean meetsBaseline;
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
