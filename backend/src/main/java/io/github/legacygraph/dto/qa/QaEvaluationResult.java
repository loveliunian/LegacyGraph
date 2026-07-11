package io.github.legacygraph.dto.qa;

import io.github.legacygraph.dto.evaluation.RagasReport;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * QA 评测结果 — 一次评测（evaluate / runSmoke）的汇总。
 * <p>
 * 包含四个核心指标分数、每条测试用例的详细记录、是否通过门禁及失败原因。
 * </p>
 */
@Data
public class QaEvaluationResult {

    private String projectId;
    private String versionId;
    private LocalDateTime evaluatedAt;

    /** 期望实体被答案引用的比例（0~1） */
    private double entityRecall;

    /** 答案引用的证据中有效的比例（0~1） */
    private double evidencePrecision;

    /** 期望关键词被答案覆盖的比例（0~1） */
    private double requiredKeywordCoverage;

    /** 拒答准确性：该拒答的拒答 + 该回答的回答 比例（0~1） */
    private double abstentionAccuracy;

    /** 用例总数 */
    private int totalCases;

    /** 通过用例数 */
    private int passedCases;

    /** 是否通过门禁 */
    private boolean passed;

    /** G-10: 聚合 Ragas 指标 — 上下文精确度（0~1） */
    private double ragasContextPrecision;
    /** G-10: 聚合 Ragas 指标 — 上下文召回率（0~1） */
    private double ragasContextRecall;
    /** G-10: 聚合 Ragas 指标 — 答案忠实度（0~1） */
    private double ragasFaithfulness;
    /** G-10: 聚合 Ragas 指标 — 答案相关性（0~1） */
    private double ragasAnswerRelevancy;

    /** 未通过原因（passed=false 时填充） */
    private List<String> failureReasons = new ArrayList<>();

    /** 每条用例的详细结果 */
    private List<QaTestCaseResult> caseResults = new ArrayList<>();

    /**
     * 单条测试用例的评测记录。
     */
    @Data
    public static class QaTestCaseResult {

        private String testCaseId;
        private String question;
        private String intent;
        private boolean shouldAbstain;

        /** 实际回答 */
        private String answer;

        /** 引用的证据数 */
        private int evidenceCount;

        /** 该用例 entityRecall（0~1） */
        private double entityRecall;

        /** 该用例 evidencePrecision（0~1） */
        private double evidencePrecision;

        /** 该用例关键词覆盖率（0~1） */
        private double keywordCoverage;

        /** 是否正确拒答 / 正确回答（与 shouldAbstain 一致） */
        private boolean abstentionCorrect;

        /** 该用例是否通过 */
        private boolean passed;

        /** 失败原因（passed=false 时填充） */
        private String failureReason;

        /** 响应耗时 ms */
        private long responseTimeMs;

        /** Ragas 指标报告（contextPrecision / contextRecall / faithfulness / answerRelevancy） */
        private RagasReport ragasReport;
    }
}
