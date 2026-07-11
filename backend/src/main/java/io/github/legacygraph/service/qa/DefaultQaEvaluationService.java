package io.github.legacygraph.service.qa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.EnhancedQaAgent;
import io.github.legacygraph.agent.QaAnswerResult;
import io.github.legacygraph.dto.EvidenceItem;
import io.github.legacygraph.dto.qa.QaEvaluationResult;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.QaTestCase;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.QaTestCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@link QaEvaluationService} 默认实现。
 * <p>
 * 通过 {@link EnhancedQaAgent#answer} 同步执行每条用例，计算四项指标，生成 JSON 报告写入
 * {@code docs/legacygraph/qa-evaluation-{versionId}.json}。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultQaEvaluationService implements QaEvaluationService {

    private final EnhancedQaAgent qaAgent;
    private final QaTestCaseRepository qaTestCaseRepository;
    private final CodeRepoRepository codeRepoRepository;
    private final ObjectMapper objectMapper;

    private static final String DOCS_SUBDIR = "docs/legacygraph";

    /** 当项目根目录无法解析时的回退目录 */
    @Value("${legacy-graph.reports.local-dir:${user.home}/.legacygraph/reports}")
    private String fallbackReportRoot;

    @Override
    public QaEvaluationResult evaluate(String projectId, String versionId, List<QaTestCase> testCases) {
        QaEvaluationResult result = new QaEvaluationResult();
        result.setProjectId(projectId);
        result.setVersionId(versionId);
        result.setEvaluatedAt(LocalDateTime.now());

        if (testCases == null || testCases.isEmpty()) {
            result.setTotalCases(0);
            result.setPassedCases(0);
            result.setEntityRecall(0.0);
            result.setEvidencePrecision(0.0);
            result.setRequiredKeywordCoverage(0.0);
            result.setAbstentionAccuracy(0.0);
            result.setPassed(true);
            result.getFailureReasons().add("NO_TEST_CASES: 无可用测试用例，跳过评测");
            return result;
        }

        // 微观聚合累加器
        long sumExpectedEntities = 0;
        long sumMatchedEntities = 0;
        long sumEvidences = 0;
        long sumValidEvidences = 0;
        long sumExpectedKeywords = 0;
        long sumMatchedKeywords = 0;
        int abstentionCorrect = 0;
        int passedCases = 0;

        for (QaTestCase tc : testCases) {
            QaEvaluationResult.QaTestCaseResult cr = evaluateSingle(projectId, versionId, tc);

            // entityRecall 累加
            List<String> expectedEntities = tc.getExpectedEntities() != null ? tc.getExpectedEntities() : List.of();
            sumExpectedEntities += expectedEntities.size();
            sumMatchedEntities += Math.round(cr.getEntityRecall() * expectedEntities.size());

            // evidencePrecision 累加
            sumEvidences += cr.getEvidenceCount();
            sumValidEvidences += Math.round(cr.getEvidencePrecision() * cr.getEvidenceCount());

            // keywordCoverage 累加
            List<String> expectedKeywords = tc.getExpectedKeywords() != null ? tc.getExpectedKeywords() : List.of();
            sumExpectedKeywords += expectedKeywords.size();
            sumMatchedKeywords += Math.round(cr.getKeywordCoverage() * expectedKeywords.size());

            if (cr.isAbstentionCorrect()) abstentionCorrect++;
            if (cr.isPassed()) passedCases++;

            result.getCaseResults().add(cr);
        }

        int total = testCases.size();
        result.setTotalCases(total);
        result.setPassedCases(passedCases);
        result.setEntityRecall(sumExpectedEntities > 0 ? (double) sumMatchedEntities / sumExpectedEntities : 1.0);
        result.setEvidencePrecision(sumEvidences > 0 ? (double) sumValidEvidences / sumEvidences : 1.0);
        result.setRequiredKeywordCoverage(sumExpectedKeywords > 0 ? (double) sumMatchedKeywords / sumExpectedKeywords : 1.0);
        result.setAbstentionAccuracy(total > 0 ? (double) abstentionCorrect / total : 1.0);

        // 门禁判定
        evaluateGate(result);

        // 写报告
        writeReport(projectId, versionId, result);

        log.info("QA evaluation done: projectId={}, versionId={}, cases={}, entityRecall={}, evidencePrecision={}, keywordCoverage={}, abstentionAccuracy={}, passed={}",
                projectId, versionId, total, format(result.getEntityRecall()), format(result.getEvidencePrecision()),
                format(result.getRequiredKeywordCoverage()), format(result.getAbstentionAccuracy()), result.isPassed());
        return result;
    }

    @Override
    public QaEvaluationResult runSmoke(String projectId, String versionId) {
        List<QaTestCase> smokeCases = qaTestCaseRepository.selectList(
                new LambdaQueryWrapper<QaTestCase>()
                        .eq(QaTestCase::getStatus, "SMOKE")
                        .eq(QaTestCase::getProjectId, projectId)
                        .orderByAsc(QaTestCase::getCreatedAt));
        if (smokeCases == null || smokeCases.isEmpty()) {
            log.warn("DefaultQaEvaluationService: no project-level SMOKE test cases found for projectId={}, skipping smoke evaluation", projectId);
            QaEvaluationResult result = new QaEvaluationResult();
            result.setProjectId(projectId);
            result.setVersionId(versionId);
            result.setEvaluatedAt(LocalDateTime.now());
            result.setTotalCases(0);
            result.setPassedCases(0);
            result.setEntityRecall(0.0);
            result.setEvidencePrecision(0.0);
            result.setRequiredKeywordCoverage(0.0);
            result.setAbstentionAccuracy(0.0);
            result.setPassed(true);
            result.getFailureReasons().add("NO_PROJECT_SMOKE_CASES: 无项目级冒烟案例，跳过评测");
            return result;
        }
        return evaluate(projectId, versionId, smokeCases);
    }

    // ==================== 单条用例评测 ====================

    private QaEvaluationResult.QaTestCaseResult evaluateSingle(String projectId, String versionId, QaTestCase tc) {
        QaEvaluationResult.QaTestCaseResult cr = new QaEvaluationResult.QaTestCaseResult();
        cr.setTestCaseId(tc.getId());
        cr.setQuestion(tc.getQuestion());
        cr.setIntent(tc.getIntent());
        cr.setShouldAbstain(Boolean.TRUE.equals(tc.getShouldAbstain()));

        long start = System.currentTimeMillis();
        QaAnswerResult ar;
        try {
            ar = qaAgent.answer(projectId, versionId, tc.getQuestion());
        } catch (Exception e) {
            log.warn("DefaultQaEvaluationService: case {} failed: {}", tc.getId(), e.getMessage());
            ar = new QaAnswerResult("", List.of(), 0.0, true);
        }
        cr.setResponseTimeMs(System.currentTimeMillis() - start);

        String answer = ar.answer() != null ? ar.answer() : "";
        cr.setAnswer(answer);
        List<EvidenceItem> evidences = ar.evidences() != null ? ar.evidences() : List.of();
        cr.setEvidenceCount(evidences.size());

        List<String> expectedEntities = tc.getExpectedEntities() != null ? tc.getExpectedEntities() : List.of();
        List<String> expectedKeywords = tc.getExpectedKeywords() != null ? tc.getExpectedKeywords() : List.of();

        // entityRecall：期望实体出现在答案中的比例
        String answerLower = answer.toLowerCase(Locale.ROOT);
        long matchedEntities = expectedEntities.stream()
                .filter(e -> e != null && !e.isBlank() && answerLower.contains(e.toLowerCase(Locale.ROOT)))
                .count();
        cr.setEntityRecall(expectedEntities.isEmpty() ? 1.0 : (double) matchedEntities / expectedEntities.size());

        // evidencePrecision：引用证据中有效（命中任一期望实体）的比例
        long validEvidences = evidences.stream()
                .filter(ev -> isEvidenceValid(ev, expectedEntities))
                .count();
        cr.setEvidencePrecision(evidences.isEmpty() ? 1.0 : (double) validEvidences / evidences.size());

        // keywordCoverage：期望关键词被答案覆盖的比例
        long matchedKeywords = expectedKeywords.stream()
                .filter(k -> k != null && !k.isBlank() && answerLower.contains(k.toLowerCase(Locale.ROOT)))
                .count();
        cr.setKeywordCoverage(expectedKeywords.isEmpty() ? 1.0 : (double) matchedKeywords / expectedKeywords.size());

        // abstentionAccuracy：该拒答的拒答 + 该回答的回答
        boolean abstained = isAbstention(answer);
        boolean abstentionCorrect = (cr.isShouldAbstain() && abstained) || (!cr.isShouldAbstain() && !abstained);
        cr.setAbstentionCorrect(abstentionCorrect);

        // 单条用例通过判定
        List<String> failures = new ArrayList<>();
        if (!expectedEntities.isEmpty() && cr.getEntityRecall() < 1.0) {
            failures.add("entityRecall=" + format(cr.getEntityRecall()));
        }
        if (!evidences.isEmpty() && cr.getEvidencePrecision() < 1.0) {
            failures.add("evidencePrecision=" + format(cr.getEvidencePrecision()));
        }
        if (!expectedKeywords.isEmpty() && cr.getKeywordCoverage() < 1.0) {
            failures.add("keywordCoverage=" + format(cr.getKeywordCoverage()));
        }
        if (!abstentionCorrect) {
            failures.add("abstentionIncorrect(want=" + (cr.isShouldAbstain() ? "abstain" : "answer") + ")");
        }
        if (ar.error()) {
            failures.add("agentError");
        }
        cr.setPassed(failures.isEmpty());
        cr.setFailureReason(failures.isEmpty() ? null : String.join("; ", failures));
        return cr;
    }

    // ==================== 门禁判定 ====================

    private void evaluateGate(QaEvaluationResult result) {
        List<String> reasons = new ArrayList<>();
        if (result.getEntityRecall() < 0.85) {
            reasons.add("ENTITY_RECALL_BELOW_THRESHOLD: " + format(result.getEntityRecall()) + " < 0.85");
        }
        if (result.getEvidencePrecision() < 0.90) {
            reasons.add("EVIDENCE_PRECISION_BELOW_THRESHOLD: " + format(result.getEvidencePrecision()) + " < 0.90");
        }
        if (result.getAbstentionAccuracy() < 0.95) {
            reasons.add("ABSTENTION_ACCURACY_BELOW_THRESHOLD: " + format(result.getAbstentionAccuracy()) + " < 0.95");
        }
        result.setPassed(reasons.isEmpty());
        result.getFailureReasons().addAll(reasons);
    }

    // ==================== 辅助方法 ====================

    /**
     * 证据是否有效：title / excerpt / sourceFile 任一字段命中期望实体。
     */
    private boolean isEvidenceValid(EvidenceItem ev, List<String> expectedEntities) {
        if (expectedEntities == null || expectedEntities.isEmpty()) {
            return true;
        }
        if (ev == null) {
            return false;
        }
        String title = ev.getTitle() != null ? ev.getTitle() : "";
        String excerpt = ev.getExcerpt() != null ? ev.getExcerpt() : "";
        String sourceFile = ev.getSourceFile() != null ? ev.getSourceFile() : "";
        String combined = (title + " " + excerpt + " " + sourceFile).toLowerCase(Locale.ROOT);
        return expectedEntities.stream()
                .anyMatch(e -> e != null && !e.isBlank() && combined.contains(e.toLowerCase(Locale.ROOT)));
    }

    /**
     * 判断是否为拒答回答。
     */
    private boolean isAbstention(String answer) {
        if (answer == null || answer.isBlank() || answer.trim().length() < 20) {
            return true;
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        return ABSTENTION_PHRASES.stream().anyMatch(lower::contains);
    }

    private String format(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    // ==================== 报告写出 ====================

    private void writeReport(String projectId, String versionId, QaEvaluationResult result) {
        Path docsDir = resolveDocsDir(projectId);
        if (docsDir == null) {
            log.warn("DefaultQaEvaluationService: cannot resolve docs dir for projectId={}, skip writing report", projectId);
            return;
        }
        try {
            Files.createDirectories(docsDir);
        } catch (IOException e) {
            log.warn("DefaultQaEvaluationService: failed to create docs dir {}: {}", docsDir, e.getMessage());
            return;
        }
        String fileName = "qa-evaluation-" + (versionId != null ? versionId : "unknown") + ".json";
        Path reportFile = docsDir.resolve(fileName);
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            Files.writeString(reportFile, json, StandardCharsets.UTF_8);
            log.info("DefaultQaEvaluationService: qa evaluation report written to {}", reportFile);
        } catch (IOException e) {
            log.warn("DefaultQaEvaluationService: failed to write report to {}: {}", reportFile, e.getMessage());
        }
    }

    /**
     * 解析项目根目录下的 docs/legacygraph 目录（与 GraphQualityAssessor / ScanArtifactPublisher 同口径）。
     * <p>
     * 规则：
     * <ul>
     *   <li>无 repo → 回退 {@code ~/.legacygraph/reports/{projectId}/docs/legacygraph}</li>
     *   <li>单 repo → repo.localPath 的父目录 + docs/legacygraph</li>
     *   <li>多 repo → 最长公共父目录 + docs/legacygraph</li>
     *   <li>公共父目录是 user.home 根或 / 时 → 回退</li>
     * </ul>
     */
    private Path resolveDocsDir(String projectId) {
        List<CodeRepo> repos = codeRepoRepository.selectList(
                new LambdaQueryWrapper<CodeRepo>().eq(CodeRepo::getProjectId, projectId));
        if (repos == null || repos.isEmpty()) {
            return fallbackDocsDir(projectId);
        }
        List<Path> localPaths = repos.stream()
                .map(CodeRepo::getLocalPath)
                .filter(p -> p != null && !p.isBlank())
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .collect(Collectors.toList());
        if (localPaths.isEmpty()) {
            return fallbackDocsDir(projectId);
        }
        Path commonParent = longestCommonParent(localPaths);
        if (commonParent == null) {
            return fallbackDocsDir(projectId);
        }
        String home = System.getProperty("user.home");
        String commonPath = commonParent.toString();
        if (commonPath.equals("/") || commonPath.equals(home)
                || commonPath.equals(Path.of(home).getRoot().toString())) {
            return fallbackDocsDir(projectId);
        }
        return commonParent.resolve(DOCS_SUBDIR);
    }

    private Path fallbackDocsDir(String projectId) {
        return Path.of(fallbackReportRoot, projectId, DOCS_SUBDIR);
    }

    private Path longestCommonParent(List<Path> paths) {
        if (paths.isEmpty()) return null;
        Path common = paths.get(0);
        for (int i = 1; i < paths.size(); i++) {
            common = commonRoot(common, paths.get(i));
            if (common == null) return null;
        }
        return common;
    }

    private Path commonRoot(Path a, Path b) {
        Path common = a.getRoot();
        if (common == null || !common.equals(b.getRoot())) {
            return null;
        }
        int n = Math.min(a.getNameCount(), b.getNameCount());
        for (int i = 0; i < n; i++) {
            if (!a.getName(i).equals(b.getName(i))) {
                break;
            }
            common = common.resolve(a.getName(i));
        }
        return common;
    }
}
