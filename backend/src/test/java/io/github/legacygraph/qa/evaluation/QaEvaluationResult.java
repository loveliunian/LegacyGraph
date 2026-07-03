package io.github.legacygraph.qa.evaluation;

import lombok.Data;
import java.util.Map;

/**
 * QA 评测结果
 */
@Data
public class QaEvaluationResult {
    private String testCaseId;
    private String question;
    private String answer;
    private double keywordCoverageScore;    // 关键词覆盖率 (0-1)
    private double evidenceMatchScore;      // 证据类型匹配度 (0-1)
    private double responseTimeMs;          // 响应时间
    private double overallScore;            // 综合得分 (0-100)
    private Map<String, Object> details;    // 详细指标
}
