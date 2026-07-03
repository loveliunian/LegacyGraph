package io.github.legacygraph.qa.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.EnhancedQaAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * QA 评测服务 - 运行评测集并计算质量指标
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaEvaluationService {

    private final EnhancedQaAgent qaAgent;
    private final ObjectMapper objectMapper;

    /**
     * 运行完整评测集
     */
    public List<QaEvaluationResult> runEvaluation(String testSetPath) {
        log.info("开始运行 QA 评测集: {}", testSetPath);
        
        List<QaTestCase> testCases = loadTestCases(testSetPath);
        List<QaEvaluationResult> results = new ArrayList<>();

        for (QaTestCase testCase : testCases) {
            log.info("执行评测用例: {} - {}", testCase.getId(), testCase.getQuestion());
            QaEvaluationResult result = evaluateSingleCase(testCase);
            results.add(result);
        }

        log.info("评测完成，共 {} 个用例", results.size());
        printSummary(results);
        
        return results;
    }

    /**
     * 评测单个用例
     */
    private QaEvaluationResult evaluateSingleCase(QaTestCase testCase) {
        QaEvaluationResult result = new QaEvaluationResult();
        result.setTestCaseId(testCase.getId());
        result.setQuestion(testCase.getQuestion());

        long startTime = System.currentTimeMillis();
        
        try {
            // TODO: qaAgent 仅有 answerStream 异步方法，同步版本待实现
            String answer = "[待实现：同步 answer 方法]";
            
            long endTime = System.currentTimeMillis();
            result.setAnswer(answer);
            result.setResponseTimeMs(endTime - startTime);

            // 计算各项指标
            result.setKeywordCoverageScore(calculateKeywordCoverage(answer, testCase.getExpectedKeywords()));
            result.setEvidenceMatchScore(0.8); // TODO: 从 answer 中提取证据类型并比对
            
            // 综合得分 = 关键词覆盖 * 40% + 证据匹配 * 30% + 响应时间 * 30%
            double timeScore = Math.max(0, 100 - (result.getResponseTimeMs() / 100)); // 10s 内满分
            result.setOverallScore(
                result.getKeywordCoverageScore() * 40 +
                result.getEvidenceMatchScore() * 30 +
                timeScore * 0.3
            );

        } catch (Exception e) {
            log.error("评测用例 {} 执行失败: {}", testCase.getId(), e.getMessage());
            result.setAnswer("ERROR: " + e.getMessage());
            result.setOverallScore(0);
        }

        return result;
    }

    /**
     * 计算关键词覆盖率
     */
    private double calculateKeywordCoverage(String answer, List<String> expectedKeywords) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return 1.0;
        }
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }

        String answerLower = answer.toLowerCase();
        long matchCount = expectedKeywords.stream()
            .filter(keyword -> answerLower.contains(keyword.toLowerCase()))
            .count();

        return (double) matchCount / expectedKeywords.size();
    }

    /**
     * 加载评测集
     */
    private List<QaTestCase> loadTestCases(String path) {
        try {
            InputStream is = getClass().getResourceAsStream(path);
            if (is == null) {
                throw new RuntimeException("评测集文件不存在: " + path);
            }
            Map<String, Object> data = objectMapper.readValue(is, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) data.get("test_cases");
            return cases.stream()
                .map(c -> objectMapper.convertValue(c, QaTestCase.class))
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("加载评测集失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 打印评测摘要
     */
    private void printSummary(List<QaEvaluationResult> results) {
        double avgScore = results.stream().mapToDouble(QaEvaluationResult::getOverallScore).average().orElse(0);
        double avgTime = results.stream().mapToDouble(QaEvaluationResult::getResponseTimeMs).average().orElse(0);
        
        log.info("========== QA 评测摘要 ==========");
        log.info("用例总数: {}", results.size());
        log.info("平均得分: {:.2f}/100", avgScore);
        log.info("平均响应时间: {:.0f}ms", avgTime);
        log.info("=================================");
    }
}
